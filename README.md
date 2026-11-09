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
