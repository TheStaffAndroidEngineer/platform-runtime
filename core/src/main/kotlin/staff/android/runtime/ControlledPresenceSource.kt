package staff.android.runtime

// Test/demo fixture; saved listeners deliberately remain available to model
// callbacks already in flight at close. This is not a production registry.
class ControlledPresenceSource(var initial: Presence? = null) : PresenceSource {
    private val listeners = mutableListOf<(Presence) -> Unit>()
    private val open = mutableSetOf<Int>()
    var closeCalls = 0
        private set
    val activeCount: Int get() = synchronized(this) { open.size }
    override fun subscribe(listener: (Presence) -> Unit): Registration {
        val index = synchronized(this) {
            listeners += listener
            listeners.lastIndex.also { open += it }
        }
        initial?.let(listener)
        return Registration {
            synchronized(this) {
                closeCalls++ // Count invocations, including an accidental duplicate close.
                open.remove(index)
            }
        }
    }
    fun emit(index: Int, report: Presence) {
        val listener = synchronized(this) { listeners[index] }
        listener(report)
    }
}
