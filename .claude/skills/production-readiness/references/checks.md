# Production readiness checks — reference

Each check below has an id (matching `scan.sh` output), what it looks for, why it matters, and what a good fix looks like. Consult this when a finding is borderline, when the user asks "why does this matter?", or when writing the Fix line in the report.

Contents
- [Config & secrets](#config--secrets)
- [Code-level](#code-level)
- [Build, tests & docs](#build-tests--docs)
- [Deciding severity on borderline cases](#deciding-severity-on-borderline-cases)

---

## Config & secrets

### secret-db-password / secret-other — FAIL
**Looks for:** a literal value after `spring.datasource.password=` or any `*secret*`, `*token*`, `*api-key*` property that isn't a `${PLACEHOLDER}`.
**Why:** anything in `src/main/resources` ends up in git history *and* inside the jar. Rotating a leaked password later doesn't un-leak it. Even a dev password sets the precedent that the prod one goes in the same place.
**Fix:** `spring.datasource.password=${DB_PASSWORD}` and supply it via environment / a secrets manager. For local dev, `application-local.properties` (git-ignored — the project's `.gitignore` already has `application-local.*`) or a `.env` loaded by the IDE.

### secret-tracked-files — FAIL
**Looks for:** `.env*`, keystores (`.pem/.p12/.jks`), `application-local.*`, `application-secret*` in `git ls-files`.
**Why:** same as above; these files exist precisely to hold things that must not be committed.
**Fix:** `git rm --cached`, add to `.gitignore`, rotate whatever was in them.

### config-profiles — WARN
**Looks for:** at least one `application-<profile>.properties|yml`.
**Why:** with a single config file, every "dev convenience" (`show-sql`, open swagger, verbose logs, localhost DB) is also the prod setting. Profiles are how you say "this only in dev" without a code change.
**Fix:** move dev-only settings into `application-dev.properties`; keep `application.properties` as the safe, quiet default; run prod with `SPRING_PROFILES_ACTIVE=prod`. Several WARNs below usually collapse into this single fix — report it as one root cause.

### config-ddl-auto — FAIL if create/create-drop/update
**Looks for:** `spring.jpa.hibernate.ddl-auto`.
**Why:** `update` silently alters production tables on deploy (and can't drop or rename, so drift accumulates); `create`/`create-drop` wipe data. `validate` or `none` are the only production-safe values.
**Fix:** `validate` (fails fast on mapping drift — the project's documented choice) or `none` + a migration tool.

### config-show-sql — WARN
**Looks for:** `spring.jpa.show-sql=true`, `hibernate.format_sql`, `highlight_sql`.
**Why:** logs every statement, unbuffered, to stdout with parameter-free SQL. On a busy service this is a measurable CPU/IO cost, floods log aggregation, and `format_sql` multiplies line count. It's also not the right tool — `logging.level.org.hibernate.SQL=DEBUG` goes through the logger and can be toggled per profile.
**Fix:** move to the dev profile, or delete and use the logger-based switch when debugging.

### config-debug-logging — WARN
**Looks for:** `logging.level.*=DEBUG|TRACE` in a non-dev config.
**Why:** volume and cost, plus DEBUG frequently includes request payloads.

### config-open-in-view — WARN
**Looks for:** `spring.jpa.open-in-view=false`.
**Why:** the default (`true`) keeps the JPA session — and its DB connection — open until the view is rendered, so slow clients pin pool connections and lazy-loading errors are masked until prod load. Boot warns about this at startup for a reason.
**Fix:** set to `false` and fix the lazy-init exceptions that surface in tests.

### config-actuator-* — FAIL if `exposure.include=*`, WARN if missing
**Looks for:** actuator on the classpath; `management.endpoints.web.exposure.include`; `health.show-details=always`.
**Why:** `*` exposes `/actuator/env` (all config, including secrets), `/heapdump`, `/threaddump`, and `/shutdown` if enabled. Conversely, no actuator means no `/actuator/health` for load balancers and orchestrators.
**Fix:** keep the default (`health` only), or list exactly what ops needs; put `show-details=when-authorized` behind security.

### config-error-details — FAIL
**Looks for:** `server.error.include-stacktrace|include-message|include-binding-errors=always|on_param`.
**Why:** stack traces reveal class names, library versions, file paths and sometimes SQL to any caller.
**Fix:** leave at default (`never`) and rely on the `@RestControllerAdvice` for structured errors.

### config-swagger — WARN
**Looks for:** springdoc present and never disabled.
**Why:** a public Swagger UI is a map of every endpoint plus try-it-out. Fine for an internal service behind auth; a WARN otherwise. Not a FAIL because it's often deliberate.
**Fix:** `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false` in the prod profile, or put it behind auth.

### config-cors — WARN
**Looks for:** `@CrossOrigin` with `*`.
**Why:** allows any origin to call the API from a browser with credentials-free requests. Usually a leftover from a frontend spike.

---

## Code-level

### code-transactions — FAIL when a write is outside a transaction
**Looks for:** repository `save/delete/*` calls in `@Service` classes; you confirm the enclosing public method is `@Transactional` (read-write). Also watch for **dirty-checking writes** — a method that only calls setters on a managed entity still writes at commit, and without a transaction there is no commit, so the change is silently lost.
**Why:** without a transaction, a service method that saves an order and then decrements stock can half-succeed. `@Transactional(readOnly = true)` at class level makes this worse: Hibernate sets the session to `FlushMode.MANUAL`, so writes in a method that forgot to override it vanish without an error.
**Gotcha:** self-invocation (`this.otherMethod()`) bypasses the proxy; the *outer* method needs the annotation.
**Fix:** `@Transactional` on every public write method.

### code-entity-leak — FAIL
**Looks for:** controller return types (or DTO fields) that are `@Entity` classes.
**Why:** Jackson will walk lazy associations (→ `LazyInitializationException` or an accidental full-graph dump), bidirectional links recurse, and every schema change becomes an API change. Enums are not entities and are fine to expose.
**Fix:** map to a response DTO in the service; the project's convention already says DTOs only in controllers.

### code-missing-valid — FAIL if the DTO has constraints, PASS otherwise
**Looks for:** `@RequestBody` without `@Valid`.
**Why:** constraints on a DTO do nothing unless something triggers validation. A `@NotBlank` that never runs is worse than none — the reader assumes it's enforced.

### code-exception-handler / code-validation-handler / code-catchall-handler
**Looks for:** every custom exception class named in the `@RestControllerAdvice`; a `MethodArgumentNotValidException` handler; a catch-all `Exception` handler that logs (with the throwable) and returns a generic message.
**Why:** an unhandled custom exception becomes a 500 with Spring's default body. A catch-all that returns `ex.getMessage()` leaks internals (`Duplicate entry 'x' for key 'uk_...'`); one that doesn't log loses the only record of the failure.
**Fix:** one handler per exception → status mapping, catch-all logs `ex` and returns a fixed message.

### code-log-sensitive / code-log-bodies — FAIL if confirmed
**Looks for:** log statements mentioning password/token/card/etc., or logging whole request/entity objects.
**Why:** logs are the least-protected copy of your data — shipped to third-party aggregators, kept for months, searchable by everyone with dashboard access. Logging a whole `CustomerRequest` puts the address and phone there.
**Judgment:** ids and emails are commonly accepted; say explicitly if you're allowing them.

### code-n-plus-one — WARN on paged/list endpoints
**Looks for:** `Page<>`/`List<>` repository methods without `@EntityGraph`/`@Query(JOIN FETCH)`, where the service mapper touches a LAZY association.
**Why:** 1 query for the page + N for each lazy association touched per row. A 20-row page with three lazy hops is 60+ queries; it's fine on a laptop with 10 rows and falls over on the first real dataset.
**Not N+1:** single-entity lookups (`findById` then one lazy load) — one extra query is a nit, not a finding.
**Fix:** `@EntityGraph(attributePaths=...)` for to-one associations on the paged finder; `spring.jpa.properties.hibernate.default_batch_fetch_size=20` for collections (a collection fetch-join with `Pageable` makes Hibernate page in memory, which is worse).

### code-autowired / code-sysout / code-printstacktrace / code-swallowed-exception
**Why:** field injection hides dependencies and blocks constructor-based tests; `System.out` bypasses log levels, formats and aggregation; `printStackTrace` goes to stderr unstructured; an empty catch turns a failure into silent wrong behaviour (FAIL — the others are WARN).

### code-entity-data / code-entity-tostring — FAIL / WARN
**Why:** `@Data` on an entity generates `equals/hashCode` over all fields including lazy collections (→ lazy-init exceptions in `Set`s, hash changes when the id is assigned) and `toString` that recurses through bidirectional links. Project convention forbids it.

---

## Build, tests & docs

### build-tests — FAIL if red, REVIEW if not run
**Why:** self-evident, but the important rule is *don't report PASS without running them*. A readiness report that assumes the suite is green is worthless the one time it isn't.

### build-coverage-service / build-coverage-controller — WARN
**Looks for:** a `<Name>Test.java` for each class in `service/` and `controller/`.
**Why:** service tests prove business rules; controller tests (`@WebMvcTest`) prove status codes, validation → 400, and the error body shape — none of which the service tests touch. Zero controller tests means the HTTP contract is untested.
**Judgment:** with strong service coverage this is WARN. With neither, FAIL.

### build-coverage-tool — WARN
**Why:** without JaCoCo (or similar) "we have tests" is unquantified; a coverage gate in CI is the only thing that stops it eroding.

### docs-openapi / docs-readme — WARN
**Why:** OpenAPI is the contract consumers build against. A README is how the next person runs it: DB setup, env vars, `./mvnw spring-boot:run`, where Swagger is. Spring Initializr's `HELP.md` doesn't count.

### build-schema-sync — FAIL
**Looks for:** `@Table(name=)` set vs `CREATE TABLE` set in the schema file.
**Why:** with `ddl-auto=validate` a missing table means the app refuses to start — in prod, at deploy time.

### build-migrations — WARN
**Why:** a hand-applied `schema.sql` that starts with `DROP DATABASE` cannot be run against prod, so schema changes become undocumented manual steps. Flyway/Liquibase makes them versioned, ordered and repeatable. WARN not FAIL because a small team can live with a documented manual process for a while.

### build-gitignore — WARN / FAIL if `target/` tracked
**Why:** build output and logs in git bloat the repo and leak local paths; `.env` un-ignored is a secret waiting to be committed.

---

## Deciding severity on borderline cases

- **Severity is about consequence, not effort.** A one-line fix for a leaked password is still a FAIL.
- **Documented project decisions are not findings.** If `CLAUDE.md` says `ddl-auto=validate` and tables come from `schema.sql`, the missing migration tool is a WARN-with-context, not a surprise.
- **Collapse symptoms into root causes.** `show-sql`, open swagger, localhost datasource → one finding: "no prod profile".
- **PASS needs evidence too.** "no @Autowired (grep clean across 47 files)" is believable; "DI looks fine" is not.
- **When unsure between WARN and FAIL, ask: would this page someone, lose data, or leak data in the first week?** Yes → FAIL.
