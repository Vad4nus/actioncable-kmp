"""Fault-injecting TCP proxy in front of the reference Rails server, for the stress suite.

Runs Rails on --rails-port as a child process, proxies --proxy-port to it and takes
commands on --control-port:

  POST /mode?name=pass|blackhole        forward, or hold every byte in both directions
  POST /mode?name=latency&ms=N          delay each chunk by N ms in each direction
  POST /mode?name=throttle&bps=N        cap each direction at N bytes per second
  POST /mode?name=refuse&seconds=N      close the listener; reopen after N seconds (0: never)
  POST /cut                             forward half of the next server chunk, then close
  POST /drop                            reset every proxied connection
  POST /server/stop, POST /server/start stop or start Rails itself
  GET  /stats                           JSON with mode, accepted and open connections
"""

import argparse
import asyncio
import json
import os
import signal
import socket
import struct
import time
from urllib.parse import parse_qs, urlparse

HOSTS = ["127.0.0.1", "::1"]


class Chaos:
    def __init__(self, args):
        self.args = args
        self.mode = "pass"
        self.latency = 0.0
        self.bps = 0
        self.cut = False
        self.accepted = 0
        self.links = set()
        self.listener = None
        self.reopen = None
        self.rails = None

    async def listen(self):
        self.listener = await asyncio.start_server(self.link, HOSTS, self.args.proxy_port, reuse_address=True)

    async def unlisten(self):
        if self.listener is not None:
            self.listener.close()
            self.listener = None

    async def link(self, client_reader, client_writer):
        self.accepted += 1
        try:
            server_reader, server_writer = await asyncio.open_connection("127.0.0.1", self.args.rails_port)
        except OSError:
            client_writer.transport.abort()
            return
        writers = (client_writer, server_writer)
        self.links.add(writers)
        tasks = []
        for reader, writer, downstream in ((client_reader, server_writer, False), (server_reader, client_writer, True)):
            queue = asyncio.Queue()
            tasks.append(asyncio.ensure_future(self.receive(reader, queue)))
            tasks.append(asyncio.ensure_future(self.forward(queue, writer, downstream)))
        try:
            await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for task in tasks:
                task.cancel()
            for writer in writers:
                writer.close()
            self.links.discard(writers)

    async def receive(self, reader, queue):
        while True:
            data = await reader.read(65536)
            if not data:
                return
            queue.put_nowait((time.monotonic(), data))

    async def forward(self, queue, writer, downstream):
        while True:
            arrived, data = await queue.get()
            while self.mode == "blackhole":
                await asyncio.sleep(0.05)
            wait = arrived + self.latency - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            if downstream and self.cut:
                self.cut = False
                writer.write(data[: max(1, len(data) // 2)])
                await writer.drain()
                return
            bps = self.bps
            step = max(1, bps // 20) if bps else len(data)
            for i in range(0, len(data), step):
                writer.write(data[i:i + step])
                await writer.drain()
                if bps:
                    await asyncio.sleep(step / bps)

    async def set_mode(self, query):
        name = query.get("name", ["pass"])[0]
        if self.reopen is not None:
            self.reopen.cancel()
            self.reopen = None
        if self.listener is None:
            await self.listen()
        self.mode, self.latency, self.bps = "pass", 0.0, 0
        if name == "blackhole":
            self.mode = "blackhole"
        elif name == "latency":
            self.latency = float(query["ms"][0]) / 1000
        elif name == "throttle":
            self.bps = int(query["bps"][0])
        elif name == "refuse":
            await self.unlisten()
            seconds = float(query.get("seconds", ["0"])[0])
            if seconds > 0:
                self.reopen = asyncio.ensure_future(self.listen_after(seconds))
        elif name != "pass":
            raise ValueError(name)

    async def listen_after(self, seconds):
        await asyncio.sleep(seconds)
        await self.listen()
        self.reopen = None

    def drop(self):
        for writers in list(self.links):
            for writer in writers:
                writer.get_extra_info("socket").setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                writer.transport.abort()

    async def start_rails(self):
        env = dict(os.environ, PORT=str(self.args.rails_port))
        self.rails = await asyncio.create_subprocess_exec(
            "bin/rails", "server", "-p", str(self.args.rails_port), "-b", "0.0.0.0",
            cwd=self.args.rails_dir, env=env,
        )
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                _, writer = await asyncio.open_connection("127.0.0.1", self.args.rails_port)
                writer.close()
                return
            except OSError:
                await asyncio.sleep(0.2)
        raise RuntimeError("rails did not start")

    async def stop_rails(self):
        if self.rails is not None and self.rails.returncode is None:
            self.rails.terminate()
            await self.rails.wait()
        self.rails = None

    def stats(self):
        return {"mode": self.mode, "latency_ms": int(self.latency * 1000), "bps": self.bps,
                "listening": self.listener is not None, "accepted": self.accepted, "open": len(self.links),
                "rails": self.rails is not None and self.rails.returncode is None}

    async def control(self, reader, writer):
        status, body = 200, {}
        try:
            line = (await reader.readline()).decode()
            while (await reader.readline()) not in (b"\r\n", b"\n", b""):
                pass
            method, target = line.split(" ")[:2]
            url = urlparse(target)
            query = parse_qs(url.query)
            if (method, url.path) == ("POST", "/mode"):
                await self.set_mode(query)
            elif (method, url.path) == ("POST", "/cut"):
                self.cut = True
            elif (method, url.path) == ("POST", "/drop"):
                self.drop()
            elif (method, url.path) == ("POST", "/server/stop"):
                await self.stop_rails()
            elif (method, url.path) == ("POST", "/server/start"):
                if self.rails is None:
                    await self.start_rails()
            elif (method, url.path) != ("GET", "/stats"):
                status = 404
            body = self.stats()
        except Exception as e:
            status, body = 500, {"error": repr(e)}
        payload = json.dumps(body).encode()
        writer.write(b"HTTP/1.1 %d X\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s"
                     % (status, len(payload), payload))
        await writer.drain()
        writer.close()


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rails-port", type=int, default=3000)
    parser.add_argument("--proxy-port", type=int, default=3100)
    parser.add_argument("--control-port", type=int, default=3101)
    parser.add_argument("--rails-dir", default=os.path.dirname(os.path.abspath(__file__)))
    chaos = Chaos(parser.parse_args())
    await chaos.start_rails()
    await chaos.listen()
    control = await asyncio.start_server(chaos.control, HOSTS, chaos.args.control_port, reuse_address=True)
    print("chaos: proxy %d -> rails %d, control %d" % (chaos.args.proxy_port, chaos.args.rails_port,
                                                       chaos.args.control_port), flush=True)
    stop = asyncio.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        asyncio.get_running_loop().add_signal_handler(sig, stop.set)
    try:
        await stop.wait()
    finally:
        control.close()
        await chaos.stop_rails()


if __name__ == "__main__":
    asyncio.run(main())
