# راهنمای تست Messaging و نتایج

این سند چهار روش برای تست بخش Kafka messaging سرویس S3 را توضیح می‌دهد، از ساده‌ترین
(اجرای محلی) تا کامل‌ترین (تست اتوماتیک با Testcontainers).

## فهرست روش‌ها

| روش | سرعت | پوشش | برای چه زمانی |
|-----|------|------|---------------|
| ۱. تست دستی با console-producer | سریع | یک مسیر | دیباگ سریع روی لپ‌تاپ |
| ۲. اسکریپت `test-messaging.sh` | سریع | چند مسیر | تمرین جلسه ۴، دمو کلاسی |
| ۳. Bruno + شبیه‌سازی | متوسط | کلید موفق | تست API رو‌به‌فرانت‌اند |
| ۴. تست اتوماتیک با Testcontainers | کند | همه مسیرها | CI/CD، اطمینان کامل |

---

## روش ۱: اجرای محلی + شبیه‌سازی دستی

### مرحله ۱: بالا آوردن زیرساخت

```bash
cd starter-java
cp env.example .env
docker compose up -d --build
```

بررسی:

```bash
docker compose ps          # همه باید Up باشند
curl http://localhost:8083/ready
# {"status":"ok","dependencies":{"db":"up","kafka":"up"}}
```

### مرحله ۲: ساخت یک انتقال از طریق API

```bash
curl -X POST http://localhost:8083/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: manual-test-001" \
  -H "X-Correlation-ID: $(uuidgen)" \
  -d '{
    "source_account_number": "001200456789",
    "dest_account_number": "001200987654",
    "amount": 2500000,
    "currency": "IRR",
    "description": "manual test"
  }'
```

پاسخ ۲۰۱ می‌گیرید با یک `id` (UUID). آن را یادداشت کنید.

**چه اتفاقی افتاد؟** S3:
1. transfer را در PostgreSQL با `status=PENDING` ذخیره کرد
2. پیام `AssessRisk` را در topic `lab.s3.s4.command.risk.assess.v1` تولید کرد
3. `status` را به `RISK_CHECKING` تغییر داد

### مرحله ۳: مشاهده پیام تولیدشده

```bash
docker exec -it s3-kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic lab.s3.s4.command.risk.assess.v1 \
  --from-beginning \
  --property print.key=true \
  --property print.headers=true
```

خروجی (نمونه):

```
[header: authorization = Bearer EXAMPLE]
54e8c94a-f952-4e24-8541-55f93b889ed9	{"message_id":"...","schema_version":"1.0","transaction_id":"54e8c94a-...","message_type":"AssessRisk.v1","data":{"src_account_id":"...","amount":2500000,...}}
```

`Ctrl+C` بزنید تا بسته شود.

### مرحله ۴: شبیه‌سازی پاسخ S4 (APPROVED)

چون سرویس S4 واقعی نداریم، خودمان یک پاسخ شبیه‌سازی‌شده می‌فرستیم:

```bash
TXN_ID="<همان UUID که از API گرفتید>"

docker exec -i s3-kafka kafka-console-producer \
  --bootstrap-server localhost:29092 \
  --topic lab.s4.s3.event.operation-result.v1 \
  --property "key=${TXN_ID}" \
  --property "parse.key=true" <<EOF
{
  "message_id": "$(uuidgen)",
  "schema_version": "1.0",
  "transaction_id": "${TXN_ID}",
  "correlation_id": "$(uuidgen)",
  "causation_id": "$(uuidgen)",
  "occurred_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "producer": "S4-RSK",
  "message_type": "AssessmentResult.v1",
  "operation_id": "$(uuidgen)",
  "outcome": "APPLIED",
  "data": {
    "decision": "APPROVED",
    "risk_score": 12,
    "rules_evaluated": ["DAILY_LIMIT_OK", "VELOCITY_OK"],
    "assessed_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  }
}
EOF
```

### مرحله ۵: بررسی نتیجه

```bash
# از طریق API
curl http://localhost:8083/api/v1/transfers/${TXN_ID} | jq

# از طریق DB
docker exec -it s3-db psql -U postgres -d paymentdb \
  -c "SELECT id, status, risk_decision, risk_score FROM transfers WHERE id='${TXN_ID}';"

# از طریق لاگ‌های s3-app
docker logs --tail 30 s3-app | grep -E "S4|risk"
```

