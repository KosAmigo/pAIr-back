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
- Inside a feature, use ONLY these 5 folders: `controller/`, `service/`, `dto/`, `repository/`, `entity/`.
  - `controller/` = `@RestController`. `service/` = `@Service` + feature-only exceptions + helpers/internal records.
  - `dto/` = API request/response types. `repository/` = JpaRepository and custom stores. `entity/` = `@Entity` (+ its enums).
  - Never create other folder names (`web/`, `db/`, `domain/`, `model/`, `util/`) inside a feature.
  - Do not create empty folders. Do not put classes directly in `domain/<feature>/` (tests may, for integration tests).
  - If a class fits none of the 5, put it in `service/`.
- Exceptions: shared ones → `global/error/`; feature-only → that feature's `service/`; external-call failures → the `infra/` folder.
- Code used by only one feature goes in that feature, even if it sends mail or builds files. Config/properties classes go in `global/config/`.
- Tests mirror the main package structure.

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
