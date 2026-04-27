package org.onekash.kashcal.sync.scheduler

/**
 * Test fake for [IcsScheduler] that records invocations.
 */
class FakeIcsScheduler : IcsScheduler {
    private val _scheduleCalls = mutableListOf<Long>()
    val scheduleCalls: List<Long> get() = _scheduleCalls.toList()

    var cancelCalls: Int = 0
        private set

    override fun schedulePeriodicRefresh(intervalHours: Long) {
        _scheduleCalls.add(intervalHours)
    }

    override fun cancelPeriodicRefresh() {
        cancelCalls += 1
    }
}
