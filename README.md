# LabBank S3 — Payment Orchestration Service

سرویس هماهنگ‌سازی پرداخت LabBank. این سرویس درخواست‌های انتقال وجه را می‌پذیرد،
ارزیابی ریسک (S4)، بلوکه‌سازی وجه (S2)، احراز حساب (S1) و تسویه خارجی (S5) را
هماهنگ می‌کند و وضعیت نهایی را به فرانت‌اند برمی‌گرداند.

> **توجه:** با تأیید استاد درس، ارتباط بین‌سرویسی به‌جای REST با **Kafka** پیاده‌سازی
> می‌شود. مستندسازی در ADR-0001. API رو‌به‌فرانت‌اند همچنان REST/OpenAPI است.

## معماری

```
Frontend (React)
    │  REST (OpenAPI)
    ▼
S3 Payment (Spring Boot) ──┬── Kafka → S1 (Account)
    │  PostgreSQL           ├── Kafka → S2 (Ledger)
    │                       ├── Kafka → S4 (Risk)
    │                       └── Kafka → S5 (Settlement)
```

## پورت‌ها

| سرویس | پورت | توضیح |
|-------|------|-------|
| S3 App | 8083 | API رو‌به‌فرانت‌اند + Swagger UI |
| PostgreSQL | 5432 | پایگاه‌داده مستقل S3 |
| Kafka | 9092 | Broker (KRaft mode، بدون Zookeeper) |
| Swagger UI | 8088 | نمایش OpenAPI |
| Frontend | 3000 / 5173 | Docker / Vite dev |

## شروع سریع

### با Docker Compose (پیشنهادی)

```bash
cp env.example .env
docker compose up -d --build
```

> **نکته‌ی شبکه (ایران):** اگر هنگام `docker compose up --build` خطای
> `Remote host terminated the handshake` یا `SSL peer shut down incorrectly`
> هنگام دانلود از `repo.maven.apache.org` گرفتید، کافی است `Dockerfile`
> پروژه را همان‌طور که هست استفاده کنید. این پروژه از mirrorهای داخلی
> قابل‌دسترس از ایران استفاده می‌کند:
>
> - **Maven** (Backend): فایل [`.mvn-settings.xml`](.mvn-settings.xml) با
>   سه mirror: Aliyun → Huawei Cloud → Tencent Cloud.
> - **npm** (Frontend): فایل [`frontend/.npmrc`](frontend/.npmrc) با mirror
>   `https://registry.npmmirror.com` (تابعه‌ی علی‌بابا).
> - **Docker images**: برای pull کردن imageهای رسمی (postgres, kafka, node,
>   nginx, ...) باید `registry-mirrors` را در Docker Desktop اضافه کنید.
>   راهنمای کامل در [`MIRRORS.md`](MIRRORS.md).
>
> هیچ کار اضافه‌ای برای Maven و npm نیاز نیست؛ فقط برای Docker images اگر
> pull شکست می‌خورد، `MIRRORS.md` را بخوانید.


سپس:

```bash
# سلامت سرویس
curl http://localhost:8083/health

# آمادگی (DB + Kafka)
curl http://localhost:8083/ready

# ساخت یک انتقال داخلی
curl -X POST http://localhost:8083/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-key-001" \
  -H "X-Correlation-ID: $(uuidgen)" \
  -d '{"source_account_number":"001200456789","dest_account_number":"001200987654","amount":2500000,"currency":"IRR"}'

# Swagger UI
open http://localhost:8088
```

### محلی (بدون Docker)

```bash
# 1. PostgreSQL و Kafka را راه‌اندازی کنید (docker compose up db kafka kafka-init)
# 2. برنامه را اجرا کنید:
DB_HOST=localhost KAFKA_BOOTSTRAP_SERVERS=localhost:9092 mvn spring-boot:run
```

## قراردادها

### AsyncAPI (ارتباط بین‌سرویسی Kafka)

| فایل | همسایه | نقش S3 | topicهای اصلی |
|------|--------|--------|---------------|
| `asyncapi/asyncS1.yaml` | S1 Account | Consumer | `lab.s3.s1.command.authorization.check.v2` |
| `asyncapi/asyncS2.yaml` | S2 Ledger | Consumer | `lab.s3.s2.command.funds.hold.v3` و ... |
| `asyncapi/asyncS4.yaml` | S4 Risk | **Consumer (حلقه)** | `lab.s3.s4.command.risk.assess.v1` |
| `asyncapi/asyncS5.yaml` | S5 Settlement | **Provider (حلقه)** | `lab.s3.s5.command.transfer.external.v3` |

