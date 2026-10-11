package staff.android.runtime

fun main(args: Array<String>) {
    when (args.singleOrNull() ?: "queue") {
        "queue" -> queueDemo().forEach(::println)
        "counter" -> {
            println("Two increments with volatile: ${lostIncrement()}")
            println("Two atomic increments: ${atomicIncrement()}")
        }
        "publication" -> {
            val stock = StockPublication()
            println("Publish revision 2: ${stock.publish(StockSnapshot(2, 8))}")
            println("Publish revision 1: ${stock.publish(StockSnapshot(1, 3))}")
            println("Read: ${stock.read()}")
        }
        "allocation" -> {
            val rows = listOf(RowValue(1, "Camera"), RowValue(2, "Bag"), RowValue(3, "Lens"))
            val rebuilt = rebuildRows(rows)
            val changed = replaceOne(rows, 2, "Travel bag")
            val sharedRebuilt = rows.indices.count { rows[it] === rebuilt[it] }
            val sharedChanged = rows.indices.count { rows[it] === changed[it] }
            println("Rebuild shares row objects: $sharedRebuilt / 3")
            println("Single edit shares row objects: $sharedChanged / 3")
            println("Original second title: ${rows[1].title}")
            println("Edited second title: ${changed[1].title}")
        }
        "presence" -> presenceDemo()
        else -> error("Modes: queue, counter, publication, allocation, presence")
    }
}

// region presence-demo
fun presenceDemo() {
    val source = ControlledPresenceSource(Presence(0, false))
    val ui = ManualQueue()
    val shown = mutableListOf<Presence>()
    val binding = ReferencePresenceBinding(source, ui)
    binding.attach(PresenceSink { shown += it })
    println("Before UI drain: $shown")
    ui.drain()
    source.emit(0, Presence(2, true))
    source.emit(0, Presence(1, false))
    ui.drain()
    println("After versions 2 then 1: $shown")
    source.emit(0, Presence(3, false))
    binding.detach()
    ui.drain()
    println("After detach and drain: $shown")
    println("Open registrations: ${source.activeCount}")
}
// endregion
