package staff.android.runtime

import java.util.ArrayDeque
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

// region manual-queue
class ManualQueue : Executor {
    private val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) {
        synchronized(tasks) { tasks.addLast(command) }
    }
    val size: Int get() = synchronized(tasks) { tasks.size }
    fun drain() {
        while (true) {
            val next = synchronized(tasks) { tasks.pollFirst() } ?: return
            next.run()
        }
    }
}
// endregion

// region queue-demo
fun queueDemo(): List<String> {
    val main = ManualQueue()
    val events = mutableListOf<String>()
    var status = "Ready"
    main.execute {
        status = "Checking"
        main.execute { events += "Queued probe: $status" }
        status = "Available"
        events += "Click returned: $status"
    }
    main.drain()
    return events
}
// endregion

// region volatile-counter
class VolatileCounter {
    @Volatile var value = 0
    fun incrementAt(barrier: CyclicBarrier) {
        val before = value
        barrier.await()
        value = before + 1
    }
}

fun lostIncrement(): Int {
    val counter = VolatileCounter()
    val barrier = CyclicBarrier(2)
    val workers = List(2) { thread { counter.incrementAt(barrier) } }
    workers.forEach { it.join() }
    return counter.value
}

fun atomicIncrement(): Int {
    val counter = AtomicInteger()
    val barrier = CyclicBarrier(2)
    val workers = List(2) {
        thread { barrier.await(); counter.incrementAndGet() }
    }
    workers.forEach { it.join() }
    return counter.get()
}
// endregion

// region coherent-snapshot
data class StockSnapshot(val revision: Long, val available: Int)

class StockPublication {
    private val state = AtomicReference(StockSnapshot(0, 0))
    fun read(): StockSnapshot = state.get()
    fun publish(next: StockSnapshot): Boolean {
        require(next.revision >= 0 && next.available >= 0)
        while (true) {
            val before = state.get()
            if (next.revision <= before.revision) return false
            if (state.compareAndSet(before, next)) return true
        }
    }
}
// endregion

// region allocation-probe
data class RowValue(val id: Int, val title: String)

fun rebuildRows(rows: List<RowValue>): List<RowValue> =
    rows.map { it.copy() }

fun replaceOne(rows: List<RowValue>, id: Int, title: String): List<RowValue> =
    rows.map { if (it.id == id) it.copy(title = title) else it }
// endregion
