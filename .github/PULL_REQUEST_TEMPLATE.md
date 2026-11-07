## Description

<!-- Briefly describe what this PR does and why -->

## Related Issue

`[EOP-NNN]` — the Jira issue key (`EOP` is the project key, not a word)

## Type of Change

- [ ] feat: new feature
- [ ] fix: bug fix
- [ ] refactor: code restructuring
- [ ] test: test addition or correction
- [ ] docs: documentation only
- [ ] chore: build/config/tooling

## Definition of Done

- [ ] Code follows Hexagonal Architecture (dependencies point inward)
- [ ] Domain layer has zero framework imports
- [ ] Unit tests pass and cover new logic (sub-second execution)
- [ ] API contract updated in `docs/api/openapi.yml` if applicable
- [ ] Error responses follow RFC 9457 Problem Details
- [ ] No secrets, PII, or full request bodies logged
- [ ] `mvn verify` passes locally (checkstyle, spotbugs, jacoco, enforcer)
- [ ] `docs/` updated (ADR, architecture diagrams, README) if behaviour changed
- [ ] Feature flagged if not ready for end users
