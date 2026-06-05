package nl.vdzon.softwarefactory.dsl

import nl.vdzon.softwarefactory.dsl.Action.SHOW_ERROR
import nl.vdzon.softwarefactory.dsl.Action.SHOW_SUCCESS
import nl.vdzon.softwarefactory.dsl.Action.SUBMIT_ORDER
import nl.vdzon.softwarefactory.dsl.Answer.DISPATCH_FAILED
import nl.vdzon.softwarefactory.dsl.Answer.DISPATCH_SUCCESS
import nl.vdzon.softwarefactory.dsl.Answer.IN_STOCK
import nl.vdzon.softwarefactory.dsl.Answer.NO_STOCK
import nl.vdzon.softwarefactory.dsl.Answer.PAYMENT_FAILED
import nl.vdzon.softwarefactory.dsl.Answer.PAYMENT_SUCCESS
import nl.vdzon.softwarefactory.dsl.Condition.CHECK_WAREHOUSE_STOCK
import nl.vdzon.softwarefactory.dsl.Condition.DISPATCH_ORDER
import nl.vdzon.softwarefactory.dsl.Condition.PROCESS_PAYMENT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActivityDiagramDslTest {

    /** Modelleert het PlantUML-voorbeeld in de gestructureerde DSL-stijl. */
    private fun buildOrderDiagram(): ActivityDiagram {
        return activityDiagram {
            start()
            action(SUBMIT_ORDER)
            decision(CHECK_WAREHOUSE_STOCK) {
                then(IN_STOCK) {
                    decision(PROCESS_PAYMENT) {
                        then(PAYMENT_SUCCESS) {
                            decision(DISPATCH_ORDER) {
                                then(DISPATCH_SUCCESS) {
                                    action(SHOW_SUCCESS)
                                }
                                otherwise(DISPATCH_FAILED) {
                                    action(SHOW_ERROR)
                                }
                            }
                        }
                        otherwise(PAYMENT_FAILED) {
                            action(SHOW_ERROR)
                        }
                    }
                }
                otherwise(NO_STOCK) {
                    action(SHOW_ERROR)
                }
            }
            stop()
        }
    }

    @Test
    fun `diagram heeft de juiste top-level structuur`() {
        val diagram = buildOrderDiagram()

        assertEquals(4, diagram.statements.size)
        assertEquals(Start, diagram.statements.first())
        assertEquals(ActionStep(SUBMIT_ORDER), diagram.statements[1])
        assertTrue(diagram.statements[2] is Decision)
        assertEquals(Stop, diagram.statements.last())
    }

    @Test
    fun `geneste decision-takken kloppen`() {
        val diagram = buildOrderDiagram()
        val stock = diagram.statements[2] as Decision

        assertEquals(CHECK_WAREHOUSE_STOCK, stock.condition)
        assertEquals(IN_STOCK, stock.thenAnswer)
        assertEquals(NO_STOCK, stock.elseAnswer)
        assertEquals(listOf(ActionStep(SHOW_ERROR)), stock.elseBranch)

        val payment = stock.thenBranch.single() as Decision
        assertEquals(PAYMENT_SUCCESS, payment.thenAnswer)
        assertEquals(PAYMENT_FAILED, payment.elseAnswer)

        val dispatch = payment.thenBranch.single() as Decision
        assertEquals(listOf(ActionStep(SHOW_SUCCESS)), dispatch.thenBranch)
        assertEquals(listOf(ActionStep(SHOW_ERROR)), dispatch.elseBranch)
    }

    @Test
    fun `toPlantUml genereert het oorspronkelijke diagram terug`() {
        val expected = """
            @startuml

            start
            :User Submits Order;
            if (Check Warehouse Stock?) then (In Stock)
                if (Process Payment) then (Payment Success)
                    if (Dispatch Order) then (Dispatch Success)
                        :Show Success;
                    else (Dispatch Failed)
                        :Show Error;
                    endif
                else (Payment Failed)
                    :Show Error;
                endif
            else (No Stock)
                :Show Error;
            endif
            stop

            @enduml
        """.trimIndent()

        assertEquals(expected, buildOrderDiagram().toPlantUml())
    }

    @Test
    fun `decision zonder otherwise-tak geeft een duidelijke fout`() {
        val error = assertFailsWith<IllegalStateException> {
            activityDiagram {
                start()
                decision(CHECK_WAREHOUSE_STOCK) {
                    then(IN_STOCK) {
                        action(SHOW_SUCCESS)
                    }
                }
                stop()
            }
        }
        assertTrue(error.message!!.contains("otherwise"))
    }
}
