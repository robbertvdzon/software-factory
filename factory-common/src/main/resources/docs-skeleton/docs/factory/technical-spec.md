# Technical Spec

Beschrijf de technische stack, frameworks, versies, architectuurafspraken,
codeconventies en bekende valkuilen.

Documenteer hier ook de commands uit `.factory/verification.yaml`. Na een tester-AI-run voert de
agentworker deze zelf uit en schrijft additive revisiongebonden evidence in `AgentResultFile`; de
factory valideert config, commandset, exitcodes en HEAD/worktree-tree onafhankelijk en fail-closed.
Timeout stopt parent en child-processen; een output-readerfout is nooit groen. Duration moet exact
met start/eind overeenkomen en samenvatting/rapportlocatie zijn begrensd.
