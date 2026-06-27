package nl.vdzon.softwarefactory.nightly

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Tijdrekenen voor de nachtelijke scheduler. De factory-klok draait in UTC, maar de gebruiker stelt
 * de start-/summary-tijd in lokale NL-tijd (`Europe/Amsterdam`) in. Deze utility rekent DST-correct
 * tussen beide en is volledig deterministisch testbaar via een injecteerbare [Clock].
 *
 * `run_date` is een kalenderdatum in NL-tijd: rond middernacht NL hoort een tick bij de NL-dag, niet
 * bij de UTC-dag. Daarom leiden we de "vandaag"-datum altijd af in de Amsterdam-zone.
 */
@Component
class NightlyTime(private val clock: Clock = Clock.systemUTC()) {

    /** Huidig moment (UTC-instant). */
    fun now(): Instant = clock.instant()

    /** De huidige kalenderdatum in NL-tijd — de `run_date` waarop één run per dag hangt. */
    fun nlToday(): LocalDate = LocalDate.ofInstant(clock.instant(), ZONE)

    /**
     * Is het huidige moment voorbij (of precies op) het opgegeven NL-tijdstip op de gegeven NL-datum?
     * DST-correct: `02:00` op de dag van de voorjaarssprong bestaat niet één-op-één, `java.time` schuift
     * dan naar het eerstvolgende geldige moment.
     */
    fun hasReached(date: LocalDate, nlTime: LocalTime): Boolean =
        !now().isBefore(nlInstant(date, nlTime))

    /** Het UTC-instant dat hoort bij [nlTime] op [date] in de NL-zone. */
    fun nlInstant(date: LocalDate, nlTime: LocalTime): Instant =
        ZonedDateTime.of(date, nlTime, ZONE).toInstant()

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Parset een `HH:MM`-string; gooit bij ongeldige invoer (gevalideerd in de UI-laag). */
        fun parseHhMm(value: String): LocalTime = LocalTime.parse(value.trim(), HHMM)

        /** Formatteert een [LocalTime] terug naar `HH:MM` voor opslag/weergave. */
        fun formatHhMm(time: LocalTime): String = time.format(HHMM)
    }
}
