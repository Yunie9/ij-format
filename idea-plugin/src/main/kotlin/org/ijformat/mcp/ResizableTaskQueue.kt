package org.ijformat.mcp

import java.util.AbstractQueue
import java.util.ArrayDeque
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** FIFO queue whose limit applies to new admissions. A shrink never evicts queued tasks. */
internal class ResizableTaskQueue(initialCapacity: Int) : AbstractQueue<Runnable>(), BlockingQueue<Runnable> {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()
    private val tasks = ArrayDeque<Runnable>()
    private var capacity = initialCapacity

    init {
        require(initialCapacity > 0)
    }

    fun resize(newCapacity: Int) = lock.withLock {
        require(newCapacity > 0)
        val grew = newCapacity > capacity
        capacity = newCapacity
        if (grew) notFull.signalAll()
    }

    override val size: Int get() = lock.withLock { tasks.size }
    override fun remainingCapacity(): Int = lock.withLock { (capacity - tasks.size).coerceAtLeast(0) }

    override fun offer(task: Runnable): Boolean = lock.withLock {
        if (tasks.size >= capacity) return false
        enqueue(task)
        true
    }

    @Throws(InterruptedException::class)
    override fun put(task: Runnable) {
        lock.lockInterruptibly()
        try {
            while (tasks.size >= capacity) notFull.await()
            enqueue(task)
        } finally {
            lock.unlock()
        }
    }

    @Throws(InterruptedException::class)
    override fun offer(task: Runnable, timeout: Long, unit: TimeUnit): Boolean {
        var remaining = unit.toNanos(timeout)
        lock.lockInterruptibly()
        try {
            while (tasks.size >= capacity) {
                if (remaining <= 0) return false
                remaining = notFull.awaitNanos(remaining)
            }
            enqueue(task)
            return true
        } finally {
            lock.unlock()
        }
    }

    override fun peek(): Runnable? = lock.withLock { tasks.peekFirst() }
    override fun poll(): Runnable? = lock.withLock { dequeue() }

    @Throws(InterruptedException::class)
    override fun take(): Runnable {
        lock.lockInterruptibly()
        try {
            while (tasks.isEmpty()) notEmpty.await()
            return dequeue()!!
        } finally {
            lock.unlock()
        }
    }

    @Throws(InterruptedException::class)
    override fun poll(timeout: Long, unit: TimeUnit): Runnable? {
        var remaining = unit.toNanos(timeout)
        lock.lockInterruptibly()
        try {
            while (tasks.isEmpty()) {
                if (remaining <= 0) return null
                remaining = notEmpty.awaitNanos(remaining)
            }
            return dequeue()
        } finally {
            lock.unlock()
        }
    }

    override fun remove(element: Runnable): Boolean = lock.withLock {
        tasks.removeFirstOccurrence(element).also { if (it) signalSpace() }
    }

    override fun drainTo(destination: MutableCollection<in Runnable>): Int = drainTo(destination, Int.MAX_VALUE)

    override fun drainTo(destination: MutableCollection<in Runnable>, maxElements: Int): Int = lock.withLock {
        require(destination !== this)
        var drained = 0
        try {
            while (drained < maxElements && tasks.isNotEmpty()) {
                destination.add(tasks.first)
                tasks.removeFirst()
                drained++
            }
            drained
        } finally {
            if (drained > 0 && tasks.size < capacity) notFull.signalAll()
        }
    }

    override fun iterator(): MutableIterator<Runnable> {
        val snapshot = lock.withLock { tasks.toMutableList().iterator() }
        return object : MutableIterator<Runnable> {
            private var last: Runnable? = null

            override fun hasNext() = snapshot.hasNext()
            override fun next(): Runnable = snapshot.next().also { last = it }
            override fun remove() {
                val task = checkNotNull(last)
                last = null
                lock.withLock {
                    val current = tasks.iterator()
                    while (current.hasNext()) {
                        if (current.next() === task) {
                            current.remove()
                            signalSpace()
                            break
                        }
                    }
                }
            }
        }
    }

    private fun enqueue(task: Runnable) {
        tasks.addLast(task)
        notEmpty.signal()
    }

    private fun dequeue(): Runnable? = tasks.pollFirst()?.also { signalSpace() }

    private fun signalSpace() {
        if (tasks.size < capacity) notFull.signal()
    }
}
