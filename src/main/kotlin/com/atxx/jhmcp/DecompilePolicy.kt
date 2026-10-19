package com.atxx.jhmcp

/**
 * The timing policy for [JadxSession.decompileTimed]'s wait loop, factored out as a pure function so
 * the concurrency-sensitive "who retires the pool" decision can be unit-tested against an explicit
 * clock instead of racing a real decompile (which would be flaky).
 */
internal sealed interface WaitDecision {
    /** Block on the worker for up to [budgetMs] more, then re-evaluate. */
    data class Wait(val budgetMs: Long) : WaitDecision

    /** Give up: cancel the task, retire the (wedged) pool, and fail with a timeout. */
    data object Retire : WaitDecision
}

/**
 * Decide, from the current clock and a task's timestamps, whether to keep waiting on the shared
 * decompile worker (and for how long) or to retire the pool.
 *
 * The invariant that fixes the HTTP-concurrency bug: a task is charged only its own time.
 *  - Before it starts running ([execStartMs] == 0) it accrues QUEUE time, capped at [maxQueueWaitMs];
 *    it is never retired merely for waiting its turn behind a sibling's slow-but-healthy decompile.
 *  - Once running it accrues EXECUTION time against [timeoutMs] and is retired only if it overruns
 *    its own budget.
 *
 * Returning [WaitDecision.Wait] always carries a strictly positive budget.
 */
internal fun decompileWaitDecision(
    nowMs: Long,
    submittedAtMs: Long,
    execStartMs: Long,
    timeoutMs: Long,
    maxQueueWaitMs: Long,
): WaitDecision {
    val remaining = if (execStartMs == 0L) {
        maxQueueWaitMs - (nowMs - submittedAtMs) // queue budget
    } else {
        timeoutMs - (nowMs - execStartMs)        // execution budget
    }
    return if (remaining <= 0L) WaitDecision.Retire else WaitDecision.Wait(remaining)
}
