---
name: production-readiness
description: Audit a Spring Boot / Maven / JPA service for production readiness and produce a PASS / WARN / FAIL scorecard with file:line evidence and a prioritised fix list. Use this whenever the user asks whether the app is "ready to ship / deploy / go live", wants a "pre-release / pre-deploy / go-live checklist", a "production audit", a "health check of the codebase", or asks "what's missing before prod?" — even if they don't say "production readiness". Also use it when asked to review config/secrets hygiene, test coverage gaps, or "is this safe to deploy" for a Spring Boot project. Prefer this over the narrower /check-transactions, /find-n-plus-one and /verify-dto-coverage commands when the user wants the whole picture; those three are subsumed here.
---

# Production Readiness Check

Answer one question: **what would bite us if this service shipped today?** Three areas are in scope — Config & secrets, Code-level, Build/tests/docs. Ops/runtime concerns (pool sizing, timeouts, graceful shutdown) are deliberately out of scope unless the user asks.

Bias toward evidence over opinion. Every finding names a file and line the reader can click; nothing is reported that you haven't confirmed by reading the code or running a command. A readiness report the team can't verify is one they'll ignore.

## Workflow

### 1. Run the scanner

```bash
bash .claude/skills/production-readiness/scripts/scan.sh [--run-tests]
```

Run it from the project root. It prints one line per check: `[AREA] STATUS check-id — message (file:line)`. `STATUS` is `PASS`, `WARN`, `FAIL`, or `REVIEW`. Pass `--run-tests` unless the user says the suite is slow or already green — a readiness report that didn't run the tests is guessing.

The scanner is deliberately conservative: it only emits `FAIL`/`WARN` on patterns that are almost always wrong (a literal password, `ddl-auto=create`, `@Data` on an `@Entity`). Anything that needs a human read comes back as `REVIEW` with a pointer.

### 2. Resolve every REVIEW line by reading the code

The scanner can't judge these; you can. For each `REVIEW` open the file it points to and decide PASS / WARN / FAIL:

- **Transaction boundaries** — a `@Service` public method that saves/deletes, or mutates a managed entity, without `@Transactional` (class-level `readOnly = true` doesn't count for writes). Private helpers called from an annotated method are fine.
- **Entity leakage** — a `@RestController` method returning an `@Entity` type directly or nested inside a DTO. Enums are fine.
- **Missing `@Valid`** — a `@RequestBody` parameter whose DTO has constraints but no `@Valid` on the parameter. Constraints that are never triggered are worse than none: they look like protection.
- **Exception coverage** — every custom exception in `exception/` has a handler in the `@RestControllerAdvice`, plus handlers for `MethodArgumentNotValidException` (400) and a catch-all (500) that logs. Check that the catch-all does not echo `ex.getMessage()` to the client.
- **Sensitive data in logs** — `log.*` lines that include passwords, tokens, full card numbers, or whole request bodies/entities. Emails and ids are usually acceptable; say so if you allow them.
- **N+1 on list endpoints** — a paged/list repository method whose mapper touches a LAZY association with no `@EntityGraph`, `JOIN FETCH`, or `default_batch_fetch_size`. Single-entity lookups are not N+1; don't flag them.
- **Schema ↔ entity sync** — when `ddl-auto=validate`, every `@Table(name=...)` must exist in the schema file and vice versa; the scanner lists the diff, you confirm it.
- **Test coverage by layer** — which services/controllers have a test class. No controller tests at all is a WARN, not a FAIL, if the service layer is well covered; say why.

Read `references/checks.md` for the full list of checks, what each one looks for, and why it matters — consult it when a finding is borderline or the user asks "why does this matter?".

### 3. Write the scorecard

Use exactly this structure so reports are comparable run to run:

```markdown
# Production Readiness — <project name> (<git short sha>, <date>)

**Verdict:** NOT READY | READY WITH WARNINGS | READY
<one sentence: the single most important blocker, or why it's fine>

## Scorecard
| Area | Status | Summary |
|---|---|---|
| Config & secrets | FAIL/WARN/PASS | n FAIL, m WARN |
| Code-level | ... | ... |
| Build, tests & docs | ... | ... |

## Findings
### 🔴 FAIL — must fix before deploy
- **<check-id>** — <what and why it matters> ([file:line](path#Lnn))
  Fix: <one concrete action>

### 🟡 WARN — fix soon / decide consciously
...

### ✅ PASS — confirmed
<compact list; one line each; include evidence so PASSes are believable>

## Fix order
1. <highest-leverage fix first — usually secrets, then anything that loses data>
2. ...
```

Rules for the verdict: any FAIL → **NOT READY**. Only WARNs → **READY WITH WARNINGS**. Otherwise **READY**. Don't soften a FAIL into a WARN because the fix is easy; the severity is about consequence, not effort.

Keep the PASS section — a report that only lists problems makes the reader wonder what wasn't checked.

### 4. Offer, don't act

This skill produces a report only. End by offering to apply the fixes in priority order, and let the user choose. Never edit files as part of the audit — the user asked for a diagnosis, not surgery.

## Severity calibration

| Severity | Means |
|---|---|
| FAIL | Ships a security hole, data-loss risk, or guaranteed production incident. Literal credentials in tracked files; `ddl-auto` that mutates schema; stack traces returned to clients; write paths without transactions; failing tests. |
| WARN | Degrades operability, cost, or maintainability but won't page anyone on day one. SQL logging on; no profile separation; swagger reachable in prod; missing README; a layer with zero tests; N+1 on a paged endpoint. |
| PASS | Confirmed good, with the evidence line. |

When the same root cause produces several symptoms (e.g. no `application-prod.properties` explains both `show-sql=true` and the exposed swagger), report the root cause once as the finding and list the symptoms under it — a fix list with five items that are all "add a prod profile" is noise.

## Calibrating to the project

Read the project's `CLAUDE.md` and `.claude/rules/*` first. They define what "correct" means here (e.g. `@RequiredArgsConstructor` not `@Autowired`, `ddl-auto=validate` by design, tables owned by `db/schema.sql`). A check that contradicts a documented project decision should be reported as PASS-with-note, not as a finding — the team already decided.