**انتظار:** `status=HOLDING`، `risk_decision=APPROVED`، `risk_score=12`، و یک رویداد
`RISK_ASSESSED` در جدول `transfer_events`.

### مرحله ۶: تست مسیرهای خطا

برای تست مسیر `REJECTED`:

```bash
docker exec -i s3-kafka kafka-console-producer \
  --bootstrap-server localhost:29092 \
  --topic lab.s4.s3.event.operation-result.v1 \
  --property "key=${TXN_ID}" --property "parse.key=true" <<EOF
{"message_id":"$(uuidgen)","schema_version":"1.0","transaction_id":"${TXN_ID}","correlation_id":"$(uuidgen)","causation_id":"$(uuidgen)","occurred_at":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","producer":"S4-RSK","message_type":"AssessmentResult.v1","operation_id":"$(uuidgen)","outcome":"APPLIED","data":{"decision":"REJECTED","risk_score":87,"rules_evaluated":["BLOCKLISTED_DESTINATION"],"assessed_at":"$(date -u +%Y-%m-%dT%H:%M:%SZ)"}}
EOF
```

**انتظار:** `status=RISK_REJECTED`.

---

## روش ۲: اسکریپت `test-messaging.sh`

اسکریپت آماده در `scripts/test-messaging.sh` تمام کارهای بالا را خودکار می‌کند.

### راهنما

```bash
./scripts/test-messaging.sh
```

### دموی کامل end-to-end (یک دستور)

```bash
./scripts/test-messaging.sh e2e
```

این دستور:
1. یک انتقال داخلی از طریق API می‌سازد
2. پیام AssessRisk تولیدشده را نشان می‌دهد
3. پاسخ APPROVED را شبیه‌سازی می‌کند
4. وضعیت نهایی و رویدادها را نشان می‌دهد

### مشاهده topicها

```bash
# یک ترمینال جدا باز کنید:
./scripts/test-messaging.sh watch-s4       # پیام‌های AssessRisk که S3 تولید می‌کند
./scripts/test-messaging.sh watch-s4-in    # پاسخ‌های S4 که S3 مصرف می‌کند
./scripts/test-messaging.sh watch-s5       # پیام‌های ExecuteExternalTransfer
./scripts/test-messaging.sh watch-s5-in    # پاسخ‌های S5
```

### شبیه‌سازی مسیرهای مختلف

```bash
TXN_ID=$(./scripts/test-messaging.sh db | tail -20 | grep -oE '[0-9a-f-]{36}' | head -1)

./scripts/test-messaging.sh replay-s4-approved "$TXN_ID"     # → HOLDING
./scripts/test-messaging.sh replay-s4-rejected "$TXN_ID"     # → RISK_REJECTED
./scripts/test-messaging.sh replay-s4-manual   "$TXN_ID"     # → RISK_MANUAL_REVIEW
./scripts/test-messaging.sh replay-s4-error    "$TXN_ID"     # → FAILED (validation)

./scripts/test-messaging.sh replay-s5-settled  "$TXN_ID"     # → SETTLED
./scripts/test-messaging.sh replay-s5-accepted "$TXN_ID"     # → EXECUTING
./scripts/test-messaging.sh replay-s5-failed   "$TXN_ID"     # → FAILED
```

### مشاهده وضعیت DB

```bash
./scripts/test-messaging.sh db
```

---

## روش ۳: Bruno + شبیه‌سازی همزمان

این روش برای تست **API رو‌به‌فرانت‌اند** با همزمانی Kafka است.

### مرحله ۱: Bruno Collection را import کنید

۱. Bruno را از https://www.usebruno.com/downloads نصب کنید
۲. Import Collection → فایل `bruno/bruno_collection.json` را انتخاب کنید

### مرحله ۲: یک انتقال بسازید

درخواست "Create Internal Transfer (201)" را اجرا کنید. UUID بازگشتی را کپی کنید.

### مرحله ۳: در یک ترمینال جدا، پاسخ S4 را شبیه‌سازی کنید

```bash
./scripts/test-messaging.sh replay-s4-approved <UUID-از-مرحله-۲>
```

### مرحله ۴: در Bruno، GET Transfer را اجرا کنید

