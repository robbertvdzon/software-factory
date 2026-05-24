# KAN-007 - Tester En Preview Deploy

Story:
Als tester-agent wil ik een PR-preview kunnen vinden, afwachten en testen met
browser/cluster tooling, zodat de factory bugs kan terugkoppelen voordat een
mens merged.

Subtaken:
[x]: Preview URL/namespace templating
[x]: Tester wacht op HTTP 200 preview
[x]: Kubeconfig mount + `preview_db_secret_recipe`
[x]: Tester dummy/eerste flow met `SF_PREVIEW_URL`, `SF_PREVIEW_NAMESPACE`, `SF_PREVIEW_DB_URL`
[x]: Preview cleanup bij merge; command-cleanup haakt in KAN-009 in op dezelfde cleaner

Stappen:
[x]: render preview URL and namespace from deployment frontmatter
[x]: pass preview values as `SF_` env-vars
[x]: poll preview URL until HTTP 200 or timeout
[x]: mount kubeconfig read-only for tester containers
[x]: execute optional DB secret recipe
[x]: run first tester flow and post feedback
[x]: clean preview namespace idempotently
[x]: add HTTP and fake cluster tests

Done / rationale:
- Start KAN-007: specs voor tester preview-flow, deployment-frontmatter en cleanup zijn gelezen; implementatie richt zich op preview env-vars, tester wait/DB-recipe en idempotente namespace cleanup.
- Developer-completion bewaart nu PR- en deploymentmetadata in `story_runs` via migratie V3, zodat latere reviewer/tester runs dezelfde PR en preview-context kunnen hergebruiken.
- De orchestrator rendert `preview_url_template` en `preview_namespace_template` met het PR-nummer, geeft `SF_PR_NUMBER`, `SF_PREVIEW_URL` en `SF_PREVIEW_NAMESPACE` door aan agent-containers en ruimt de preview namespace op als een gemonitorde PR gemerged is.
- De tester CLI wacht op HTTP 200, kan `preview_db_secret_recipe` uitvoeren wanneer `SF_PREVIEW_DB_URL` ontbreekt, en voegt de preview-context toe aan de agent-taak.
- `agent-tester` bevat nu naast Playwright en `psql` ook `oc` en `kubectl`, zodat recipes en clusterchecks met de gemounte kubeconfig kunnen draaien.
- `agent-tester:local` is live tegen KAN-69 en de sample repo getest met preview-env-vars; Jira werd bijgewerkt naar `tested-successfully` en kreeg een `[TESTER]` comment.
- Unit-tests dekken template-rendering, HTTP wait, DB recipe, kubeconfig cleanup, Docker preview envs, completion metadata en orchestrator dispatch/merge-cleanup.
