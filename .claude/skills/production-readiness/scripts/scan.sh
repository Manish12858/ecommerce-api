#!/usr/bin/env bash
# Production-readiness scanner for Spring Boot / Maven / JPA projects.
#
# Prints one line per check:   [AREA] STATUS check-id — message (file:line)
#   STATUS: PASS | WARN | FAIL | REVIEW   (REVIEW = needs a human/LLM read; see SKILL.md)
#
# Deterministic on purpose: only greps and build commands, no opinions. Anything
# that needs judgement is emitted as REVIEW with a pointer to the exact line.
#
# Usage: bash scan.sh [--run-tests]   (run from the project root)

set -u
RUN_TESTS=0
[[ "${1:-}" == "--run-tests" ]] && RUN_TESTS=1

SRC=src/main/java
TEST=src/test/java
RES=src/main/resources

# --- helpers -----------------------------------------------------------------
emit() { printf '[%s] %s %s — %s\n' "$1" "$2" "$3" "$4"; }   # area status id message
CFG_FILES=$(ls "$RES"/application*.properties "$RES"/application*.yml "$RES"/application*.yaml 2>/dev/null)
# grep across config files, print file:line:text
cfg() { [[ -n "$CFG_FILES" ]] && grep -nH -E "$1" $CFG_FILES 2>/dev/null | grep -v -E '^[^:]+:[0-9]+:\s*#'; }
# grep across main sources, print file:line:text
src() { grep -rnH -E "$1" "$SRC" --include='*.java' 2>/dev/null; }
loc() { echo "$1" | head -1 | cut -d: -f1,2; }

echo "# production-readiness scan — $(basename "$PWD") — $(git rev-parse --short HEAD 2>/dev/null || echo 'no-git') — $(date +%F)"
echo

# =============================================================================
echo "## Config & secrets"
A=CONFIG

if [[ -z "$CFG_FILES" ]]; then
  emit $A WARN config-present "no application.properties / .yml found under $RES"
fi

# literal credentials (value present and not a ${PLACEHOLDER})
hit=$(cfg '^\s*spring\.datasource\.password\s*[=:]\s*[^$[:space:]]+' )
if [[ -n "$hit" ]]; then emit $A FAIL secret-db-password "spring.datasource.password is a literal value, not \${ENV_VAR} ($(loc "$hit"))"
else
  hit=$(cfg 'spring\.datasource\.password')
  [[ -n "$hit" ]] && emit $A PASS secret-db-password "datasource password is externalised ($(loc "$hit"))" \
                  || emit $A PASS secret-db-password "no datasource password in config"
fi
hit=$(cfg '(secret|token|api[-_]?key|private[-_]?key)\s*[=:]\s*[^$[:space:]]{8,}' )
[[ -n "$hit" ]] && emit $A FAIL secret-other "literal secret/token/key in config ($(loc "$hit"))" \
                || emit $A PASS secret-other "no other literal secrets in config"

# tracked files that should never be committed
tracked=$(git ls-files 2>/dev/null | grep -E '(^|/)(\.env|\.env\..*|.*\.pem|.*\.p12|.*\.jks|application-(local|secret).*)$' | head -3)
[[ -n "$tracked" ]] && emit $A FAIL secret-tracked-files "sensitive files tracked in git: $(echo $tracked | tr '\n' ' ')" \
                    || emit $A PASS secret-tracked-files "no .env / keystore / local-profile files tracked"

# profiles
profiles=$(ls "$RES"/application-*.properties "$RES"/application-*.yml "$RES"/application-*.yaml 2>/dev/null | xargs -n1 basename 2>/dev/null | tr '\n' ' ')
[[ -n "$profiles" ]] && emit $A PASS config-profiles "profile-specific config present: $profiles" \
                     || emit $A WARN config-profiles "no application-{dev,prod}.* files — same config runs everywhere ($RES)"

# ddl-auto
hit=$(cfg 'spring\.jpa\.hibernate\.ddl-auto\s*[=:]\s*(create|create-drop|update)')
if [[ -n "$hit" ]]; then emit $A FAIL config-ddl-auto "ddl-auto mutates the schema at startup ($(loc "$hit"))"
else
  hit=$(cfg 'spring\.jpa\.hibernate\.ddl-auto')
  [[ -n "$hit" ]] && emit $A PASS config-ddl-auto "ddl-auto=$(echo "$hit" | head -1 | sed -E 's/.*[=:]\s*//') ($(loc "$hit"))" \
                  || emit $A WARN config-ddl-auto "ddl-auto not set explicitly (Boot defaults to 'none' for non-embedded DBs; say so)"
fi

# SQL logging
hit=$(cfg 'spring\.jpa\.show-sql\s*[=:]\s*true')
[[ -n "$hit" ]] && emit $A WARN config-show-sql "show-sql=true — every statement goes to stdout in prod ($(loc "$hit"))" \
                || emit $A PASS config-show-sql "show-sql not enabled"
