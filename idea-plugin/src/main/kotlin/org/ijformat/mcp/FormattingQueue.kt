package org.ijformat.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal class FormattingFailure(val code: Int, message: String) : RuntimeException(message) {
    companion object {
        const val BUSY = -32010
        const val QUEUE_TIMEOUT = -32011
        const val FORMAT_TIMEOUT = -32012
    }
}

internal class FormattingQueue(scope: CoroutineScope, private val limits: () -> WorkerLimits) {
    private class Task(
        val bytes: Int,
        val startBy: Long,
        val finishBy: Long,
        val format: suspend (() -> Unit) -> String,
    ) {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String>()
        var cancelled = false
        var work: Job? = null
    }

    private val pending = ArrayDeque<Task>()
    private val ready = Channel<Unit>(Channel.CONFLATED)
    private var pendingBytes = 0

    init {
        scope.launch {
            for (signal in ready) {
                while (true) {
                    val task = synchronized(pending) {
                        pending.removeFirstOrNull()?.also { pendingBytes -= it.bytes }
                    } ?: break
                    try {
                        if (task.startBy - System.nanoTime() <= 0) throw queueTimeout()
                        val formatted = supervisorScope {
                            val work = async(start = CoroutineStart.LAZY) {
                                task.format {
                                    synchronized(pending) {
                                        if (task.cancelled || task.startBy - System.nanoTime() <= 0) throw queueTimeout()
                                        task.started.complete(Unit)
                                    }
                                }
                            }
                            synchronized(pending) {
                                task.work = work
                                if (task.cancelled) work.cancel()
                            }
                            work.await()
                        }
                        if (task.finishBy - System.nanoTime() > 0) task.result.complete(formatted)
                        else task.result.completeExceptionally(formatTimeout())
                    } catch (failure: Exception) {
                        task.result.completeExceptionally(failure)
                        if (failure is CancellationException && !isActive) throw failure
                    } finally {
                        task.started.complete(Unit)
                    }
                }
            }
        }
    }

    suspend fun format(bytes: Int, timeoutMs: Int?, action: suspend (() -> Unit) -> String): String {
        val settings = limits()
        val timeout = timeoutMs ?: settings.timeoutMs
        val now = System.nanoTime()
        val startBy = now + TimeUnit.MILLISECONDS.toNanos(minOf(settings.queueTimeoutMs, timeout).toLong())
        val finishBy = now + TimeUnit.MILLISECONDS.toNanos(timeout.toLong())
        val task = Task(bytes, startBy, finishBy, action)
        synchronized(pending) {
            if (pending.size >= settings.queueCapacity || pendingBytes + bytes > MAX_QUEUED_BYTES) {
                throw FormattingFailure(FormattingFailure.BUSY, "Formatting queue is full")
            }
            pending.addLast(task)
            pendingBytes += bytes
        }
        ready.trySend(Unit)
        try {
            if (withTimeoutOrNull(millisecondsLeft(startBy)) { task.started.await() } == null) {
                synchronized(pending) {
                    if (!task.started.isCompleted) {
                        task.cancelled = true
                        throw queueTimeout()
                    }
                }
            }
            return withTimeoutOrNull(millisecondsLeft(finishBy)) { task.result.await() } ?: throw formatTimeout()
        } finally {
            synchronized(pending) {
                task.cancelled = true
                if (pending.remove(task)) pendingBytes -= task.bytes
                if (!task.started.isCompleted) task.work?.cancel()
            }
        }
    }

    private fun millisecondsLeft(deadline: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)

    private fun queueTimeout() = FormattingFailure(FormattingFailure.QUEUE_TIMEOUT, "Timed out in the formatting queue")
    private fun formatTimeout() = FormattingFailure(FormattingFailure.FORMAT_TIMEOUT, "Timed out waiting for IDEA formatting")

    private companion object {
        const val MAX_QUEUED_BYTES = 2 * 1024 * 1024
    }
}
