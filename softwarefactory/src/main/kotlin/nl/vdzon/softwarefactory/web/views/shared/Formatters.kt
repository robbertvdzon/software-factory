package nl.vdzon.softwarefactory.web.views.shared

import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Datum-/duur-/token-/geld-formattering voor de views. Als class (niet object) omdat
 * [relative] de injecteerbare [Clock] nodig heeft — zo blijven de view-tests deterministisch
 * met een `Clock.fixed`.
 */
internal class Formatters(private val clock: Clock) {

    fun relative(value: OffsetDateTime?): String {
        if (value == null) {
            return "-"
        }
        val duration = Duration.between(value, OffsetDateTime.now(clock)).abs()
        return when {
            duration.toMinutes() < 1 -> "net"
            duration.toHours() < 1 -> "${duration.toMinutes()}m geleden"
            duration.toDays() < 1 -> "${duration.toHours()}u geleden"
            else -> "${duration.toDays()}d geleden"
        }
    }

    fun date(value: OffsetDateTime?): String =
        value?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "-"

    /** Absoluut tijdstip in NL-tijd (de factory-klok draait in UTC), bv. `28-06 11:14`. */
    fun absolute(value: OffsetDateTime?): String =
        value?.atZoneSameInstant(java.time.ZoneId.of("Europe/Amsterdam"))
            ?.format(DateTimeFormatter.ofPattern("dd-MM HH:mm")) ?: "-"

    fun timestamp(value: OffsetDateTime?): String =
        value?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: "-"

    fun tokens(value: Long): String =
        when {
            value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
            value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
            else -> value.toString()
        }

    fun money(value: Double): String =
        String.format(Locale.US, "${'$'}%.4f", value)

    fun duration(value: Long): String {
        if (value <= 0) {
            return "-"
        }
        val seconds = value / 1000
        val minutes = seconds / 60
        val remaining = seconds % 60
        return if (minutes > 0) "${minutes}m ${remaining}s" else "${seconds}s"
    }

    /** Looptijd als minuten:seconden (bv. 1:34), seconden altijd 2-cijferig. */
    fun durationMmSs(value: Long): String {
        val totalSeconds = (value / 1000).coerceAtLeast(0)
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
