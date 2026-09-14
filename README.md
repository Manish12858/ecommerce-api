# ecommerce-api

A REST API for a small e-commerce back office — categories, products, customers, orders, payments and product reviews — built on **Spring Boot 4.1 / Java 21 / MySQL**.

It is deliberately opinionated: DTOs at the edge, business rules in services, one-way order state machine, stock reserved at order time, and every error shaped by a single `GlobalExceptionHandler`. Interactive docs via Swagger UI.

---

## Contents

- [Stack](#stack)
- [Quick start](#quick-start)
- [Docker](#docker)
- [Configuration](#configuration)
- [API reference](#api-reference)
- [Business rules](#business-rules)
- [Error responses](#error-responses)
- [Project layout](#project-layout)
- [Testing & coverage](#testing--coverage)
- [Database schema](#database-schema)
- [Claude Code tooling](#claude-code-tooling)
- [Known gaps / roadmap](#known-gaps--roadmap)

---

## Stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 4.1.1 (Spring Framework 7, Jackson 3) |
| Persistence | Spring Data JPA, Hibernate ORM 7.4, MySQL 8 (`mysql-connector-j`) |
| Validation | Jakarta Bean Validation |
| API docs | springdoc-openapi 3.1 (Swagger UI) |
| Build | Maven (wrapper included), JaCoCo 0.8.15 |
| Tests | JUnit 5, Mockito, AssertJ, MockMvc (`@WebMvcTest`) |
| Boilerplate | Lombok |

---

## Quick start

### Prerequisites

- JDK 21
- MySQL 8 running locally (default `localhost:3306`)
- No Maven install needed — use the wrapper (`./mvnw`, `mvnw.cmd` on Windows)

### 1. Create the database

The schema is owned by [`db/schema.sql`](db/schema.sql), **not** by Hibernate (`ddl-auto=validate`). The script drops and recreates `ecommerce_db`, creates all tables, and seeds reference data (4 categories, 10 products, 3 customers).

```bash
mysql -u root -p < db/schema.sql
```

> ⚠️ The script starts with `DROP DATABASE IF EXISTS ecommerce_db`. Never run it against anything but a local dev database.

### 2. Provide credentials

Copy the example and fill in your MySQL password:

```bash
cp .env.example .env
# edit .env → DB_PASSWORD=your-password
```

`.env` is git-ignored and loaded automatically via `spring.config.import`. See [Configuration](#configuration).

### 3. Run

```bash
./mvnw spring-boot:run
```

The app starts on **http://localhost:8080**.

| URL | What |
|---|---|
| http://localhost:8080/swagger-ui.html | Swagger UI — try every endpoint |
| http://localhost:8080/api-docs | Raw OpenAPI 3 JSON |
| http://localhost:8080/actuator/health | Health check |

### 4. Smoke test

```bash
# list products (paged)
curl http://localhost:8080/api/v1/products

# place an order for customer 1: 3 × product 1
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":1,"shippingAddress":"123 Maple Street","paymentMethod":"UPI",
       "items":[{"productId":1,"quantity":3}]}'
```

---

## Docker

### Full local stack (API + MySQL) with Compose

[`docker-compose.yml`](docker-compose.yml) runs the API from the Dockerfile plus **MySQL 8.4**. MySQL loads [`db/schema.sql`](db/schema.sql) on first start (tables + seed data); the API waits for MySQL's health check before starting. All secrets come from `.env`.

```bash
cp .env.example .env          # set MYSQL_ROOT_PASSWORD (DB_PASSWORD should match it)
docker compose up -d --build
docker compose ps             # both containers report (healthy)
curl http://localhost:8080/api/v1/products
```

| Service | Host port | Notes |
|---|---|---|
| `api` | 8080 | built from `./Dockerfile`; `DB_URL=jdbc:mysql://mysql:3306/…` |
| `mysql` | **3307** | so it doesn't clash with a MySQL already on 3306; data in the `mysql-data` volume |

`docker compose down` keeps the data; `docker compose down -v` wipes it so `schema.sql` runs again on the next `up`. With the stack running, `./mvnw spring-boot:run` on the host also works — the default `.env.example` points `DB_URL` at `localhost:3307`.

### Image only

A multi-stage [`Dockerfile`](Dockerfile) builds with Temurin 21 JDK, extracts Spring Boot layers, and runs on a Temurin 21 JRE (Alpine) as an unprivileged user (`uid 10001`) with a `HEALTHCHECK` on `/actuator/health`.

```bash
docker build -t ecommerce-api .

docker run -d -p 8080:8080 \
  -e DB_URL=jdbc:mysql://host.docker.internal:3306/ecommerce_db \
  -e DB_USER=root -e DB_PASSWORD=... \
  ecommerce-api
```

- Tests are **not** run in the image build (they need a live MySQL) — run `./mvnw verify` in CI first.
- Dependencies (~62 MB) and application code (~150 kB) are separate layers, so a code change only re-pushes the small one.
- JVM flags live in `JAVA_TOOL_OPTIONS` (`MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`) and can be overridden with `-e`.

---

## Configuration

All configuration lives in [`src/main/resources/application.properties`](src/main/resources/application.properties). Secrets are **never** committed — they come from environment variables, which locally are read from a git-ignored `.env` file.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/ecommerce_db` | JDBC URL (`.env.example` uses `:3307`, the Compose MySQL) |
| `DB_USER` | `root` | DB user |
| `DB_PASSWORD` | *(none — required)* | DB password. Startup fails fast if unset. |
| `MYSQL_DATABASE`, `MYSQL_ROOT_PASSWORD` | — | Compose only: passed to the MySQL container; the API's `DB_PASSWORD` is derived from `MYSQL_ROOT_PASSWORD`. |

Other notable settings:

| Property | Value | Why |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `validate` | Tables come from `schema.sql`; Hibernate only checks the mapping matches. |
| `spring.jpa.open-in-view` | `false` | No lazy loading in the web layer; services shape the response. |
| `spring.jpa.show-sql` | `true` | Dev convenience — turn off (or move to a dev profile) before production. |
| `springdoc.paths-to-match` | `/api/v1/**` | Only the public API appears in Swagger. |

---

## API reference

All endpoints are prefixed `/api/v1`. Conventions: `POST` → `201 Created` with body, `GET`/`PUT` → `200 OK`, `DELETE` → `204 No Content`. List endpoints return a `PageResponse<T>` (`content, page, size, totalElements, totalPages, last`) and accept `page`, `size` and `sort` query params, except categories which returns a plain array.

### Categories — `/api/v1/categories`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/` | Create. `name` and `slug` must be unique (409 otherwise). |
| `GET` | `/` | All categories (unpaged). |
| `GET` | `/{id}` | |
| `GET` | `/slug/{slug}` | |
| `PUT` | `/{id}` | |
| `DELETE` | `/{id}` | 422 if any product belongs to it. |

### Products — `/api/v1/products`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/` | Create. `sku` unique (409). `categoryId` must exist (404). |
| `GET` | `/` | Paged, default sort `name`. Filters: `?categoryId=`, `?activeOnly=true`. |
| `GET` | `/{id}` | |
| `GET` | `/sku/{sku}` | |
| `PUT` | `/{id}` | Can move product to another category. |
| `DELETE` | `/{id}` | 422 if the product is on any order or has reviews — deactivate instead. |

### Customers — `/api/v1/customers`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/` | Create. `email` unique (409). |
| `GET` | `/` | Paged, default sort `lastName`. |
| `GET` | `/{id}` | |
| `GET` | `/email/{email}` | |
| `PUT` | `/{id}` | |
| `DELETE` | `/{id}` | 422 if the customer has orders or reviews. |

### Orders — `/api/v1/orders`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/` | Place an order. Reserves stock, creates a `PENDING` payment. |
| `GET` | `/` | Paged, newest first. Filters: `?customerId=`, `?status=`. |
| `GET` | `/{id}` | Full order with items and payment. |
| `GET` | `/number/{orderNumber}` | e.g. `ORD-1A2B3C4D`. |
| `PUT` | `/{id}/status` | Body `{"status": "CONFIRMED"}`. Must be an allowed transition (422). |
| `POST` | `/{id}/cancel` | Shortcut for status → `CANCELLED`. Restores stock, refunds if paid. |
| `PUT` | `/{id}/payment` | Body `{"paymentStatus": "COMPLETED"}`. |

**Place-order request:**

```json
{
  "customerId": 1,
  "shippingAddress": "123 Maple Street, Springfield",
  "notes": "Leave at door",
  "paymentMethod": "UPI",
  "items": [
    { "productId": 1, "quantity": 3 },
    { "productId": 5, "quantity": 2 }
  ]
}
```

**Order response (abridged):**

```json
{
  "id": 8,
  "orderNumber": "ORD-567C5958",
  "customerId": 1,
  "customerName": "John Doe",
  "status": "PENDING",
  "totalAmount": 139.97,
  "items": [
    { "productId": 1, "productName": "Wireless Mouse", "sku": "ELEC-MOU-001",
      "quantity": 3, "unitPrice": 24.99, "subtotal": 74.97 }
  ],
  "payment": { "paymentMethod": "UPI", "paymentStatus": "PENDING", "amount": 139.97 },
  "createdAt": "2026-09-13T05:19:27"
}
```

### Product reviews — `/api/v1/products/{productId}/reviews`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/` | Body `{"customerId": 1, "rating": 5, "comment": "..."}`. Rating 1–5. Customer must have a **DELIVERED** order containing this product (422 otherwise). |
| `GET` | `/` | Paged, newest first. |

### Enums

| Enum | Values |
|---|---|
| `OrderStatus` | `PENDING`, `CONFIRMED`, `PROCESSING`, `SHIPPED`, `DELIVERED`, `CANCELLED`, `REFUNDED` |
| `PaymentStatus` | `PENDING`, `COMPLETED`, `FAILED`, `REFUNDED` |
| `PaymentMethod` | `CREDIT_CARD`, `DEBIT_CARD`, `UPI`, `NET_BANKING`, `WALLET`, `CASH_ON_DELIVERY` |

---

## Business rules

These are enforced in the service layer and covered by unit tests.

### Orders

- **Order numbers** are generated on persist as `ORD-` + 8 uppercase hex characters (from a UUID), unique per order.
- **Stock is reserved when the order is placed**, not at shipment. Each line checks `stockQuantity >= quantity`; on failure the whole order is rejected with 422 and nothing is decremented.
- Inactive products cannot be ordered (422).
- `OrderItem.subtotal` = `unitPrice × quantity`, computed automatically; `Order.totalAmount` is the sum of subtotals. Unit price is snapshotted from the product at order time.
- Saving an order cascades to its items and payment (`CascadeType.ALL`).
- A **low-stock warning** is logged for any product left with fewer than 10 units after an order.

### Order status transitions (one-way)

```
PENDING ──► CONFIRMED ──► PROCESSING ──► SHIPPED ──► DELIVERED
   │             │              │
   └─────────────┴──────────────┴──► CANCELLED
```

`DELIVERED`, `CANCELLED` and `REFUNDED` are terminal. Any other move returns 422 with the allowed targets in the message.

- **Cancelling** an order (from any pre-`SHIPPED` state) restores stock for every line and, if the payment was `COMPLETED`, marks it `REFUNDED`.

### Payments

```
PENDING ──► COMPLETED ──► REFUNDED
   └──────► FAILED
```

- `REFUNDED` cannot be set directly through the API — it only happens via order cancellation.
- Payment status cannot change once the order is in a terminal state.

### Reviews

- A customer may review a product only if they have a `DELIVERED` order that contains it.
- Rating is 1–5 (validated at the API and by a `CHECK` constraint in the DB).

### Deletion guards

Categories with products, products on orders or with reviews, and customers with orders or reviews cannot be deleted (422). Deactivate products instead.

---

## Error responses

Every error — validation, business rule, not found, unexpected — comes back as the same JSON shape from `GlobalExceptionHandler`:

```json
{
  "timestamp": "2026-09-13T05:19:27.456812",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Insufficient stock for product 'ELEC-HDP-004': requested 50, available 9",
  "path": "/api/v1/orders"
}
```

Validation failures list every field problem:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/v1/products/1/reviews",
  "fieldErrors": [
    { "field": "rating",  "rejectedValue": 6,  "message": "must be less than or equal to 5" },
    { "field": "comment", "rejectedValue": "", "message": "must not be blank" }
  ]
}
```

| Situation | Status |
|---|---|
| Bean validation failure, malformed JSON | `400 Bad Request` |
| Resource not found (`ResourceNotFoundException`) | `404 Not Found` |
| Duplicate name / slug / SKU / email (`DuplicateResourceException`) | `409 Conflict` |
| Business rule violated (`BusinessRuleException`) | `422 Unprocessable Entity` |
| Anything unexpected | `500` with a generic message; details go to the log only |

---

## Project layout

```
src/main/java/com/manish/ecommerce/api/
├── controller/   thin REST controllers — DTOs in, DTOs out, no logic
├── service/      all business rules, @Transactional boundaries, entity→DTO mapping
├── repository/   Spring Data JPA interfaces (@EntityGraph on detail/list loads)
├── entity/       JPA entities + OrderStatus / PaymentStatus state machines
├── dto/          request/response records (@Data @Builder), PageResponse<T>, ErrorResponse
├── exception/    the four domain exceptions + GlobalExceptionHandler
└── config/       OpenApiConfig
```

Conventions (also encoded in [`CLAUDE.md`](.claude/CLAUDE.md) and [`.claude/rules/`](.claude/rules)):

- Constructor injection via `@RequiredArgsConstructor` + `final` fields; no `@Autowired`.
- Entities: `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor` — never `@Data`.
- Money is always `BigDecimal`.
- Entities never leave the service layer; controllers only see DTOs.
- Class-level `@Transactional(readOnly = true)` on services, overridden per write method.

---

## Testing & coverage

```bash
./mvnw test            # unit + web-layer tests (needs DB_PASSWORD for the context-load test)
./mvnw clean verify    # same, plus JaCoCo report → target/site/jacoco/index.html
```

| Layer | Style | Count |
|---|---|---|
| Services | `@ExtendWith(MockitoExtension.class)`, repositories mocked | 64 |
| Controllers | `@WebMvcTest` + `MockMvc`, services mocked via `@MockitoBean` | 64 |
| Context | `@SpringBootTest` `contextLoads` — validates entity mapping against the live schema | 1 |

Baseline coverage: **93.7% line / 75.0% branch** (Lombok-generated code excluded via `lombok.config`).

Test conventions: `should_[expected]_when_[condition]` names, `// Arrange / Act / Assert` comments, AssertJ only.

> Spring Boot 4 note: `@WebMvcTest` lives in `org.springframework.boot.webmvc.test.autoconfigure`, and `@MockBean` no longer exists — use `org.springframework.test.context.bean.override.mockito.MockitoBean`.

---

## Database schema

Seven tables, defined in [`db/schema.sql`](db/schema.sql):

```
categories ──< products ──< order_items >── orders ──< payments
                  │                           │
                  └──< product_reviews >──────┘── customers
```

| Table | Key constraints |
|---|---|
| `categories` | unique `name`, unique `slug` |
| `products` | unique `sku`, FK `category_id`, `DECIMAL(10,2)` price |
| `customers` | unique `email` |
| `orders` | unique `order_number`, FK `customer_id`, `DECIMAL(12,2)` total |
| `order_items` | FK `order_id`, FK `product_id`, snapshotted `unit_price` |
| `payments` | unique FK `order_id` (one payment per order) |
| `product_reviews` | FK `product_id`, FK `customer_id`, `CHECK (rating BETWEEN 1 AND 5)` |

Schema changes are currently applied by hand. Adding a table means: edit `schema.sql`, apply the `CREATE TABLE` to your local DB, add the entity — `contextLoads` will fail until all three agree.

---

## Claude Code tooling

The repo ships with a small amount of [Claude Code](https://claude.com/claude-code) configuration under [`.claude/`](.claude):

| Path | What |
|---|---|
| `CLAUDE.md`, `rules/` | Project conventions the assistant follows |
| `commands/` | `/check-transactions`, `/find-n-plus-one`, `/verify-dto-coverage` — focused audits |
| `skills/production-readiness/` | `/production-readiness` — full PASS/WARN/FAIL scorecard with a bundled scanner script |
| `hooks/guardrail.sh` | Blocks automated edits to `.env*`, keystores, `db/schema.sql` and build infra |
| `settings.json` | Registers the guardrail for every contributor |

None of it is required to build or run the application.

---

## Known gaps / roadmap

Findings from the last `/production-readiness` run, in priority order:

1. **No dev/prod profile split** — `show-sql=true` and public Swagger UI apply everywhere. Move dev settings to `application-dev.properties`.
2. **N+1 on paged `GET /orders`** — customer, items, products and payment load lazily per row. Add `@EntityGraph` for to-one associations and `hibernate.default_batch_fetch_size=20`.
3. **Unparseable enum query params return 500** (`?status=BOGUS`) — add a `MethodArgumentTypeMismatchException` handler → 400.
4. **No migration tool** — `schema.sql` can't be run against a live DB. Flyway baseline recommended.
5. **Stock race** — check-then-decrement without locking; add `@Version` to `Product` or a `PESSIMISTIC_WRITE` read.
6. No authentication — `AccessDeniedException`/403 is wired but nothing throws it yet.

---

## License

MIT