درخواست "Get Transfer by ID" را اجرا کنید (UUID را در URL جایگزین کنید). باید
`status: HOLDING` ببینید.

---

## روش ۴: تست اتوماتیک با Testcontainers

برای CI/CD و اطمینان کامل. `pom.xml` شامل `testcontainers-kafka` و
`testcontainers-postgresql` است.

### نمونه تست Integration

```java
package edu.softwarelab.starter.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class S4ResultConsumerIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("paymentdb")
        .withUsername("postgres")
        .withPassword("postgres");

    @Autowired
    KafkaTemplate<String, MessageEnvelope> kafkaTemplate;

    @Autowired
    TransferRepository transferRepository;

    @Test
    void shouldTransitionToHoldingWhenS4Approves() {
        // Arrange: create a transfer in DB
        UUID txnId = UUID.randomUUID();
        // ... save transfer with status=RISK_CHECKING ...

        // Act: send S4 reply on the topic
        MessageEnvelope envelope = MessageEnvelope.builder()
            .transactionId(txnId)
            .outcome("APPLIED")
            .data(new AssessmentResultData("APPROVED", 12, List.of("OK"), OffsetDateTime.now()))
            .build();
        kafkaTemplate.send("lab.s4.s3.event.operation-result.v1", txnId.toString(), envelope);

        // Assert: wait for consumer to update DB
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                Transfer t = transferRepository.findById(txnId).orElseThrow();
                assertThat(t.status()).isEqualTo(TransferStatus.HOLDING);
                assertThat(t.riskDecision()).isEqualTo("APPROVED");
            });
    }
}
```

### اجرای تست‌ها

```bash
mvn test
mvn test -Dtest=S4ResultConsumerIntegrationTest
mvn verify    # با JaCoCo coverage report
```

---

## جدول مرجع: مسیرها و انتظارات

### مسیرهای S3↔S4 (Risk Assessment)

| ورودی (پیام S4) | outcome | decision | انتظار status | رویداد ثبت‌شده |
|----------------|---------|----------|---------------|----------------|
| موفق | APPLIED | APPROVED | HOLDING | RISK_ASSESSED |
| رد شده | APPLIED | REJECTED | RISK_REJECTED | RISK_ASSESSED |
| بررسی دستی | APPLIED | MANUAL_REVIEW | RISK_MANUAL_REVIEW | RISK_ASSESSED |
| خطای اعتبارسنجی | REJECTED | — | FAILED | TRANSFER_FAILED |
| نامشخص | UNKNOWN | — | بدون تغییر | — |

### مسیرهای S3↔S5 (External Transfer)

| ورودی (پیام S5) | outcome | status | انتظار transfer status | رویداد |
|----------------|---------|--------|------------------------|--------|
| تسویه‌شده | APPLIED | SETTLED | SETTLED | TRANSFER_SETTLED |
| پذیرفته‌شده | APPLIED | ACCEPTED | EXECUTING | TRANSFER_EXECUTED |
| در حال پردازش | APPLIED | PROCESSING | EXECUTING | TRANSFER_EXECUTED |
| شکست‌خورده | APPLIED | FAILED | FAILED | TRANSFER_FAILED |
| نامشخص | APPLIED | UNKNOWN | FAILED | TRANSFER_FAILED |
| رد شده | REJECTED | — | FAILED | TRANSFER_FAILED |

---

## تست Idempotency

### تکرار همان Idempotency-Key با همان بدنه → همان پاسخ

```bash
# اولین درخواست
curl -X POST http://localhost:8083/api/v1/transfers \
  -H "Idempotency-Key: reuse-test-001" \
  -H "Content-Type: application/json" \
  -d '{"source_account_number":"001200456789","dest_account_number":"001200987654","amount":100000,"currency":"IRR"}'

# دومین درخواست با همان کلید و بدنه → همان پاسخ (همان UUID)
curl -X POST http://localhost:8083/api/v1/transfers \
  -H "Idempotency-Key: reuse-test-001" \
  -H "Content-Type: application/json" \
  -d '{"source_account_number":"001200456789","dest_account_number":"001200987654","amount":100000,"currency":"IRR"}'
```

### تکرار همان Idempotency-Key با بدنه متفاوت → 409

