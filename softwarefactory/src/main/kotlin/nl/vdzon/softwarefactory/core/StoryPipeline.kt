package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.TrackerIssue

/**
 * Publiek contract van de pipeline-module: gegeven één issue, bepaal en voer de volgende stap uit
 * (dispatch / recovery / keten-doorzetten / auto-approve / promote) en geef de uitkomst.
 *
 * Dit is de enige naar-buiten zichtbare API van de module (root-package). De implementatie
 * ([nl.vdzon.softwarefactory.pipeline.service.StoryPipelineService]) leeft intern in `service`.
 */
interface StoryPipeline {
    fun process(issue: TrackerIssue): IssueProcessResult
}