اعتبارسنجی:

```bash
npx @asyncapi/cli validate asyncapi/asyncS4.yaml
```

### OpenAPI (API رو‌به‌فرانت‌اند)

| فایل | کاربرد |
|------|--------|
| `openapi/Payment.yaml` | REST API فرانت‌اند (POST /transfers, GET /transfers/{id}, ...) |
| `redocly.yaml` | تنظیمات lint |

اعتبارسنجی:

```bash
npx @redocly/cli lint openapi/Payment.yaml
```

## حلقه‌ی جلسه ۴

| نقش | قرارداد | Endpoint (REST) → Topic (Kafka) |
|-----|---------|--------------------------------|
| Provider | S3↔S5 | `POST /settlement-callbacks` → `lab.s3.s5.command.transfer.external.v3` |
| Consumer | S4→S3 | `POST /assessments` → `lab.s3.s4.command.risk.assess.v1` |

## برای تیم همسایه (For the neighbor team — Provider side, Section 7)

> این بخش برای تیم‌های همسایه‌ای است که می‌خواهند وضعیت یک انتقال را از S3
> بخوانند. این Endpoint فقط‌خواندنی، REST و بدون احراز هویت داخلی است و در
> شاخه `main` مرج شده. بعد از `docker compose up --build -d` روی پورت 8083
> در دسترس است.
>
> This section is for neighbor teams that need to query the status of a
> transfer from S3. The endpoint is read-only, REST, available on `main`, and
> reachable on port **8083** after `docker compose up --build -d`.

### Endpoint

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/transfers/{id}/status` | Returns a minimal status snapshot of one transfer |

### Request

- Path parameter `id` — UUID of the transfer (one of the seed identifiers below).
- No headers required. `X-Correlation-ID` is optional and propagated to logs.

### Responses

- `200 OK` — body:

  ```json
  {
    "id": "0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11",
    "status": "SETTLED",
    "is_internal": true,
    "amount": 2500000,
    "currency": "IRR",
    "external_reference": null,
    "updated_at": "2026-11-01T08:00:05Z"
  }
  ```

- `404 NOT_FOUND` — `application/problem+json`:

  ```json
  {
    "type": "https://errors.labbank.example/problem/not_found",
    "title": "Resource not found",
    "status": 404,
    "code": "NOT_FOUND",
    "detail": "Transfer not found: <uuid>",
    "correlation_id": "<uuid>"
  }
  ```

### Seed identifiers

`docker compose up --build -d` automatically applies the Flyway migration
`V2__seed_neighbor_data.sql`, so the following deterministic UUIDs are always
present and can be hard-coded in the neighbor team's tests:

| UUID | Status | Notes |
|------|--------|-------|
| `0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11` | `SETTLED` | internal transfer, happy path |
| `0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c12` | `SETTLED` | external transfer with `external_reference=S5-DEMO-REFERENCE-001` |
| `0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c13` | `RISK_REJECTED` | rejected by S4 |
| `0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c14` | `RISK_CHECKING` | pending transfer |

### Example calls

```bash
# Success
curl -sS http://localhost:8083/api/v1/transfers/0192a7e4-6b1d-7c3e-9a41-2f5d8e0b7c11/status