```bash
curl -i -X POST http://localhost:8083/api/v1/transfers \
  -H "Idempotency-Key: reuse-test-001" \
  -H "Content-Type: application/json" \
  -d '{"source_account_number":"001200456789","dest_account_number":"001200987654","amount":999999,"currency":"IRR"}'
# HTTP/1.1 409
# {"code":"IDEMPOTENCY_KEY_REUSED",...}
```

---

## تست timeout (مسیر no-response)

S4 واقعی پاسخ نمی‌دهد. S3 باید بعد از `s3.risk-assessment.timeout-seconds=10`
(در `application.properties`) timeout کند.

برای تست:

```bash
# یک انتقال بسازید
TXN_ID=$(curl -sS -X POST http://localhost:8083/api/v1/transfers \
  -H "Idempotency-Key: timeout-test" -H "Content-Type: application/json" \
  -d '{"source_account_number":"001200456789","dest_account_number":"001200987654","amount":100000,"currency":"IRR"}' \
  | jq -r .id)

# هیچ پاسخی از S4 نفرستید. ۱۱ ثانیه صبر کنید.
sleep 11

# بررسی وضعیت — باید MANUAL_REVIEW باشد
curl http://localhost:8083/api/v1/transfers/${TXN_ID} | jq .status
```

> **نکته:** در نسخه فعلی کد، منطق timeout هنوز به‌صورت کامل پیاده‌سازی نشده است
> (به‌عنوان TODO در `TransferService` مشخص است). برای تمرین جلسه ۴، می‌توانید
> این بخش را به‌عنوان یک تسک برای اسپرینت بعد ثبت کنید یا خودتان با
> `@Scheduled` پیاده‌سازی کنید.

---

## عیب‌یابی

### "Failed to fetch" در Swagger UI

- مطمئن شوید `servers.url` در `openapi/payment.yaml` با آدرس سرویس هم‌خوانی دارد
- اگر Swagger UI از docker container باز می‌شود، آدرس باید `http://localhost:8083/api/v1` باشد
- CORS: اگر از مرورگر استفاده می‌کنید، مطمئن شوید `CorrelationIdFilter` هدر CORS اضافه می‌کند

### پیام‌ها در topic نیستند

```bash
# لیست همه topicها
docker exec s3-kafka kafka-topics --bootstrap-server localhost:29092 --list

# بررسی گروه consumer
docker exec s3-kafka kafka-consumer-groups \
  --bootstrap-server localhost:29092 \
  --describe --group s3-payment-service
```

### consumer پیام‌ها را پردازش نمی‌کند

```bash
# لاگ‌های s3-app
docker logs -f s3-app | grep -E "S4|S5|Kafka|Error"

# بررسی اتصال consumer
docker exec s3-kafka kafka-consumer-groups \
  --bootstrap-server localhost:29092 \
  --describe --group s3-payment-service
```

### "Trusted package" خطای Deserialization

در `application.properties`:

```properties
spring.kafka.consumer.properties.spring.json.trusted.packages=edu.softwarelab.starter.messaging.dto
```

اگر پیام شما از package دیگری می‌آید، آن را اضافه کنید یا `*` بگذارید.

---

## چک‌لیست تست برای تمرین جلسه ۴

- [ ] `docker compose up -d` بدون خطا بالا می‌آید
- [ ] `GET /health` پاسخ ۲۰۰ می‌دهد
- [ ] `GET /ready` پاسخ ۲۰۰ می‌دهد (DB + Kafka up)
- [ ] `POST /api/v1/transfers` با بدنه معتبر → ۲۰۱ + UUID
- [ ] پیام `AssessRisk` در topic `lab.s3.s4.command.risk.assess.v1` ظاهر می‌شود
- [ ] شبیه‌سازی پاسخ APPROVED → `status=HOLDING`
- [ ] شبیه‌سازی پاسخ REJECTED → `status=RISK_REJECTED`
- [ ] شبیه‌سازی پاسخ MANUAL_REVIEW → `status=RISK_MANUAL_REVIEW`
- [ ] تکرار Idempotency-Key با بدنه متفاوت → ۴۰۹
- [ ] `GET /api/v1/transfers/{id}/events` رویدادها را برمی‌گرداند
- [ ] `mvn test` همه ۱۶ تست را سبز می‌کند
- [ ] Swagger UI روی `http://localhost:8088` باز می‌شود و Try it out کار می‌کند
