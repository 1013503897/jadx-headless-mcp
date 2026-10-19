package com.atxx.jhmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Deterministic coverage of the decompile pool's queue/timeout policy via explicit clock values —
 * the retire decision that decompileTimed's wait loop runs, without a real APK or real timing.
 */
class DecompilePolicyTest {

    private val timeout = 90_000L
    private val maxQueue = 30_000L

    private fun decide(now: Long, submitted: Long, execStart: Long) =
        decompileWaitDecision(now, submitted, execStart, timeout, maxQueue)

    @Test
    fun `queued task keeps waiting while under the queue cap`() {
        // Submitted at t=0, not started (execStart=0), 10s elapsed: still within the 30s queue cap.
        assertEquals(WaitDecision.Wait(20_000L), decide(now = 10_000, submitted = 0, execStart = 0))
    }

    @Test
    fun `queued task retires once the queue cap is exhausted`() {
        assertEquals(WaitDecision.Retire, decide(now = 30_000, submitted = 0, execStart = 0))
        assertEquals(WaitDecision.Retire, decide(now = 45_000, submitted = 0, execStart = 0))
    }

    @Test
    fun `a long queue wait does NOT retire a task that only just started running`() {
        // The bug this fixes: the task sat queued 28s behind a sibling, then started at t=28s.
        // At t=29s it has executed only 1s — it must keep waiting, NOT be retired for the queue time.
        val d = decide(now = 29_000, submitted = 0, execStart = 28_000)
        assertEquals(WaitDecision.Wait(timeout - 1_000L), d)
    }

    @Test
    fun `running task is charged execution time, not wall-clock since submit`() {
        // Started at t=5s, now t=50s → executed 45s of its 90s budget → keep waiting ~45s more.
        assertEquals(WaitDecision.Wait(45_000L), decide(now = 50_000, submitted = 0, execStart = 5_000))
    }

    @Test
    fun `running task retires only when it overruns its own execution budget`() {
        // Executed exactly timeout → retire; executed past timeout → retire.
        assertEquals(WaitDecision.Retire, decide(now = 95_000, submitted = 0, execStart = 5_000))
        assertEquals(WaitDecision.Retire, decide(now = 200_000, submitted = 0, execStart = 5_000))
    }

    @Test
    fun `execution budget is independent of a short queue wait`() {
        // Barely queued (started at t=1ms) but running long (now t=91s, executed ~91s) → retire.
        assertEquals(WaitDecision.Retire, decide(now = 91_000, submitted = 0, execStart = 1))
    }
}
