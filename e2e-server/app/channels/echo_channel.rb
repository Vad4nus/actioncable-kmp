class EchoChannel < ActionCable::Channel::Base
  def subscribed
    return reject if params[:reject]
    sleep(params[:slow].to_f) if params[:slow]
    stream_from "echo:#{params[:room]}"
  end

  def echo(data)
    ActionCable.server.broadcast("echo:#{params[:room]}", data["payload"])
  end

  def flood(data)
    data["count"].times { |i| transmit(i) }
    ActionCable.server.broadcast("echo:#{data["done"]}", "done")
  end

  def kick(data)
    connection.close(reason: data["reason"], reconnect: data.fetch("reconnect", false))
  end
end
