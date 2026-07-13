package nl.vdzon.softwarefactory.nightly

import nl.vdzon.softwarefactory.nightly.models.*
import nl.vdzon.softwarefactory.nightly.types.*
import nl.vdzon.softwarefactory.nightly.services.*
import nl.vdzon.softwarefactory.nightly.repositories.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Tests voor de NL↔UTC-conversie van [NightlyTime]. DST-correctheid wordt getoetst met een vaste klok:
 * dezelfde wandklok-tijd in NL valt 's winters op een ander UTC-moment dan 's zomers.
 */
class NightlyTimeTest {

    private fun at(instant: String): NightlyTime =
        NightlyTime(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))

    @Test
    fun `NL-tijd in winter is UTC+1`() {
        // 15 jan 2026: CET (UTC+1) → 02:00 NL = 01:00 UTC.
        val instant = NightlyTime(Clock.systemUTC())
            .nlInstant(LocalDate.of(2026, 1, 15), LocalTime.of(2, 0))
        assertEquals(Instant.parse("2026-01-15T01:00:00Z"), instant)
    }

    @Test
    fun `NL-tijd in zomer is UTC+2`() {
        // 15 jul 2026: CEST (UTC+2) → 02:00 NL = 00:00 UTC.
        val instant = NightlyTime(Clock.systemUTC())
            .nlInstant(LocalDate.of(2026, 7, 15), LocalTime.of(2, 0))
        assertEquals(Instant.parse("2026-07-15T00:00:00Z"), instant)
    }

    @Test
    fun `hasReached is DST-correct rond de winter-starttijd`() {
        val date = LocalDate.of(2026, 1, 15)
        val start = LocalTime.of(2, 0) // = 01:00 UTC in winter
        assertFalse(at("2026-01-15T00:59:59Z").hasReached(date, start))
        assertTrue(at("2026-01-15T01:00:00Z").hasReached(date, start))
    }

    @Test
    fun `hasReached is DST-correct rond de zomer-starttijd`() {
        val date = LocalDate.of(2026, 7, 15)
        val start = LocalTime.of(2, 0) // = 00:00 UTC in zomer
        assertFalse(at("2026-07-14T23:59:59Z").hasReached(date, start))
        assertTrue(at("2026-07-15T00:00:00Z").hasReached(date, start))
    }

    @Test
    fun `nlToday volgt de NL-kalenderdag, niet de UTC-dag`() {
        // 23-30 UTC valt na de zomertijd-overgang al in de volgende NL-dag (CEST = UTC+2 → 01-30 NL).
        assertEquals(LocalDate.of(2026, 3, 30), at("2026-03-29T23:30:00Z").nlToday())
        // Een uur eerder (vóór middernacht NL) is het nog de vorige dag.
        assertEquals(LocalDate.of(2026, 3, 29), at("2026-03-29T21:30:00Z").nlToday())
    }

    @Test
    fun `parseHhMm en formatHhMm zijn elkaars inverse`() {
        assertEquals("02:00", NightlyTime.formatHhMm(NightlyTime.parseHhMm("02:00")))
        assertEquals(LocalTime.of(7, 30), NightlyTime.parseHhMm("07:30"))
    }
}
