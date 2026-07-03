package nl.vdzon.softwarefactory.core

/**
 * Soort menselijke actie waar een issue op wacht, los van presentatie (kaart, melding, inbox).
 */
enum class HumanGate {
    /** Een agent stelt een vraag aan de gebruiker (`*-with-questions`). */
    QUESTION,

    /** Een fase-gate wacht op goedkeuring; vervalt wanneer auto-approve actief is. */
    APPROVAL,

    /** Een handmatige stap of de manual-approve-poort; wacht ALTIJD op een mens (SF-192). */
    MANUAL,
}

/**
 * Eén bron van waarheid voor "wacht dit issue op een mens?" en "geldt auto-approve?".
 *
 * Dashboard-inbox, actiekaarten en Telegram-meldingen namen deze beslissing voorheen elk met een
 * eigen, handmatig gesynchroniseerde kopie — dat gaf aantoonbaar bugs (SF-164/SF-170: subtaak las
 * z'n eigen auto-approve-veld i.p.v. dat van de parent-story). Consumenten vertalen de uitkomst
 * hier alleen nog naar hun eigen presentatie.
 */
object HumanActionPolicy {

    /**
     * Pure classificatie (geen IO): op welk soort menselijke actie wacht dit issue volgens z'n
     * fase, of `null` als de fase geen wachtmoment is. Auto-approve is hier bewust géén input:
     * of een [HumanGate.APPROVAL] daadwerkelijk op een mens wacht beslist [awaitsHuman].
     */
    fun gateFor(issue: TrackerIssue): HumanGate? = when (issue.issueType) {
        IssueType.STORY -> when (StoryPhase.fromTracker(issue.fields.storyPhase)) {
            StoryPhase.REFINED_WITH_QUESTIONS,
            StoryPhase.PLANNED_WITH_QUESTIONS,
            -> HumanGate.QUESTION
            StoryPhase.REFINED,
            StoryPhase.PLANNED,
            -> HumanGate.APPROVAL
            else -> null
        }
        IssueType.SUBTASK -> when (SubtaskPhase.fromTracker(issue.fields.subtaskPhase)) {
            SubtaskPhase.DEVELOPED_WITH_QUESTIONS,
            SubtaskPhase.REVIEWED_WITH_QUESTIONS,
            SubtaskPhase.TESTED_WITH_QUESTIONS,
            SubtaskPhase.SUMMARY_WITH_QUESTIONS,
            -> HumanGate.QUESTION
            SubtaskPhase.AWAITING_HUMAN,
            SubtaskPhase.MANUAL_APPROVE_NEEDED,
            -> HumanGate.MANUAL
            SubtaskPhase.REVIEWED,
            SubtaskPhase.TESTED,
            SubtaskPhase.SUMMARIZED,
            -> HumanGate.APPROVAL
            // Alleen een development-subtaak wacht op beoordeling van 'developed';
            // review/test/summary-subtaken advancen daar vanzelf doorheen.
            SubtaskPhase.DEVELOPED ->
                if (issue.fields.subtaskType.equals(SubtaskType.DEVELOPMENT.trackerValue, ignoreCase = true)) {
                    HumanGate.APPROVAL
                } else {
                    null
                }
            else -> null
        }
    }

    /**
     * Wacht dit issue op een mens? Een issue in error blokkeert de story en telt ook als
     * wachtend; een [HumanGate.APPROVAL] vervalt wanneer auto-approve actief is.
     */
    fun awaitsHuman(issue: TrackerIssue, autoApproveActive: Boolean): Boolean {
        if (!issue.fields.error.isNullOrBlank()) return true
        return when (gateFor(issue)) {
            HumanGate.QUESTION, HumanGate.MANUAL -> true
            HumanGate.APPROVAL -> !autoApproveActive
            null -> false
        }
    }

    /**
     * SF-335 — silent impliceert auto-approve. De vlag staat op de PARENT-story; voor subtaken
     * dus via [parentFieldsOf] resolven (SF-170: het eigen veld alleen lezen is fout). De lookup
     * is best-effort: lever `null` bij ontbreken/falen, dan geldt auto-approve niet.
     */
    fun autoApproveActive(issue: TrackerIssue, parentFieldsOf: (subtaskKey: String) -> TrackerIssueFields?): Boolean {
        if (issue.fields.autoApprove || issue.fields.silent) return true
        if (issue.issueType != IssueType.SUBTASK) return false
        val parent = parentFieldsOf(issue.key) ?: return false
        return parent.autoApprove || parent.silent
    }
}
