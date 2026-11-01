package org.onekash.kashcal.reminder.device

import android.app.job.JobInfo
import android.app.job.JobScheduler

/**
 * The shared [DeviceCalendarChangeWatcher.Jobs] double. Records every schedule and cancel; keeps
 * pending jobs in memory, or passes calls to [delegate] (the Robolectric JobScheduler) when one is
 * given. [failWith] makes every call throw; [refuse] makes schedule return
 * [JobScheduler.RESULT_FAILURE] without keeping the job.
 */
internal class FakeChangeJobs(
    private val delegate: DeviceCalendarChangeWatcher.Jobs? = null,
) : DeviceCalendarChangeWatcher.Jobs {
    val scheduled = mutableListOf<JobInfo>()
    val cancelled = mutableListOf<Int>()
    var failWith: Exception? = null
    var refuse = false
    private val pending = mutableMapOf<Int, JobInfo>()

    override fun pending(id: Int): JobInfo? {
        failWith?.let { throw it }
        return delegate?.pending(id) ?: pending[id]
    }

    override fun schedule(job: JobInfo): Int {
        failWith?.let { throw it }
        if (refuse) return JobScheduler.RESULT_FAILURE
        scheduled += job
        pending[job.id] = job
        return delegate?.schedule(job) ?: JobScheduler.RESULT_SUCCESS
    }

    override fun cancel(id: Int) {
        failWith?.let { throw it }
        cancelled += id
        pending.remove(id)
        delegate?.cancel(id)
    }
}
