# KAN-007 - Tester En Preview Deploy

Story:
Als tester-agent wil ik een PR-preview kunnen vinden, afwachten en testen met
browser/cluster tooling, zodat de factory bugs kan terugkoppelen voordat een
mens merged.

Subtaken:
[ ]: Preview URL/namespace templating
[ ]: Tester wacht op HTTP 200 preview
[ ]: Kubeconfig mount + `preview_db_secret_recipe`
[ ]: Tester dummy/eerste flow met `SF_PREVIEW_URL`, `SF_PREVIEW_NAMESPACE`, `SF_PREVIEW_DB_URL`
[ ]: Preview cleanup bij merge/delete/re-implement

Stappen:
[ ]: render preview URL and namespace from deployment frontmatter
[ ]: pass preview values as `SF_` env-vars
[ ]: poll preview URL until HTTP 200 or timeout
[ ]: mount kubeconfig read-only for tester containers
[ ]: execute optional DB secret recipe
[ ]: run first tester flow and post feedback
[ ]: clean preview namespace idempotently
[ ]: add HTTP and fake cluster tests

Done / rationale:
- Nog niet geimplementeerd.
