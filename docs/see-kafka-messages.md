# راهنمای ساده: دیدن پیام‌ها در Kafka

این راهنما فقط یک هدف دارد: **یک پیام از سرویس S3 به topic برود و شما بتوانید آن را
ببینید.** بدون شبیه‌سازی، بدون پیچیدگی.

---

## مرحله ۱: زیرساخت را بالا بیاورید

```bash
cd starter-java
cp env.example .env        # فقط یک بار
docker compose up -d
```

صبر کنید تا همه containerها بالا بیایند (حدود ۳۰ ثانیه). بررسی:

```bash
docker compose ps
```

باید ببینید:
```
s3-app        Up   8083
s3-db         Up   5432
s3-kafka      Up   9092
s3-kafka-init exited 0     # این باید exited باشد یعنی topicها ساخته شده
```

اگر `s3-kafka-init` هنوز `Up` است، چند ثانیه صبر کنید. اگر `exited 1` شد، logs را
ببینید: `docker logs s3-kafka-init`.

---

## مرحله ۲: لیست همه topicها را ببینید

```bash
docker exec s3-kafka kafka-topics --bootstrap-server localhost:29092 --list
```

باید این topicها را ببینید:

```
lab.s1.s3.event.operation-result.v2
lab.s2.s3.event.operation-result.v3
lab.s3.s1.command.authorization.check.v2
lab.s3.s2.command.funds.capture.v3
lab.s3.s2.command.funds.hold.v3
lab.s3.s2.command.funds.release.v3
lab.s3.s2.command.operation.inquire.v3
lab.s3.s2.command.transfer.internal.v3
lab.s3.s2.command.transfer.reverse.v3
lab.s3.s4.command.risk.assess.v1        ← S3 به این پیام می‌فرستد
lab.s3.s5.command.transfer.external.v3  ← S3 به این پیام می‌فرستد (خارجی)
lab.s3.s5.command.transfer.inquire.v3
lab.s4.s3.event.operation-result.v1     ← S4 به این پاسخ می‌دهد
lab.s5.s3.event.operation-result.v3     ← S5 به این پاسخ می‌دهد
```

---

## مرحله ۳: یک consumer روی topic باز کنید (ترمینال ۱)

یک ترمینال جدا باز کنید و این را اجرا کنید تا روی topic `AssessRisk` گوش دهد:

```bash
docker exec -it s3-kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic lab.s3.s4.command.risk.assess.v1 \
  --from-beginning \
  --property print.key=true
```

این دستور منتظر می‌ماند و هر پیامی که به این topic بیاید را به‌صورت زنده چاپ می‌کند.
ترمینال را باز نگه دارید. (هنوز چیزی چاپ نشده چون کسی پیام نفرستاده.)

---

## مرحله ۴: یک انتقال از API بسازید (ترمینال ۲)

ترمینال دوم باز کنید:

```bash
curl -X POST http://localhost:8083/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-001" \
  -H "X-Correlation-ID: $(uuidgen 2>/dev/null || python3 -c 'import uuid;print(uuid.uuid4())')" \
  -d '{
    "source_account_number": "001200456789",
    "dest_account_number": "001200987654",
    "amount": 2500000,
    "currency": "IRR",
    "description": "test message"
  }'
```

پاسخ:
```json
{
  "id": "abc123-...",
  "status": "PENDING",
  ...
}
```

**در همین لحظه، در ترمینال ۱، یک پیام JSON ظاهر می‌شود** — این همان پیام AssessRisk
است که سرویس S3 خودکار به topic فرستاده.

نمونه خروجی (فشرده شده):

```
abc123-...    {
  "message_id": "...",
  "schema_version": "1.0",
  "transaction_id": "abc123-...",
  "correlation_id": "...",
  "occurred_at": "2026-11-01T12:34:56.789Z",
  "producer": "S3-PAY",
  "message_type": "AssessRisk.v1",
  "operation_id": "...",
  "data": {
    "src_account_id": "...",
    "dest_account_id": "...",
    "amount": 2500000,
    "currency": "IRR",
    "additional_data": {
      "transfer_type": "INTERNAL",
      "channel": "API",
      "dest_iban": ""
    }
  }
}
```

---

## مرحله ۵: برای دیدن topicهای دیگر

برای دیدن پیام‌های انتقال خارجی (S3 → S5)، ابتدا باید یک انتقال خارجی بسازید.
اما در کد فعلی، S5 producer فقط برای انتقال خارجی فعال می‌شود و نیاز به صدا زدن
متد `sendExecuteExternalTransfer` دارد. برای تمرین جلسه ۴، topic `S3→S4` کافی است.

اگر خواستید topicهای دیگر را هم ببینید:

```bash
# topic پاسخ S4 (که S4 واقعی باید بفرستد — الان خالی است)
docker exec -it s3-kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic lab.s4.s3.event.operation-result.v1 \
  --from-beginning --property print.key=true

# topic پاسخ S5 (خالی)
docker exec -it s3-kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic lab.s5.s3.event.operation-result.v3 \
  --from-beginning --property print.key=true
```

برای بستن هر consumer: `Ctrl+C`

---

## خلاصه در ۳ دستور

```bash
# 1. زیرساخت بالا
docker compose up -d

# 2. (ترمینال جدا) consumer باز کن
docker exec -it s3-kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic lab.s3.s4.command.risk.assess.v1 \
  --from-beginning --property print.key=true

# 3. (ترمینال جدا) یک پیام تولید کن
curl -X POST http://localhost:8083/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-001" \
  -d '{"source_account_number":"001200456789","dest_account_number":"001200987654","amount":2500000,"currency":"IRR"}'
```

پیام در ترمینال ۲ ظاهر می‌شود. تمام!

---

## عیب‌یابی

### "No messages" در consumer

- مطمئن شوید `--from-beginning` را زده‌اید (پیام‌های قدیمی را هم نشان می‌دهد)
- مطمئن شوید درخواست curl را **بعد از** باز کردن consumer زده‌اید
- لاگ s3-app را ببینید: `docker logs --tail 50 s3-app | grep -i kafka`
  - اگر خطای "Brokers not available" دیدید: صبر کنید Kafka کاملاً بالا بیاید
  - اگر خطای "Topic not found" دیدید: `docker logs s3-kafka-init` را ببینید

### curl خطا می‌دهد "Connection refused"

- سرویس app هنوز بالا نیامده: `docker compose logs app | tail -30`
- پورت 8083 اشغال است: `lsof -i :8083` (macOS/Linux)

### "Trusted package" خطا

این خطا مربوط به consumer است نه producer. اگر فقط می‌خواهید پیام ببینید، با
`kafka-console-consumer` مشکلی نیست چون از `StringDeserializer` پیش‌فرض استفاده می‌کند.

### ریست کامل

اگر همه چیز خراب شد:

```bash
docker compose down -v       # حذف volume ها (دیتابیس پاک می‌شود)
docker compose up -d --build
```
