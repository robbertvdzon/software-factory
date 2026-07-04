package nl.vdzon.softwarefactory.bridge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeError
import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Vertaalt een binnengekomen [BridgeRequest] naar een aanroep op de bestaande
 * [FactoryDashboardService] (zie docs/ontwerp-bridge-dashboard.md §5, operatie-catalogus) en
 * verpakt het resultaat weer in een [BridgeResponse]. Uitsluitend vertalen en delegeren — géén
 * nieuwe businesslogica hier.
 */
@Component
class BridgeRequestHandler(
    private val dashboardService: FactoryDashboardService,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(request: BridgeRequest): BridgeResponse =
        try {
            val body = dispatch(request.operation)
            BridgeResponse(id = request.id, ok = true, body = objectMapper.valueToTree(body))
        } catch (unknown: UnknownOperationException) {
            BridgeResponse(
                id = request.id,
                ok = false,
                error = BridgeError(code = "UNKNOWN_OPERATION", message = unknown.message.orEmpty()),
            )
        } catch (exception: Exception) {
            logger.warn("Bridge-operatie '{}' faalde: {}", request.operation, exception.message)
            BridgeResponse(
                id = request.id,
                ok = false,
                error = BridgeError(code = "INTERNAL_ERROR", message = exception.message ?: "Onbekende fout"),
            )
        }

    private fun dispatch(operation: String): Any =
        when (operation) {
            "stories.list" -> dashboardService.stories()
            "myActions.count" -> MyActionsCountBody(dashboardService.myActionsCount())
            else -> throw UnknownOperationException("Onbekende operatie: $operation")
        }

    private data class MyActionsCountBody(val count: Int)

    private class UnknownOperationException(message: String) : Exception(message)
}
