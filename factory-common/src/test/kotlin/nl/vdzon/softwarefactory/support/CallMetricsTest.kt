package nl.vdzon.softwarefactory.support

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CallMetricsTest {
    @Test
    fun `measures calls and builds a per-category report`() {
        CallMetrics.begin()
        val a = CallMetrics.measure("youtrack", "GET /api/issues") { 1 }
        val b = CallMetrics.measure("docker", "docker inspect") { 2 }
        val calls = CallMetrics.end()

        assertEquals(1, a)
        assertEquals(2, b)
        assertEquals(2, calls.size)

        val report = CallMetrics.report(calls)
        assertContains(report, "2 calls")
        assertContains(report, "youtrack")
        assertContains(report, "docker")
    }

    @Test
    fun `measure runs the call but records nothing without an active session`() {
        val result = CallMetrics.measure("youtrack", "GET /x") { 7 }

        assertEquals(7, result)
        assertEquals("0 calls", CallMetrics.report(CallMetrics.end()))
    }
}
