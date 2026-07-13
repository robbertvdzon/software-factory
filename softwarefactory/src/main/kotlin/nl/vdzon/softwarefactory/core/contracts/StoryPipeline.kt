package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.AgentComments
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.*

import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue

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
