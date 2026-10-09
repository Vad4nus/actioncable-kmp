package io.github.vad4nus.actioncable

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import platform.darwin.KERN_SUCCESS
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_port_deallocate
import platform.darwin.mach_task_self_
import platform.darwin.task_threads
import platform.darwin.thread_act_array_tVar
import platform.darwin.thread_act_tVar
import platform.darwin.vm_deallocate
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun liveThreads(): Int = memScoped {
    val threads = alloc<thread_act_array_tVar>()
    val count = alloc<mach_msg_type_number_tVar>()
    check(task_threads(mach_task_self_, threads.ptr, count.ptr) == KERN_SUCCESS)
    val n = count.value.toInt()
    val list = checkNotNull(threads.value)
    for (i in 0 until n) mach_port_deallocate(mach_task_self_, list[i])
    vm_deallocate(mach_task_self_, list.toLong().toULong(), (n * sizeOf<thread_act_tVar>()).toULong())
    n
}

@OptIn(NativeRuntimeApi::class)
internal actual fun collectGarbage() = GC.collect()

@OptIn(ExperimentalNativeApi::class)
internal actual fun weakRef(value: Any): () -> Any? = WeakReference(value)::get
