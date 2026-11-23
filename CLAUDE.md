# Development Rules

## Backend
- Use Java 21 and Spring Boot.
- Use DTOs for API requests and responses.
- Apply transaction boundaries appropriately.
- Check N+1 queries and SQL performance.

## Package Structure (2026-11-23 팀 확정)
Full rule: pAIr-docs `설계/백엔드_패키지_구조.md`. Follow it for every new or moved class.

```
com.pair
├─ global/   config/, error/, security/      shared by all features
├─ infra/    ai/, sandbox/, storage/, payment/, mail/   external systems
└─ domain/   auth, company, credit, task, taskgen, exam, attempt, question, scoring, grader
```

- Package by feature first: every business feature lives in `domain/<feature>/`.
- Inside a feature, use ONLY these 6 folders: `controller/`, `service/`, `dto/`, `repository/`, `entity/`, `exception/`.
  - `controller/` = `@RestController` only. `service/` = `@Service` + helpers/internal records.
  - `dto/` = API request/response types. `repository/` = JpaRepository and custom stores. `entity/` = `@Entity` (+ its enums). `exception/` = feature-only exceptions.
  - Never create other folder names (`web/`, `db/`, `domain/`, `model/`, `util/`) inside a feature.
  - Do not create empty folders. Do not put classes directly in `domain/<feature>/` (tests may, for integration tests).
  - If a class fits none of the 6, put it in `service/`.
- Exceptions: shared ones → `global/error/`; feature-only → that feature's `exception/`; external-call failures → the `infra/` folder.
- Code used by only one feature goes in that feature, even if it sends mail or builds files. Config/properties classes go in `global/config/`. Interceptors/resolvers go in `global/security/`.
- Tests mirror the main package structure.

### Class naming
- `controller/`: `XxxController`. `@Service` classes: `XxxService` (helpers in `service/` use `@Component` and any descriptive noun).
- `dto/`: `XxxRequest`, `XxxResponse`; parts nested inside them: `XxxDto`. One type per file, no `XxxDtos` bundle files.
- `repository/`: `XxxRepository`. `entity/`: plain noun, no `Record`/`Entity` suffix, always with `@Table(name = ...)`.
- Exceptions: `XxxException`. `global/config/`: `XxxConfig`, `XxxProperties`. `infra/`: `XxxClient` for external APIs, `XxxStorage` for file storage.
- If a non-entity class clashes with an entity name, the entity keeps the plain name and the other gets a role suffix (e.g. `Attempt` entity, `AttemptState`).
- Tests: `TargetClassTest`; multi-layer tests: `XxxIntegrationTest`.
- `global/config/PackageRules` checks these rules at startup (non-prod) and in `./gradlew build`/`bootRun`. A violation stops the app.

## Testing
- Write tests for important business logic.
- Run relevant tests after modifications.
- Run build before completing tasks.

## Token Efficiency
- Inspect only files relevant to the task.
- Avoid reading the entire project repeatedly.
- Do not invoke agents unless necessary.
- Do not generate unnecessary documentation.
- Summarize changed files and test results briefly.
- Ask before large-scale refactoring.
