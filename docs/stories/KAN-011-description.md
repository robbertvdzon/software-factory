# KAN-011 - Agent Knowledge En Tips API

Story:
Als agent wil ik tips per target-repo en rol kunnen lezen en opslaan, zodat
herbruikbare kennis tussen runs behouden blijft zonder repo's door elkaar te
halen.

Subtaken:
[x]: `GET /agent-knowledge`
[x]: `POST /agent-knowledge/update`
[x]: target-repo normalisatie
[x]: runner schrijft `.agent-tips.md`
[x]: tests voor isolatie en upsert

Stappen:
[x]: specs §9 en tabeldefinitie nalopen
[x]: repository en service voor `agent_knowledge` maken
[x]: HTTP controller toevoegen voor lezen en upsert
[x]: GitHub SSH/HTTPS URL's normaliseren naar dezelfde bucket
[x]: agent-runner tips laten ophalen en als markdown schrijven
[x]: tips-markdown toevoegen aan agent-context
[x]: unit tests en runner tests toevoegen
[x]: volledige test-suite draaien

Done / rationale:
- Start KAN-011: audit vond dat `agent_knowledge` wel in Flyway bestaat, maar dat de HTTP endpoints, target-repo normalisatie en runner-flow uit specs §9 nog ontbreken.
- `AgentKnowledgeRepository`, `AgentKnowledgeService` en `AgentKnowledgeController` toegevoegd voor `GET /agent-knowledge` en `POST /agent-knowledge/update`.
- Target-repo's worden genormaliseerd zodat SSH en HTTPS GitHub-URL's dezelfde tips-bucket gebruiken, bijvoorbeeld `github.com/robbertvdzon/sample-build-project`.
- De agent-runner haalt bij start tips op via de orchestrator, schrijft ze als markdown naar `.agent-tips.md` in de clone en voegt ze toe aan de agent-context.
- `AgentOutcome` kan knowledge-updates dragen; de runner post die na succesvolle of gefaalde runs naar `/agent-knowledge/update` als een toekomstige echte AI-client tips oplevert.
- Tests toegevoegd voor normalisatie, rol-isolatie, upsert en runner HTTP-fetch/post. Verificatie: `mvn test` groen met 71 tests.