# Error: unknown id → 404 problem+json
curl -sS -i http://localhost:8083/api/v1/transfers/00000000-0000-4000-8000-000000000000/status
```

### Provider-side API tests (committed to `main`)

Class: `edu.softwarelab.starter.api.NeighborTransferStatusControllerMvcTest`

| Test name | Path | Expected |
|-----------|------|----------|
| `getTransferStatusReturns200WithMinimalSnapshotForSeededTransfer` (success) | `GET /api/v1/transfers/{id}/status` | `200 OK` with `{id, status, is_internal, amount, currency, external_reference, updated_at}` |
| `getTransferStatusReturns404ProblemForUnknownId` (error) | `GET /api/v1/transfers/{id}/status` | `404 NOT_FOUND` with `application/problem+json` and `code=NOT_FOUND` |

Run them with:

```bash
mvn -Dtest=NeighborTransferStatusControllerMvcTest test
```

### OpenAPI

The endpoint is also described in [`openapi/Payment.yaml`](openapi/Payment.yaml)
under `GET /transfers/{id}/status`.

## تست‌ها

```bash
mvn test
```

تست‌ها:
- `TransferStatusTest`: اعتبارسنجی enumها (۸ تست)
- `S4ResultConsumerTest`: ماشین حالت consumer با Mockito (۷ تست)

## فرانت‌اند

```bash
cd frontend
npm install
npm run gen:api    # تولید TypeScript types از openapi/Payment.yaml
npm run dev        # http://localhost:5173
```

## ساختار پروژه

```
.
├── asyncapi/           # قراردادهای AsyncAPI (Kafka بین‌سرویسی)
│   ├── asyncS1.yaml    # S3↔S1 احراز حساب
│   ├── asyncS2.yaml    # S3↔S2 دفترکل
│   ├── asyncS4.yaml    # S3↔S4 ریسک (Consumer حلقه) ⭐
│   └── asyncS5.yaml    # S3↔S5 تسویه (Provider حلقه) ⭐
├── openapi/
│   └── payment.yaml    # API رو‌به‌فرانت‌اند
├── redocly.yaml        # تنظیمات lint OpenAPI
├── docs/
│   ├── adr/
│   │   └── ADR-0001-kafka-vs-rest.md  # تصمیم معماری
│   ├── sequence-diagram-s3-s4.md      # نمودار تعاملی Consumer
│   └── sequence-diagram-s3-s4.png     # تصویر رندرشده
├── bruno/
│   └── bruno_collection.json  # درخواست‌های نمونه Bruno
├── src/main/java/edu/softwarelab/starter/
│   ├── api/            # REST controllers + exception handler
│   ├── config/         # Kafka config + CorrelationId filter
│   ├── domain/         # Entity, Repository, enums
│   ├── exception/      # Problem Details
│   ├── messaging/      # Kafka producers/consumers + DTOs
│   └── service/        # TransferService (orchestration)
├── src/main/resources/
│   ├── application.properties
│   ├── schema.sql      # DDL
│   └── data.sql        # Seed data
├── src/test/java/      # تست‌ها
├── frontend/           # React + Vite + openapi-fetch
├── docker-compose.yml  # PostgreSQL + Kafka + App + Swagger + Frontend
├── env.example
└── pom.xml
```

## استانداردهای رعایت‌شده

| الزام | وضعیت |
|-------|-------|
| GEN-01: OpenAPI/AsyncAPI | ✅ AsyncAPI برای Kafka، OpenAPI برای فرانت‌اند |
| GEN-02: مسیر /api/v1 | ✅ |
| GEN-03: مبلغ integer ریال | ✅ |
| GEN-04: UUID | ✅ |
| GEN-05: زمان UTC ISO 8601 | ✅ |
| GEN-06: Problem Details (RFC 9457) | ✅ |
| GEN-07: Idempotency-Key | ✅ جدول idempotency_keys |
| GEN-08: X-Correlation-ID | ✅ CorrelationIdFilter + MDC |
| GEN-10: /health + /ready | ✅ |
| GEN-11: لاگ JSON | ✅ |
| GEN-12: PostgreSQL مستقل | ✅ |
| GEN-13: شبا IR | ✅ |
| GEN-15: پورت 8083 | ✅ |


## Database migrations and Kafka acknowledgement safety

This project uses Spring Boot 4.x. The Maven build must include
`spring-boot-starter-flyway` in addition to `flyway-core` and
`flyway-database-postgresql`: Boot 4 moved Flyway auto-configuration into its
own module, so the Flyway engine dependencies alone do not activate migrations.
The application is expected to fail startup if the configured migration cannot
run; do not create the outbox/inbox tables manually or delete the PostgreSQL
volume to work around a migration error.

Kafka result listeners use `AckMode.MANUAL`, not `MANUAL_IMMEDIATE`. The
acknowledgement is queued by the listener and committed by the container after
the listener invocation returns, allowing the listener's database transaction
to commit before the Kafka offset is committed.
