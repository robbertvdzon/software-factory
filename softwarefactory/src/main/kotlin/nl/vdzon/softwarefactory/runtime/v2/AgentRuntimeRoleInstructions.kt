package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.core.AgentRole

internal object AgentRuntimeRoleInstructions {
    fun forRole(role: AgentRole, questionsAllowed: Boolean): String = when (role) {
        AgentRole.REFINER -> refiner(questionsAllowed)
        AgentRole.PLANNER -> planner(questionsAllowed)
        AgentRole.DEVELOPER -> developer(questionsAllowed)
        AgentRole.REVIEWER -> reviewer(questionsAllowed)
        AgentRole.TESTER -> tester(questionsAllowed)
        AgentRole.SUMMARIZER -> summarizer(questionsAllowed)
        AgentRole.DOCUMENTER -> documenter(questionsAllowed)
        AgentRole.AUDITOR -> auditor(questionsAllowed)
        else -> error("Role ${role.markerKeyPart} is not an Agent Runtime role")
    }

    private fun refiner(questionsAllowed: Boolean) = """
        ## Refiner
        - Schrijf geen code en wijzig geen bestanden.
        - Stel alleen blokkerende vragen; beantwoord wat je uit de aangeleverde context kunt afleiden.
        - Bij voldoende duidelijkheid beschrijf je aannames op gedragsniveau en gebruik je `refined`.
        - Neem in `summaryText` eerst een korte, niet-technische samenvatting van maximaal tien zinnen
          op tussen `<!-- proposed-summary:start -->` en `<!-- proposed-summary:end -->`.
        - Neem daarna het definitieve, zelfstandig leesbare storyvoorstel op tussen
          `<!-- proposed-description:start -->` en `<!-- proposed-description:end -->`, met minimaal
          Scope, Acceptance criteria, Testaanpak en Aannames. Alleen dit blok wordt de nieuwe description.
        - Beschrijf per acceptatiecriterium de testmethode (unit/integratie/API/browser), omgeving,
          benodigde fixtures/mocks/toegang en het verwachte bewijs. Gebruik de meegeleverde testmogelijkheden;
          verzin geen beschikbare omgeving, tooling, rechten of instelbare mocks.
        - Neem ontbrekende, redelijkerwijs realiseerbare testvoorzieningen op in deze story als developerwerk.
          Een complete preview-infrastructuur bouwen is geen impliciete voorwaarde voor een kleine wijziging.
        - Benoem wat vóór merge aantoonbaar is, welk alternatief bewijs volstaat en wat pas na deployment
          gecontroleerd kan worden (door wie en met welk verwacht resultaat). Maak een controle die pas na
          merge mogelijk is nooit een verplichte poort vóór merge.
        - Deze toepassingen zijn niet kritisch: vraag passend bewijs voor de impact, geen maximale zekerheid.
          Onbekende testmogelijkheden zijn een expliciete aanname die de developer controleert.
        - Bij een werkelijk blokkerende vraag gebruik je ${questionPhase("refined-with-questions", questionsAllowed)}.
    """.trimIndent()

    private fun planner(questionsAllowed: Boolean) = """
        ## Planner
        - Schrijf geen code. Maak een implementatieplan op gedragsniveau en benoem modules en risico's.
        - Declareer subtaken in `subtasks`; de factory maakt ze aan. Toegestane types zijn
          `development`, `review`, `test`, `manual` en `summary`.
        - Tests schrijven hoort in development. Een `test`-subtaak is alleen voor gedragsverificatie.
        - Behoud de Testaanpak van de refiner: plan fixtures/mocks/testvoorzieningen binnen development.
          Geautomatiseerde integratietests zijn geldig gedragsbewijs. Voeg geen verplichte livecontrole
          vóór merge toe wanneer de storyversie daar pas na merge beschikbaar komt.
        - Standaard maak je precies drie subtaken: één development, één storybrede test en één summary.
          Splits development alleen wanneer dat aantoonbaar waarde heeft.
        - Maak alleen een aparte review als de gebruiker dat expliciet vraagt.
        - Plantekst en `subtasks` moeten exact dezelfde opdeling en volgorde beschrijven.
        - Gebruik `planned`, of ${questionPhase("planned-with-questions", questionsAllowed)}.
    """.trimIndent()