hit=$(cfg 'logging\.level\.[^=:]*\s*[=:]\s*(DEBUG|TRACE)')
[[ -n "$hit" ]] && emit $A WARN config-debug-logging "DEBUG/TRACE log level configured ($(loc "$hit"))" \
                || emit $A PASS config-debug-logging "no DEBUG/TRACE log levels in config"

# open-in-view
hit=$(cfg 'spring\.jpa\.open-in-view\s*[=:]\s*false')
[[ -n "$hit" ]] && emit $A PASS config-open-in-view "open-in-view=false ($(loc "$hit"))" \
                || emit $A WARN config-open-in-view "open-in-view not disabled — DB connection held for the whole request"

# actuator exposure
if grep -q 'spring-boot-starter-actuator' pom.xml 2>/dev/null; then
  hit=$(cfg 'management\.endpoints\.web\.exposure\.include\s*[=:]\s*\*')
  [[ -n "$hit" ]] && emit $A FAIL config-actuator-exposure "all actuator endpoints exposed over HTTP ($(loc "$hit"))"
  hit=$(cfg 'management\.endpoints\.web\.exposure\.include')
  [[ -z "$hit" ]] && emit $A PASS config-actuator-exposure "actuator on classpath, exposure left at default (health only)"
  hit=$(cfg 'management\.endpoint\.health\.show-details\s*[=:]\s*always')
  [[ -n "$hit" ]] && emit $A WARN config-actuator-health-details "health details shown to unauthenticated callers ($(loc "$hit"))"
else
  emit $A WARN config-actuator-missing "spring-boot-starter-actuator not in pom.xml — no /actuator/health for load balancers"
fi

# error responses
hit=$(cfg 'server\.error\.include-(stacktrace|message)\s*[=:]\s*(always|on_param)')
[[ -n "$hit" ]] && emit $A FAIL config-error-details "stack traces / messages included in error responses ($(loc "$hit"))" \
                || emit $A PASS config-error-details "server.error.include-* not widened"

# swagger reachable
if grep -q 'springdoc' pom.xml 2>/dev/null; then
  hit=$(cfg 'springdoc\.(api-docs|swagger-ui)\.enabled\s*[=:]\s*false')
  [[ -n "$hit" ]] && emit $A PASS config-swagger "springdoc disabled in at least one config ($(loc "$hit"))" \
                  || emit $A WARN config-swagger "springdoc on classpath and never disabled — Swagger UI is public in every profile"
fi

# CORS wide open
hit=$(src '@CrossOrigin\s*(\(\s*\)|\(\s*"\*"\s*\)|\(\s*origins\s*=\s*"\*")')
[[ -n "$hit" ]] && emit $A WARN config-cors "@CrossOrigin(\"*\") on a controller ($(loc "$hit"))"

echo
# =============================================================================
echo "## Code-level"
A=CODE

# forbidden patterns
hit=$(src '@Autowired')
[[ -n "$hit" ]] && emit $A WARN code-autowired "@Autowired field injection ($(echo "$hit" | wc -l | tr -d ' ') hits, first: $(loc "$hit"))" \
                || emit $A PASS code-autowired "no @Autowired — constructor injection throughout"
hit=$(src 'System\.(out|err)\.print')
[[ -n "$hit" ]] && emit $A WARN code-sysout "System.out/err used instead of a logger ($(loc "$hit"))" \
                || emit $A PASS code-sysout "no System.out/err"
hit=$(src '\.printStackTrace\(\)')
[[ -n "$hit" ]] && emit $A WARN code-printstacktrace "printStackTrace() ($(loc "$hit"))" \
                || emit $A PASS code-printstacktrace "no printStackTrace()"
hit=$(src 'catch\s*\(\s*(Exception|Throwable|RuntimeException)\s+\w+\s*\)\s*\{\s*\}')
[[ -n "$hit" ]] && emit $A FAIL code-swallowed-exception "empty catch block ($(loc "$hit"))" \
                || emit $A PASS code-swallowed-exception "no empty catch blocks"

# @Data on entities
for f in $(grep -rlE '^@Entity' "$SRC" 2>/dev/null); do
  grep -nE '^@Data' "$f" >/dev/null && emit $A FAIL code-entity-data "@Data on an @Entity — equals/hashCode over lazy fields and mutable ids ($f:$(grep -nE '^@Data' "$f" | cut -d: -f1))"
done
grep -rlE '^@Entity' "$SRC" 2>/dev/null | xargs grep -lE '^@Data' 2>/dev/null | grep -q . || emit $A PASS code-entity-data "no @Data on entities"

