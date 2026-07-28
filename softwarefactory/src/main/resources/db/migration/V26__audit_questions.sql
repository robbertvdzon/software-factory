-- Een auditor kon tot nu toe niets vragen: hij is niet interactief en er is geen vervolgstap zoals
-- een story die heeft (geen tracker-issue, geen fase-machine, geen "-questions-answered"-ronde).
-- Bij onduidelijkheid moest hij het dus in het rapport zetten en doorgaan op een aanname.
--
-- Deze tabel maakt de vraag-en-antwoordlus mogelijk zonder dat er iets blijft hangen:
--
--   * De vraag leeft LOS van audit_run/audit_run_job. Dat is bewust: AuditPlanner beëindigt een run
--     pas als álle jobs terminaal zijn, en maakt alleen een nieuwe run aan als er geen run loopt.
--     Zou een wachtende job niet-terminaal blijven, dan bleef de run eeuwig open en kwamen ALLE
--     audits van ALLE projecten daarna stil te liggen. De job eindigt daarom terminaal (status
--     'asked') en de vraag blijft hier staan.
--   * `findings` bewaart wat run 1 al uitgezocht had, zodat run 2 na het antwoord alleen nog het
--     rapport hoeft te schrijven i.p.v. het onderzoek over te doen. Bewust in de database en niet in
--     het workspace-bestand: WorkCleanupPoller ruimt workspaces op tussen twee runs door.
--   * `consumed_at` voorkomt dat een beantwoorde vraag eeuwig in elke volgende prompt blijft plakken.
--
-- Sleutel is (project, audit_type), net als bij audit_report — een vraag hoort bij een audit, niet
-- bij de toevallige run waarin hij gesteld werd.
CREATE TABLE IF NOT EXISTS ${schema}.audit_question (
    id          BIGSERIAL    PRIMARY KEY,
    project     TEXT         NOT NULL,
    audit_type  TEXT         NOT NULL,
    question    TEXT         NOT NULL,
    findings    TEXT,
    asked_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    answer      TEXT,
    answered_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ
);

-- Twee leesroutes: "welke audits wachten op mij" (openstaand, answer IS NULL) en "wat moet er in de
-- prompt van de volgende run van déze audit" (nieuwste eerst per project+type).
CREATE INDEX IF NOT EXISTS audit_question_project_type_asked_idx
    ON ${schema}.audit_question (project, audit_type, asked_at DESC);

CREATE INDEX IF NOT EXISTS audit_question_open_idx
    ON ${schema}.audit_question (asked_at DESC)
    WHERE answer IS NULL;
