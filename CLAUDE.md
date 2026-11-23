# Development Rules

## Git workflow
- Before starting: `git status` must be clean (otherwise ask the user), then `git fetch` and `git pull`.
- Branch from the latest `develop`: `feat/`, `fix/`, `refactor/`, `docs/`, `test/` or `chore/` + a short English name. No PRs.
- Commit message: `type: 한국어 요약` + a blank line + 2–4 short bullets. No Co-Authored-By.
- When done, merge into develop yourself: `git switch develop && git pull && git merge --no-ff <branch> && ./gradlew build && git push`. Don't push if the build fails.
- No direct commits on `develop` (merge commits only). Never touch `main`. Never rebase or force-push pushed commits; to update a pushed branch, use `git merge origin/develop`.
- Commit or push only when the user asks.

## Package structure (MUST)
Enforced by `global/config/PackageRules`: a violation fails `./gradlew classes`/`build`, so IntelliJ ▶ fails before running (keep IntelliJ's default "Build and run using: Gradle"), and it also stops `PairApplication` at startup. Never weaken the check: don't edit `PackageRules` to pass, and don't set `PACKAGE_RULES_ENABLED=false` (production only). If a class fits no rule, ask the user. Background: pAIr-docs `설계/백엔드_패키지_구조.md`.

```
com.pair
├─ PairApplication
├─ global/   config/ error/ security/
├─ infra/    ai/ sandbox/ storage/ payment/ mail/
└─ domain/<feature>/   controller/ service/ dto/ repository/ entity/ exception/
```
- Only these folders. No classes directly in `domain/<feature>/`, no empty folders. A new `domain/<feature>/` is fine; new folders under `global/` or `infra/` need a team decision.
- Folder name = class suffix:

| Folder | Class name |
| --- | --- |
| `controller/` | `XxxController` |
| `service/` | `XxxService`, every class with no exceptions (interfaces, helpers, schedulers too) |
| `dto/` | `XxxRequest` / `XxxResponse` for API, `XxxDto` for other data |
| `repository/` | `XxxRepository` (Xxx = what it stores, without `Dto`) |
| `entity/` | plain noun (no `Record`/`Entity`), always `@Table(name = ...)`; entity enums too |
| `exception/` | `XxxException` |
| `global/config/` | `XxxConfig` / `XxxProperties` |

- Has logic → `service/`. Data only → `dto/`. One type per file.
- Used by one feature → that feature's folder. Shared exceptions → `global/error/`. External-call failures → `infra/<area>/`. Interceptors/resolvers → `global/security/`. Config/properties → `global/config/`.
- Name = singular noun + suffix (`EditProposalService`, not `EditProposals`). An entity keeps the plain name; clashing data gets `Dto` (`Attempt` vs `AttemptStateDto`).
- Renames must not change `@Table` names or JSON field names.
- Tests mirror the main package. Name: `<TargetClass or scenario>Test`; multi-layer: `XxxIntegrationTest`.

## Backend
- Use Java 21 and Spring Boot.
- Use DTOs (from `dto/`) for API requests and responses.
- Apply transaction boundaries appropriately.
- Check N+1 queries and SQL performance.

## Testing
- Write tests for important business logic.
- Run relevant tests after modifications.
- Run `./gradlew build` before completing tasks.

## Token Efficiency
- Inspect only files relevant to the task.
- Avoid reading the entire project repeatedly.
- Do not invoke agents unless necessary.
- Do not generate unnecessary documentation.
- Summarize changed files and test results briefly.
- Ask before large-scale refactoring.
