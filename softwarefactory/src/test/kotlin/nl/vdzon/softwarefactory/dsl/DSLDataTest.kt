package nl.vdzon.softwarefactory.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DSLDataTest {

    /**
     * Bouwt de order-flowchart uit het screenshot op in de geneste / declaratieve
     * stijl zoals build.gradle.kts: per node een blok met z'n uitgaande pijlen,
     * verwijzend naar andere nodes op naam.
     */
    private fun buildOrderFlow(): Flowchart = flowchart {
        node("Start") {
            to("User Submits Order")
        }
        node("User Submits Order") {
            to("Check Warehouse Stock")
        }
        node("Check Warehouse Stock") {
            to("Show Error") labeled "No Stock"
            to("Process Payment") labeled "In Stock"
        }
        node("Process Payment") {
            to("Show Error") labeled "Payment Failed"
            to("Dispatch Order") labeled "Payment Success"
        }
        node("Dispatch Order") {
            to("Show Error") labeled "Dispatch Failed"
            to("Show Success") labeled "Dispatch Success"
        }
        node("Show Error") {
            to("Finished")
        }
        node("Show Success") {
            to("Finished")
        }
        node("Finished")
    }

    @Test
    fun `flowchart bevat alle nodes`() {
        val flow = buildOrderFlow()

        assertEquals(8, flow.nodes.size)
        assertEquals(
            listOf(
                "Start",
                "User Submits Order",
                "Check Warehouse Stock",
                "Process Payment",
                "Dispatch Order",
                "Show Error",
                "Show Success",
                "Finished",
            ),
            flow.nodes.map { it.label },
        )
    }

    @Test
    fun `flowchart bevat alle edges`() {
        val flow = buildOrderFlow()

        assertEquals(10, flow.edges.size)
    }

    @Test
    fun `gelabelde edges zijn correct`() {
        val flow = buildOrderFlow()

        val noStock = flow.edges.single { it.label == "No Stock" }
        assertEquals("Check Warehouse Stock", noStock.from.label)
        assertEquals("Show Error", noStock.to.label)

        val paymentSuccess = flow.edges.single { it.label == "Payment Success" }
        assertEquals("Process Payment", paymentSuccess.from.label)
        assertEquals("Dispatch Order", paymentSuccess.to.label)
    }

    @Test
    fun `ongelabelde edges hebben geen label`() {
        val flow = buildOrderFlow()

        val startEdge = flow.edges.single { it.from.label == "Start" }
        assertEquals("User Submits Order", startEdge.to.label)
        assertEquals(null, startEdge.label)
    }

    @Test
    fun `node ids zijn uniek`() {
        val flow = buildOrderFlow()

        val ids = flow.nodes.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertNotNull(flow.nodes.single { it.label == "Show Error" })
    }

    @Test
    fun `onbekende doelnode geeft een duidelijke fout`() {
        val error = assertFailsWith<IllegalStateException> {
            flowchart {
                node("Start") {
                    to("Bestaat Niet")
                }
            }
        }
        assertTrue(error.message!!.contains("Bestaat Niet"))
    }
}
