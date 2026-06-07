# SF-8 — Fase 2 — Refine + plan op Story Phase (met goedkeuringen)

De story-refinement draait volledig op het `Story Phase`-veld via de StoryRefinementCoordinator.

- Twee aparte AI-stappen: refiner en planner, elk met een vragen-loop (mens antwoordt -> AI opnieuw) en een goedkeuringsstap (mens approve/reject).
- Verloop: `refining -> refined(-with-questions) -> refined-approved -> planning -> planned(-with-questions) -> planning-approved` (terminaal).
- Dispatch/completion/recovery zijn phase-veld-bewust; de refiner/planner-agents (agentworker) emitten de Story Phase-waarden.
