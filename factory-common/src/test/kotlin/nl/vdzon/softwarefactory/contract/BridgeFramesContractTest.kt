package nl.vdzon.softwarefactory.contract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract-test voor het bridge-frame-formaat ([BridgeHello]/[BridgeRequest]/[BridgeResponse]/
 * [BridgeEvent]), zelfde recept als [nl.vdzon.softwarefactory.contract.AgentResultFileContractTest]:
 * round-trip + letterlijke golden-JSON-fixtures (ook gebruikt door de Dart-tests van de
 * Flutter-app, zie `factory-common/src/test/resources/bridge-fixtures/`) + defaults + onbekende
 * velden genegeerd.
 */
class BridgeFramesContractTest {

    private val objectMapper = jacksonObjectMapper()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/bridge-fixtures/$name")) { "Fixture ontbreekt: $name" }
            .readText()

    @Test
    fun `hello round-trip en golden fixture`() {
        val original = BridgeHello(token = "shared-secret-token", protocolVersion = 1, factoryVersion = "abc1234")

        val json = objectMapper.writeValueAsString(original)
        assertEquals(original, objectMapper.readValue<BridgeHello>(json))

        val parsed = objectMapper.readValue<BridgeHello>(fixture("hello.json"))
        assertEquals(original, parsed)
    }

    @Test
    fun `request round-trip en golden fixture`() {
        val original = BridgeRequest(id = "r-123", operation = "stories.list", params = objectMapper.createObjectNode())

        val json = objectMapper.writeValueAsString(original)
        assertEquals(original, objectMapper.readValue<BridgeRequest>(json))

        val parsed = objectMapper.readValue<BridgeRequest>(fixture("request.json"))
        assertEquals("r-123", parsed.id)
        assertEquals("stories.list", parsed.operation)
        assertEquals(0, parsed.params?.size())
    }

    @Test
    fun `ok-response round-trip en golden fixture`() {
        val body = objectMapper.createObjectNode().set<com.fasterxml.jackson.databind.JsonNode>(
            "stories",
            objectMapper.createArrayNode(),
        )
        val original = BridgeResponse(id = "r-123", ok = true, body = body)

        val json = objectMapper.writeValueAsString(original)
        assertEquals(original, objectMapper.readValue<BridgeResponse>(json))

        val parsed = objectMapper.readValue<BridgeResponse>(fixture("response-ok.json"))
        assertEquals("r-123", parsed.id)
        assertEquals(true, parsed.ok)
        assertNull(parsed.error)
        assertEquals(0, parsed.body?.path("stories")?.size())
    }

    @Test
    fun `error-response round-trip en golden fixture`() {
        val original = BridgeResponse(
            id = "r-123",
            ok = false,
            error = BridgeError(code = "STORY_NOT_FOUND", message = "Story SF-999 bestaat niet."),
        )

        val json = objectMapper.writeValueAsString(original)
        assertEquals(original.id, objectMapper.readValue<BridgeResponse>(json).id)
        assertEquals(original.error, objectMapper.readValue<BridgeResponse>(json).error)

        // Losse veld-asserts i.p.v. object-equals: een afwezig "body"-veld deserialiseert naar
        // Jackson's NullNode i.p.v. Kotlin-null, wat data-class-equals (terecht) laat falen.
        val parsed = objectMapper.readValue<BridgeResponse>(fixture("response-error.json"))
        assertEquals("r-123", parsed.id)
        assertEquals(false, parsed.ok)
        assertEquals(BridgeError(code = "STORY_NOT_FOUND", message = "Story SF-999 bestaat niet."), parsed.error)
    }

    @Test
    fun `event zonder body round-trip en golden fixture`() {
        val original = BridgeEvent(event = "changed")

        val json = objectMapper.writeValueAsString(original)
        assertEquals(original.event, objectMapper.readValue<BridgeEvent>(json).event)

        val parsed = objectMapper.readValue<BridgeEvent>(fixture("event-changed.json"))
        assertEquals("changed", parsed.event)
    }

    @Test
    fun `event met body round-trip en golden fixture`() {
        val parsed = objectMapper.readValue<BridgeEvent>(fixture("event-my-actions-count.json"))
        assertEquals("myActionsCount", parsed.event)
        assertEquals(3, parsed.body?.path("count")?.asInt())
    }

    @Test
    fun `minimale request valt terug op defaults`() {
        val minimal = """{"id":"r-1","operation":"status.get"}"""
        val parsed = objectMapper.readValue<BridgeRequest>(minimal)

        assertEquals("request", parsed.type)
        assertNull(parsed.params)
    }

    @Test
    fun `onbekende velden worden genegeerd`() {
        val json = """
            {
              "type": "event",
              "event": "changed",
              "someFutureField": "ignored"
            }
        """.trimIndent()

        val parsed = objectMapper.readValue<BridgeEvent>(json)

        assertEquals("changed", parsed.event)
    }

    @Test
    fun `typeOf herkent elk frame-type zonder het volledig te parsen`() {
        assertEquals("hello", BridgeFrameReader.typeOf(fixture("hello.json")))
        assertEquals("request", BridgeFrameReader.typeOf(fixture("request.json")))
        assertEquals("response", BridgeFrameReader.typeOf(fixture("response-ok.json")))
        assertEquals("event", BridgeFrameReader.typeOf(fixture("event-changed.json")))
        assertNull(BridgeFrameReader.typeOf("not json"))
    }
}
