package nl.vdzon.softwarefactory.roadmap.types

enum class EpicStatus(val wireValue: String) {
    PLANNED("planned"),
    IN_PROGRESS("in_progress"),
    DONE("done");

    companion object {
        fun fromWire(value: String): EpicStatus = entries.firstOrNull { it.wireValue == value.trim().lowercase() }
            ?: throw IllegalArgumentException("Onbekende epic-status: $value")
    }
}