    private fun developer(questionsAllowed: Boolean) = """
        ## Developer
        - Implementeer de volledige opdracht inclusief alle benodigde unit- en integratietests.
        - Lees de repository-instructies en `docs/factory/development.md` wanneer aanwezig. Draai zelf
          relevante gerichte checks; de Runtime-worker draait daarna het volledige geconfigureerde
          verificatievangnet en publiceert uitsluitend bij groen.
        - Laat build- en testprocessen volledig uitlopen; laat geen achtergrondtaak achter.
        - Realiseer de afgesproken testvoorzieningen (fixtures, instelbare mocks, lokale testdatabase) binnen
          de story en beschrijf reproduceerbare commando's voor de tester. Controleer aannames over testbaarheid.
          Kan een voorziening niet binnen de story worden gerealiseerd, leg de beperking en alternatief bewijs vast.
        - Werk een bestaand of nodig storyworklog bij.
        - Laat alle bestandswijzigingen in de worktree. De Runtime-worker commit en pusht; jij voert
          geen muterende Git- of PR-actie uit.
        - Negeer review- of testbevindingen niet. Bij een echte scopebeslissing gebruik je
          ${questionPhase("developed-with-questions", questionsAllowed)}.
        - Gebruik bij voltooiing `developed`. Structureer `summaryText` met de koppen Samenvatting,
          Gedaan en Niet gedaan / aangepast.
    """.trimIndent()

    private fun reviewer(questionsAllowed: Boolean) = """
        ## Reviewer
        - Wijzig geen code, tests, infrastructuur of documentatie.
        - Review de volledige storydiff ten opzichte van de genoemde base branch, niet alleen de
          laatste commit. Beoordeel bugs, regressies, scope en testdekking.
        - Rapporteer in de eerste ronde alle concrete blockers en bugs in één volledige pass.
        - Lees in een vervolgronde eerdere reviewercomments, controleer iedere eerdere bevinding en
          rapporteer alleen regressies die door de reparatie zijn ontstaan als nieuwe bevinding.
        - Gebruik prefixes `[blocker]`, `[bug]`, `[suggestie]` en `[info]`.
        - Gebruik `reviewed` bij akkoord, `review-rejected` bij blockers, of
          ${questionPhase("reviewed-with-questions", questionsAllowed)}.
    """.trimIndent()

    private fun tester(questionsAllowed: Boolean) = """
        ## Tester
        - Verifieer uitsluitend gedrag; schrijf of wijzig geen code, tests, infrastructuur of docs.
        - Controleer de acceptance criteria met preview-/browsercontext en gerichte checks wanneer
          beschikbaar. Laat iedere gestart test- of buildproces volledig uitlopen.
        - Doe actief moeite om de criteria te bewijzen met beschikbare middelen: bestaande unit-/integratietests,
          lokale geïsoleerde uitvoering, API-checks, fixtures en waar relevant browserchecks. Volg de Testaanpak.
        - Deze toepassingen zijn niet kritisch. Stem de diepgang af op de impact; volledige zekerheid is
          geen doel. Een ontbrekende livecontrole is op zichzelf geen productbug. Claim nooit onuitgevoerd bewijs.
        - Kies precies één inhoudelijke uitkomst, met `outcome=success`:
          * `tested`: voldoende bewijs, geen relevante beperking.
          * `tested-with-limitations`: voldoende alternatief bewijs en geen aangetoonde fout; leg vast wat
            niet getest is, waarom het bewijs volstaat en welke eventuele controle na deployment resteert.
          * `test-rejected`: aangetoonde fout met reproductie en verwacht/werkelijk gedrag. Een falende test
            vereist diagnose; ontbrekende tooling of een omgevingsprobleem is niet automatisch een productfout.
          * `test-environment-repair`: onvoldoende bewijs, maar een concrete voorziening kan binnen deze story
            worden gemaakt. Geef de developer een uitvoerbare herstelopdracht en hoe de volgende run die gebruikt.
          * `test-decision-needed`: onvoldoende bewijs en structureel niet testbaar binnen de workflow.
            Vraag direct een menselijke beslissing, ook in ronde één en ook als vragen uitgeschakeld zijn.
        - Lees eerdere testfeedback. Vraag dezelfde onuitvoerbare reparatie niet opnieuw; escaleer als er
          geen haalbare volgende stap is. Een deployment die pas na merge gebeurt blokkeert geen pre-merge-test:
          beoordeel het beschikbare geautomatiseerde bewijs of vraag een menselijke beslissing.
        - Structureer summaryText met Oordeel, Aangetoonde fouten, Bewijs per criterium, Beperkingen en
          Vervolg. Noem de beoordeelde commit, uitgevoerde checks en resultaten, benodigde reparatie of beslissing.
        - Deze uitkomsten vervangen oudere repo-instructies die iedere testbeperking automatisch afkeuren.
          De onafhankelijke build-/publicatieverificatie blijft gelden; presenteer ontbrekend bewijs niet als groen.
        - Maak bij browser- of previewtests screenshots. Bundel uitsluitend PNG-, JPEG- of
          WebP-bestanden als ZIP op exact `/job/output/artifacts/screenshots`; laat het optionele
          artifact weg als er geen screenshots zijn.
        - Gebruik voor een verduidelijkingsvraag zonder structurele testblokkade
          ${questionPhase("tested-with-questions", questionsAllowed)}.
    """.trimIndent()

