# HuskSync Development Workflow (Strict)

This workflow is mandatory for all future development in this repository. Do not skip steps.

## 1. Requirements & Scope
- Define the goal, success criteria, and non-goals.
- Identify target platform(s), Minecraft version(s), and dependencies.
- List affected modules (`common`, `bukkit`, `fabric`, docs, configs, locales).
- Record external APIs being used and the failure modes if they are missing.

## 2. Design & Decisions
- Write a short design note (1-2 pages) covering:
  - Data model changes and identifiers
  - Serialization format and backward compatibility
  - Command/GUI behavior and permissions
  - Configuration and default values
  - Error handling and fallbacks
- Confirm decisions are complete (no TBDs).

## 3. Implementation Plan
- Break work into atomic steps with clear file paths.
- Identify any new public API changes.
- Identify required migrations or config updates.

## 4. Implementation
- Implement changes in small, reviewable commits.
- Keep platform-specific logic in platform modules.
- Avoid hard dependencies when reflection or optional integration is required.
- Ensure null/absent dependencies fail gracefully.

## 5. Validation & Testing
- Run required build tasks:
  - `./gradlew clean build`
  - `./gradlew test` (if tests exist or were changed)
- Execute manual test scenarios if the feature is runtime-dependent.
- Record test evidence (commands used, screenshots/logs, or test notes).

## 6. Documentation & Localization
- Update configuration docs and defaults.
- Update locale keys (`common/src/main/resources/locales/en-gb.yml`).
- Add or update usage docs for any new commands/features.

## 7. Change Log
- Add a new entry to `docs/CHANGELOG.md` describing user-visible changes.
- Include date, summary, and migration notes if applicable.

## 8. Final Checklist (Must Pass)
- [ ] Requirements and scope defined
- [ ] Design note complete with no open decisions
- [ ] Implementation matches plan
- [ ] Build/test completed
- [ ] Manual test notes captured (if applicable)
- [ ] Docs & locales updated
- [ ] Change log updated