# toString on entities with relationships (recursion risk)
hit=$(grep -rlE '^@Entity' "$SRC" 2>/dev/null | xargs grep -lE '@ToString($|[^.])' 2>/dev/null | head -1)
[[ -n "$hit" ]] && emit $A WARN code-entity-tostring "@ToString on entity — lazy-init / recursion risk ($hit)"

# transactions: services with write calls -> REVIEW listing
for f in $(grep -rlE '^@Service' "$SRC" 2>/dev/null); do
  writes=$(grep -nE '\.(save|saveAll|delete|deleteAll|deleteById|saveAndFlush)\(' "$f" | cut -d: -f1 | tr '\n' ',')
  [[ -n "$writes" ]] && emit $A REVIEW code-transactions "$(basename "$f" .java): repository writes at lines ${writes%,} — confirm each enclosing public method has @Transactional ($f)"
done

# controllers: entity return types, missing @Valid
entities=$(grep -rlE '^@Entity' "$SRC" 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/\.java$//' | paste -sd'|' -)
for f in $(grep -rlE '^@RestController' "$SRC" 2>/dev/null); do
  if [[ -n "$entities" ]]; then
    hit=$(grep -nE "(ResponseEntity|List|Page|PageResponse)?<?\b($entities)\b>?\s+\w+\s*\(" "$f" | grep -E 'public' | head -1)
    [[ -n "$hit" ]] && emit $A FAIL code-entity-leak "$(basename "$f" .java) returns an entity type ($f:$(echo "$hit" | cut -d: -f1))"
  fi
  hit=$(grep -nE '@RequestBody' "$f" | grep -vE '@Valid' | head -1)
  [[ -n "$hit" ]] && emit $A REVIEW code-missing-valid "$(basename "$f" .java): @RequestBody without @Valid at line $(echo "$hit" | cut -d: -f1) — check whether the DTO carries constraints ($f)"
done
emit $A REVIEW code-entity-leak "confirm no DTO in dto/ embeds an entity type (grep 'import .*\.entity\.' src/main/java/**/dto)"

# exception handler coverage
advice=$(grep -rlE '^@RestControllerAdvice|^@ControllerAdvice' "$SRC" 2>/dev/null | head -1)
if [[ -z "$advice" ]]; then
  emit $A FAIL code-exception-handler "no @RestControllerAdvice — unhandled exceptions become Spring's default error page"
else
  missing=""
  for ex in $(grep -rlE 'extends (RuntimeException|Exception)' "$SRC" 2>/dev/null | xargs -n1 basename | sed 's/\.java$//'); do
    grep -qE "\b$ex\b" "$advice" || missing="$missing $ex"
  done
  [[ -n "$missing" ]] && emit $A FAIL code-exception-handler "custom exceptions with no handler in $(basename "$advice" .java):$missing" \
                      || emit $A PASS code-exception-handler "every custom exception is handled in $(basename "$advice" .java)"
  grep -qE 'MethodArgumentNotValidException' "$advice" && emit $A PASS code-validation-handler "validation errors mapped to 400" \
                                                        || emit $A WARN code-validation-handler "no MethodArgumentNotValidException handler — validation failures return Spring's default body"
  grep -qE 'handle\w*\(\s*Exception\s+\w+' "$advice" && emit $A REVIEW code-catchall-handler "catch-all handler exists — confirm it logs and does NOT return ex.getMessage() to the client ($advice)" \
                                                     || emit $A WARN code-catchall-handler "no catch-all Exception handler in $(basename "$advice" .java)"
fi

# sensitive data in logs
hit=$(src 'log\.\w+\(.*(password|passwd|secret|token|card|cvv|ssn)' | grep -viE 'low stock|stock alert' | head -1)
[[ -n "$hit" ]] && emit $A REVIEW code-log-sensitive "log statement mentions a sensitive field ($(loc "$hit"))" \
                || emit $A PASS code-log-sensitive "no log statements mention password/token/card"
hit=$(src 'log\.\w+\(.*\{\}.*,\s*(request|dto|entity|body|customer|order|user)\s*[,)]' | head -1)
[[ -n "$hit" ]] && emit $A REVIEW code-log-bodies "log statement may dump a whole request/entity ($(loc "$hit"))"

# N+1 pointers: paged/list repository methods without EntityGraph/JOIN FETCH
for f in $(grep -rlE 'extends (Jpa|Crud|Paging)\w*Repository' "$SRC" 2>/dev/null); do
  hits=$(grep -nE '^\s*(Page|List)<' "$f" | while IFS=: read -r ln rest; do
    prev=$(sed -n "$((ln-1))p" "$f"); [[ "$prev" =~ @EntityGraph|@Query ]] || echo -n "$ln,"; done)
  [[ -n "$hits" ]] && emit $A REVIEW code-n-plus-one "$(basename "$f" .java): list/page finders without @EntityGraph/@Query at lines ${hits%,} — check whether the mapper touches LAZY associations ($f)"
done
hit=$(cfg 'hibernate\.default_batch_fetch_size')
[[ -n "$hit" ]] && emit $A PASS code-batch-fetch "default_batch_fetch_size set ($(loc "$hit"))"

echo
# =============================================================================
echo "## Build, tests & docs"
A=BUILD

# tests
if [[ $RUN_TESTS -eq 1 ]]; then
  out=$(./mvnw test 2>&1); rc=$?
  summary=$(echo "$out" | grep -E '^\[(INFO|ERROR|WARNING)\] Tests run:.*Failures:' | tail -1 | sed -E 's/^\[[A-Z]+\] //')
  [[ $rc -eq 0 ]] && emit $A PASS build-tests "./mvnw test green — ${summary:-see output}" \
                  || emit $A FAIL build-tests "./mvnw test FAILED — ${summary:-$(echo "$out" | grep -E 'ERROR' | head -1)}"
else
  emit $A REVIEW build-tests "tests not run (pass --run-tests); do not report PASS without running them"
fi

# coverage by layer
for layer in service controller; do
  total=0; covered=0; missing=""
  for f in $(ls "$SRC"/*/*/*/*/$layer/*.java "$SRC"/*/*/*/$layer/*.java 2>/dev/null); do
    n=$(basename "$f" .java); total=$((total+1))
    if find "$TEST" -name "${n}Test.java" 2>/dev/null | grep -q .; then covered=$((covered+1)); else missing="$missing $n"; fi
  done
  [[ $total -eq 0 ]] && continue
  if [[ $covered -eq $total ]]; then emit $A PASS build-coverage-$layer "$covered/$total ${layer}s have a test class"
  elif [[ $covered -eq 0 ]]; then emit $A WARN build-coverage-$layer "0/$total ${layer}s have a test class:$missing"
  else emit $A WARN build-coverage-$layer "$covered/$total ${layer}s tested; missing:$missing"; fi
done
grep -q 'jacoco' pom.xml 2>/dev/null && emit $A PASS build-coverage-tool "jacoco configured" \
                                     || emit $A WARN build-coverage-tool "no coverage plugin (jacoco) in pom.xml — coverage is unmeasured"

# api docs
grep -q 'springdoc' pom.xml 2>/dev/null && emit $A PASS docs-openapi "springdoc-openapi present" \
                                        || emit $A WARN docs-openapi "no OpenAPI/Swagger dependency"

# readme
if ls README* readme* 2>/dev/null | grep -q .; then emit $A PASS docs-readme "README present ($(ls README* readme* | head -1))"
else emit $A WARN docs-readme "no README — nobody can run this without reading the code (HELP.md is Initializr boilerplate)"; fi

# schema <-> entity sync
schema=$(ls db/*.sql src/main/resources/schema.sql src/main/resources/db/*.sql 2>/dev/null | head -1)
if [[ -n "$schema" ]]; then
  ent_tables=$(grep -rhoE '@Table\([[:space:]]*name[[:space:]]*=[[:space:]]*"[^"]+"' "$SRC" --include='*.java' | sed -E 's/.*"([^"]+)"/\1/' | tr 'A-Z' 'a-z' | sort -u)
  sql_tables=$(grep -ioE 'create table\s+`?[a-z_]+`?' "$schema" | awk '{print $3}' | tr -d '`' | tr 'A-Z' 'a-z' | sort -u)
  only_ent=$(comm -23 <(echo "$ent_tables") <(echo "$sql_tables") | tr '\n' ' ')
  only_sql=$(comm -13 <(echo "$ent_tables") <(echo "$sql_tables") | tr '\n' ' ')
  if [[ -z "$only_ent" && -z "$only_sql" ]]; then emit $A PASS build-schema-sync "@Table names match $schema ($(echo "$ent_tables" | wc -l | tr -d ' ') tables)"
  else emit $A FAIL build-schema-sync "schema drift — entities without table: [${only_ent}] tables without entity: [${only_sql}] ($schema)"; fi
else
  emit $A REVIEW build-schema-sync "no schema .sql found — if ddl-auto=validate, where do tables come from?"
fi

# migrations
grep -qE 'flyway|liquibase' pom.xml 2>/dev/null && emit $A PASS build-migrations "migration tool present" \
                                                 || emit $A WARN build-migrations "no Flyway/Liquibase — schema changes are applied by hand (${schema:-no schema file})"

# gitignore hygiene
if [[ -f .gitignore ]]; then
  for p in target/ '\.env' '\*\.log'; do grep -qE "^$p" .gitignore || emit $A WARN build-gitignore "'.gitignore' missing pattern: $p"; done
  git ls-files 2>/dev/null | grep -qE '^target/' && emit $A FAIL build-gitignore "target/ is tracked in git"
fi

echo
echo "# end of scan — resolve every REVIEW line by reading the referenced file"
