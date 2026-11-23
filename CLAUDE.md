# Development Rules

## Package Structure and Naming (MUST, team decision 2026-11-23)

Every class you create, move or rename in `backend/src/main/java/com/pair` MUST follow these rules.
They are enforced in code: `global/config/PackageRules` runs when `PairApplication` starts (also when run directly from IntelliJ), in `./gradlew test`, and before `bootRun`/`bootJar`. A violation stops the app with a list of offending classes.
Full rationale: pAIr-docs `설계/백엔드_패키지_구조.md`.

### Layout

```
com.pair
├─ PairApplication            the only class directly in com.pair
├─ global/                    shared by all features
│  ├─ config/                 @Configuration, @ConfigurationProperties, web config, PackageRules
│  ├─ error/                  shared exceptions, ApiExceptionHandler
│  └─ security/               CurrentUser, argument resolvers, interceptors, JWT
├─ infra/                     talks to external systems
│  ├─ ai/  sandbox/  storage/  payment/  mail/
└─ domain/                    one package per business feature
   └─ auth, company, credit, task, taskgen, exam, attempt, question, scoring, grader
```

Inside `domain/<feature>/`, use ONLY these 6 folders, and only the ones you need (no empty folders):

| Folder | Holds | Class name |
| --- | --- | --- |
| `controller/` | `@RestController` only | `XxxController` |
| `service/` | `@Service` classes; helpers (`@Component` or plain); internal records/value objects | `@Service` → `XxxService`; helpers → descriptive noun (no `@Service` on them) |
| `dto/` | API request/response types, one type per file | `XxxRequest`, `XxxResponse`; parts nested inside them → `XxxDto` |
| `repository/` | Spring Data repositories and hand-written stores | `XxxRepository`, where Xxx is the type it saves/returns |
| `entity/` | `@Entity`, `@Embeddable`, enums used by entities | plain noun; never `Record`/`Entity` suffix; always `@Table(name = ...)` |
| `exception/` | exceptions used only by this feature | `XxxException` |

Hard rules:
- No other folder names inside a feature (`web/`, `db/`, `domain/`, `model/`, `util/`, `common/`, `impl/` are all forbidden).
- No classes directly in `domain/<feature>/`. (Tests may put multi-layer integration tests there.)
- Do not add new folders under `global/` or `infra/`, or new top-level packages, on your own. That is a team decision, and it requires updating the doc, `PackageRules` and this file together.
- A new feature is fine: add `domain/<lowercase-word>/` (e.g. `notification`).

### Where does it go? (quick lookup)

| Thing | Location |
| --- | --- |
| Used by only one feature (even mail text, PDF building, schedulers, WebSocket handlers) | that feature's `service/` |
| Exception shared by several features (401/403/409 etc.) | `global/error/` |
| Exception used by one feature | `domain/<feature>/exception/` |
| Failure of an external call (AI, payment) | the matching `infra/` folder |
| Interceptor, argument resolver, auth check, even if feature-specific | `global/security/` |
| `@ConfigurationProperties`, `@Configuration`, even if feature-specific | `global/config/` (`XxxConfig`, `XxxProperties`) |
| HTTP client to an outside API | `infra/<area>/XxxClient` |
| File storage (S3/local) | `infra/storage/XxxStorage` |
| Doesn't fit anything above | `service/` of the feature that uses it |

### Naming details
- Repository name = the type it handles. The JPA repository for entity `Attempt` is `AttemptRepository`. A hand-written store for the non-entity type `AttemptState` is `AttemptStateRepository` (with implementations `DbAttemptStateRepository`, `FileAttemptStateRepository`). Never use vague names like `XxxStoreRepository` or `XxxDao`.
- If a non-entity class would clash with an entity name, the entity keeps the plain name and the other class gets a role suffix (`Attempt` entity vs `AttemptState`, `ChatTurn` vs `ChatTurnState`).
- No bundle files like `XxxDtos` or `XxxResponses`. One request/response type per file.
- Renaming an entity class must not change its table: keep `@Table(name = ...)`, and keep JSON field names when renaming DTOs.
- UpperCamelCase, and treat acronyms as words: `JwtTokens`, `S3Client`, not `JWTTokens`.
- Tests mirror the main package: `domain/exam/service/ExamSessionService` → `domain/exam/service/ExamSessionServiceTest`. Multi-layer tests: `XxxIntegrationTest`.

### When the structure check fails
- Read the message. It names the class and the rule. Move or rename the class to fit.
- NEVER weaken or bypass the check: do not edit `PackageRules`, do not add exceptions to it, do not set `PACKAGE_RULES_ENABLED=false` locally, and do not skip `ArchitectureTest`. `PACKAGE_RULES_ENABLED=false` exists only for the production server.
- If a class truly fits no rule, stop and ask the user. The rule change is a team decision.
- Quick check without starting the server: `./gradlew archTest` (in `backend/`).

## Backend
- Use Java 21 and Spring Boot.
- Use DTOs (from `dto/`) for API requests and responses.
- Apply transaction boundaries appropriately.
- Check N+1 queries and SQL performance.

## Testing
- Write tests for important business logic.
- Run relevant tests after modifications.
- Run build (or at least `./gradlew archTest`) before completing tasks.

## Token Efficiency
- Inspect only files relevant to the task.
- Avoid reading the entire project repeatedly.
- Do not invoke agents unless necessary.
- Do not generate unnecessary documentation.
- Summarize changed files and test results briefly.
- Ask before large-scale refactoring.
