package staff.android.runtime

import java.util.concurrent.Executor

// region presence-contract
data class Presence(val version: Long, val available: Boolean)

fun interface Registration { fun close() }

interface PresenceSource {
    // May call listener inline, later, or concurrently on arbitrary threads.
    fun subscribe(listener: (Presence) -> Unit): Registration
}

fun interface PresenceSink { fun show(report: Presence) }

interface PresenceBinding {
    // Both methods belong to the UI lane.
    // ui.execute always enqueues there.
    fun attach(sink: PresenceSink)
    fun detach()
}
// endregion

// region reference-presence
class ReferencePresenceBinding(
    private val source: PresenceSource,
    private val ui: Executor
) : PresenceBinding {
    private class Session(var lastVersion: Long = -1)
    private var current: Session? = null
    private var sink: PresenceSink? = null
    private var registration: Registration? = null

    override fun attach(sink: PresenceSink) {
        detach()
        val session = Session()
        current = session
        this.sink = sink
        registration = source.subscribe { report ->
            enqueue(session, report)
        }
    }

    private fun enqueue(session: Session, report: Presence) {
        ui.execute {
            if (current !== session) return@execute
            if (report.version <= session.lastVersion) return@execute
            session.lastVersion = report.version
            sink?.show(report)
        }
    }

    override fun detach() {
        current = null
        sink = null
        val old = registration
        registration = null
        old?.close()
    }
}
// endregion

// region learner-presence
class LearnerPresenceBinding(
    private val source: PresenceSource,
    private val ui: Executor
) : PresenceBinding {
    override fun attach(sink: PresenceSink) {
        source.subscribe { report -> sink.show(report) }
    }
    override fun detach() = Unit
}
// endregion
