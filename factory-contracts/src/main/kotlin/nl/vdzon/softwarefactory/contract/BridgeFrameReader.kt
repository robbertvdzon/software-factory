package nl.vdzon.softwarefactory.contract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Bepaalt het frame-type van een binnengekomen bridge-bericht zonder eerst te weten welke
 * van [BridgeHello]/[BridgeRequest]/[BridgeResponse]/[BridgeEvent] het is. Zowel de factory-kant
 * (`BridgeClient`) als de backend-hub gebruiken dit om te routeren voordat ze het volledige
 * frame parsen.
 */
object BridgeFrameReader {
    private val mapper = jacksonObjectMapper()

    /** Het `type`-veld van [raw], of `null` als het geen geldige JSON-object is met dat veld. */
    fun typeOf(raw: String): String? =
        runCatching { mapper.readTree(raw).path("type").asText(null) }.getOrNull()
}
