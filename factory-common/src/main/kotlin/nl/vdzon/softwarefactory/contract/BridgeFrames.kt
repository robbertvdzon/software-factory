package nl.vdzon.softwarefactory.contract

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/**
 * HET wire-contract van de bridge-websocket tussen de factory (client, uitgaand) en de
 * dashboard-backend (server, "de hub"), zie `docs/ontwerp-bridge-dashboard.md` §5.
 *
 * Drie frame-soorten, allemaal JSON met een `type`-veld dat bepaalt welke van deze
 * data-classes van toepassing is (de lezer bepaalt dat zelf door eerst `type` te lezen —
 * er is bewust geen Jackson-polymorfie-annotatie, dat houdt beide kanten simpel).
 * Evolutie is additief: nieuwe operaties/velden mogen, bestaande veldnamen wijzigen niet,
 * en beide kanten negeren onbekende velden (`@JsonIgnoreProperties(ignoreUnknown = true)`).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BridgeHello(
    val type: String = "hello",
    val token: String,
    val protocolVersion: Int = 1,
    val factoryVersion: String = "",
)

/** backend → factory; [id] is de correlation-id waarop precies één [BridgeResponse] terugkomt. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BridgeRequest(
    val type: String = "request",
    val id: String,
    val operation: String,
    val params: JsonNode? = null,
)

/** factory → backend: antwoord op precies één [BridgeRequest] (zelfde [id]). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BridgeResponse(
    val type: String = "response",
    val id: String,
    val ok: Boolean,
    val body: JsonNode? = null,
    val error: BridgeError? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BridgeError(
    val code: String,
    val message: String,
)

/** factory → backend: push zonder request (de SSE-vervanger richting de frontend). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BridgeEvent(
    val type: String = "event",
    val event: String,
    val body: JsonNode? = null,
)
