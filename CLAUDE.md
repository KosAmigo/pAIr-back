# pAIr-back Rules

## Git
- Before starting: `git status` must be clean (otherwise ask the user), then `git fetch` and `git pull`.
- Branch from the latest `develop`: `feat|fix|refactor|docs|test|chore/<short-english-name>`. No PRs.
- When done, merge into develop yourself: push your branch, check its CI is green (`gh run list --branch <branch>`), then `git switch develop && git pull && git merge --no-ff <branch> && ./gradlew build && git push`. If the build or CI fails, don't merge or push. Locally `build` runs only fast tests; CI (GitHub Actions) runs all tests on every push to any branch. If develop CI goes red, fix it right away.
- No direct commits on `develop` (merge commits only). Never touch `main`. Never rebase or force-push pushed commits; update a branch with `git merge origin/develop`.
- Commit/push only when the user asks. Message: `type: 한국어 요약` + a blank line + 2–4 bullets. No Co-Authored-By.
- Never commit `.env`, `pilot-data/`, build output or secrets.

## Package structure (MUST)
Enforced by `global/config/PackageRulesConfig`: a violation fails `./gradlew classes`/`build`, so IntelliJ ▶ fails before running (keep IntelliJ's default "Build and run using: Gradle"), and it also stops `PairApplication` at startup. Never weaken the check: don't edit `PackageRulesConfig` to pass, and don't set `PACKAGE_RULES_ENABLED=false` (production only). If a class fits no rule, ask the user. Background: pAIr-docs `설계/백엔드_패키지_구조.md`.

```
com.pair
├─ PairApplication
├─ global/   config/ error/ security/
├─ infra/    ai/ sandbox/ storage/ payment/ mail/
└─ domain/<feature>/   controller/ service/ dto/ repository/ entity/ exception/
```
- Only these folders. No classes directly in `domain/<feature>/`, no empty folders. A new `domain/<feature>/` is fine; new folders under `global/` or `infra/` need a team decision.
- Folder name = class suffix, for EVERY class in the folder with no exceptions (interfaces, helpers, schedulers too):

| Folder | Class name |
| --- | --- |
| `controller/` | `XxxController` |
| `service/` | `XxxService` |
| `dto/` | `XxxRequest` / `XxxResponse` for API, `XxxDto` for other data |
| `repository/` | `XxxRepository` (Xxx = what it stores, without `Dto`) |
| `entity/` | plain noun (no `Record`/`Entity`), always `@Table(name = ...)`; entity enums too |
| `exception/` | `XxxException` |
| `global/config/` | `XxxConfig` / `XxxProperties` |
| `global/error/` | `XxxException` / `XxxExceptionHandler` |

- Has logic → `service/`. Data only → `dto/`. One type per file.
- Used by one feature → that feature's folder. Shared exceptions → `global/error/`. External-call failures → `infra/<area>/`. Interceptors/resolvers → `global/security/`. Config/properties → `global/config/`.
- Name = singular noun + suffix (`EditProposalService`, not `EditProposals`). An entity keeps the plain name; clashing data gets `Dto` (`Attempt` vs `AttemptStateDto`).
- Renames must not change `@Table` names or JSON field names.
- Tests mirror the main package. Name: `<TargetClass or scenario>Test`; multi-layer: `XxxIntegrationTest`.

## Code
- Java 21, Spring Boot. API requests and responses use `dto/` types.
- Put transactions on service methods. Watch for N+1 queries.
- AI model calls go through `infra/ai/ModelClient` (`json(...)` for structured output, `text(...)` for plain text). Pass the model name, prompt, max tokens and effort; it picks the vendor from the name (`claude-*` → Anthropic, `gpt-*` → OpenAI) and returns token counts. Keep model names in settings, not code, so switching models is a config change. Don't add new per-vendor clients or call Spring AI chat models directly. Exceptions: streaming (`AssistantClient`) and the OpenAI Batch API (`BatchClient`). Need a new vendor or call type? Extend `ModelClient`.

## Tests (keep the build fast)
Summary for people: pAIr-docs `설계/백엔드_테스트와_빌드.md`.
- Spring integration tests extend a shared base in `global/config`: `ExamIntegrationTest` (dev header auth) or `BearerIntegrationTest` (Bearer token auth). They share one Spring context, so don't add `@MockitoBean`, `@DynamicPropertySource`, `@TestPropertySource`, `properties=` or `@ActiveProfiles` to a subclass: any difference starts a new context and slows every build. Set per-test mock behaviour with `when(...)` in `@BeforeEach`.
- Need a mock bean or property the bases don't have? Add it to the base only if most tests can share it; otherwise ask the user first.
- Shared contexts share the H2 data. Don't depend on rows other tests left (e.g. counting all tasks); clean or filter your own data.
- Tag slow tests `@Tag("slow")`: Spring contexts (the bases already have it), real JVM/code runs, anything over ~10s.
- Don't change `src/test/resources/spring.properties` (context cache 64: closing a context drops the shared H2 tables) or the test memory/fork settings in `build.gradle` without asking.

## Finishing a task
- Add tests for new or changed business logic.
- Run `./gradlew build` before merging and report changed files and results briefly. Locally it skips `@Tag("slow")` (about 1 min); CI runs everything. To run all tests locally: `./gradlew fullTest` (about 5 min), or IntelliJ's shared run configs in `.run/` (빠른 빌드 / 전체 테스트 / 서버 실행). Running one slow test directly (IntelliJ ▶ or `--tests`) still runs it.
- Read only the files the task needs. Don't add docs or extra agents unless needed. Ask before large refactors.
