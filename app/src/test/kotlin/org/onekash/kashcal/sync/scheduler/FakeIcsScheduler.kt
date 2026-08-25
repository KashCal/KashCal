package org.onekash.kashcal.sync.scheduler

/**
 * Test fake for [IcsScheduler] that records invocations.
 */
class FakeIcsScheduler : IcsScheduler {
    private val _ensureCalls = mutableListOf<Long>()
    private val _callLog = mutableListOf<String>()

    /** Intervals passed to [ensurePeriodicRefresh], in call order. */
    val ensureCalls: List<Long> get() = _ensureCalls.toList()

    var cancelCalls: Int = 0
        private set

    /**
     * Entry/exit markers for every call, so a test can tell serialized calls from
     * interleaved ones.
     */
    val callLog: List<String> get() = _callLog.toList()

    /** Runs inside [ensurePeriodicRefresh]; use it to suspend mid-call. */
    var duringEnsure: (suspend () -> Unit)? = null

    /** Runs inside [cancelPeriodicRefresh]; use it to suspend mid-call. */
    var duringCancel: (suspend () -> Unit)? = null

    /** Thrown by [ensurePeriodicRefresh] and [cancelPeriodicRefresh] when set. */
    var failWith: Exception? = null

    override suspend fun ensurePeriodicRefresh(intervalHours: Long) {
        _callLog.add("ensure-enter:$intervalHours")
        _ensureCalls.add(intervalHours)
        duringEnsure?.invoke()
        failWith?.let { throw it }
        _callLog.add("ensure-exit:$intervalHours")
    }

    override suspend fun cancelPeriodicRefresh() {
        _callLog.add("cancel-enter")
        cancelCalls += 1
        duringCancel?.invoke()
        failWith?.let { throw it }
        _callLog.add("cancel-exit")
    }
}