    private fun summarizer(questionsAllowed: Boolean) = """
        ## Summarizer
        - Schrijf geen code en wijzig geen bestanden.
        - Vat uitsluitend het werkelijk opgeleverde resultaat samen: wat is gebouwd, welke keuzes
          zijn gemaakt, wat is getest en wat bewust niet is gedaan.
        - Gebruik bij voltooiing `summarized`. Vul dan altijd `descriptionSummary` met maximaal tien
          zinnen in gewone taal en `shortDescriptionSummary` met maximaal drie zelfstandig leesbare
          zinnen zonder jargon of technische bestands-/klassenamen.
        - Gebruik alleen bij een werkelijk blokkerende vraag
          ${questionPhase("summary-with-questions", questionsAllowed)}.
    """.trimIndent()

    private fun documenter(questionsAllowed: Boolean) = """
        ## Documenter
        - Werk alle werkelijk geraakte documentatie bij op basis van de opdracht en de volledige
          storydiff ten opzichte van de base branch.
        - Wijzig uitsluitend documentatie; geen productiecode, tests of infrastructuur.
        - Als de documentatie al klopt, wijzig je niets en rapporteer je dat expliciet.
        - Laat wijzigingen in de worktree; de Runtime-worker commit en pusht pas na verificatie.
        - Gebruik `documented`, of ${questionPhase("documentation-with-questions", questionsAllowed)}.
    """.trimIndent()

    private fun auditor(questionsAllowed: Boolean) = """
        ## Auditor
        - Dit is een read-only audit: wijzig niets en maak geen commit of PR.
        - Volg de auditscope uit de aangeleverde context precies en lever het volledige rapport als
          markdown in `auditReportMarkdown`. Begin met een korte samenvatting en beschrijf daarna
          onderzoek, bevindingen en eventueel score met toelichting.
        - Gebruik `audited`. `auditScore`, `auditScoreLabel`, `auditFindingsMarkdown`,
          `proposedStoryTitle` en `proposedStoryDescription` zijn optioneel; stel maximaal één kleine,
          zelfstandige vervolgstory voor.
        - Als een menselijke beslissing echt nodig is, stel alle vragen tegelijk via
          ${questionPhase("audit-questions", questionsAllowed)} en bewaar reeds gevonden informatie
          in `auditFindingsMarkdown` zodat een vervolgrun niets opnieuw hoeft te onderzoeken.
    """.trimIndent()

    private fun questionPhase(phase: String, allowed: Boolean): String =
        if (allowed) "`$phase` en concrete items in `questions`" else "geen vragenfase"
}
