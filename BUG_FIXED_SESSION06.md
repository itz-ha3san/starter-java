# گزارش رفع باگ و بازآرایی — جلسه ششم (`BUG_FIXED_SESSION06.md`)

* **سرویس:** `S3-PAY` (Payment Orchestration Service)

* **مخزن تمرین جلسه ۶ (Fork):** `https://github.com/itz-ha3san/LabBank-S3-Session06`

* **اعضای تیم:** محمدحسن ضیغمی - عرفان رجبی - پارسا ماهر

## ۱. باگ اول — Backend: جا افتادن رویدادها در صفحه‌بندی Cursor-based هنگام یکسان بودن زمان رخداد (`occurred_at`)

### ۱.۱. رفتار اشتباه و علت ریشه‌ای (Root Cause)

در اندپوینت دریافت تاریخچه رویدادهای یک تراکنش (`GET /api/v1/transfers/{id}/events`)، صفحه‌بندی مبتنی بر نشانگر (Keyset/Cursor Pagination) پیاده‌سازی شده است. کلاس `TransferService` نشانگر صفحه بعد (`next_cursor`) را به‌صورت ترکیبی در قالب `occurred_at|id` تولید می‌کند تا ترتیب یکتا روی زوج `(occurred_at DESC, id DESC)` حفظ شود.

با این حال، در کلاس `TransferRepository` (متد `findEvents` و کوئری `SQL_FIND_EVENTS_BY_ID_CURSOR`):

1. بخش `|id` از رشته `cursorIso` جدا و دور ریخته می‌شد و تنها بخش زمانی (`cursorTime`) استخراج می‌گردید.

2. در شرط `WHERE` کوئری SQL صرفاً از مقایسه اکید `occurred_at < ?::timestamptz` استفاده شده بود.

**نتیجه خطا:** اگر چند رویداد از یک انتقال دارای `occurred_at` یکسان باشند و مرز صفحه (`limit`) بین آن‌ها قرار بگیرد، در درخواست صفحه بعد تمامی رویدادهای باقی‌مانده با همان timestamp به دلیل شرط اکید `<` فیلتر شده و از خروجی جا می‌افتند.

*(همچنین در بررسی تست‌های قرارداد API مشخص شد اسکیمای `Transfer` در `openapi/payment.yaml` فاقد دو فیلد `source_account_number` و `dest_account_number` بود که باعث خطای اعتبارسنجی در `TransferApiContractTest` می‌شد).*

### ۱.۲. تست بازتولید باگ و خروجی پیش از اصلاح (Fail)

در کلاس `TransferPostgresIntegrationTest` تست `returnsAllEventsWhenMultipleEventsShareSameOccurredAt` اضافه شد که ۳ رویداد با `occurred_at` یکسان ذخیره کرده و با `limit = 2` صفحه‌بندی را بررسی می‌کند.

**خروجی پیش از اصلاح (Fail):**

```
[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 7.547 s <<< FAILURE! -- in edu.softwarelab.starter.integration.TransferPostgresIntegrationTest
[ERROR] edu.softwarelab.starter.integration.TransferPostgresIntegrationTest.returnsAllEventsWhenMultipleEventsShareSameOccurredAt -- Time elapsed: 0.865 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <1> but was: <0>

```

### ۱.۳. اصلاح انجام‌شده و Diff تغییرات

در `TransferRepository`، مقدار `cursorId` نیز از `cursorIso` استخراج شد و شرط کوئری به مقایسه ترکیبی `(occurred_at < ? OR (occurred_at = ? AND id < ?))` تغییر یافت:

```
diff --git a/src/main/java/edu/softwarelab/starter/domain/TransferRepository.java b/src/main/java/edu/softwarelab/starter/domain/TransferRepository.java
--- a/src/main/java/edu/softwarelab/starter/domain/TransferRepository.java
+++ b/src/main/java/edu/softwarelab/starter/domain/TransferRepository.java
@@ -111,7 +111,11 @@ public class TransferRepository {
             SELECT id, transfer_id, event_type, payload::text, occurred_at
               FROM transfer_events
              WHERE transfer_id = ?
-               AND (?::timestamptz IS NULL OR occurred_at < ?::timestamptz)
+               AND (
+                    ?::timestamptz IS NULL
+                    OR occurred_at < ?::timestamptz
+                    OR (occurred_at = ?::timestamptz AND id < ?::uuid)
+               )
              ORDER BY occurred_at DESC, id DESC
              LIMIT ?
             """;
@@ -264,16 +268,21 @@ public class TransferRepository {
     public List<TransferEvent> findEvents(UUID transferId, String cursorIso, int limit) {
         String cursorTime = null;
+        UUID cursorId = null;
         if (cursorIso != null) {
             int sep = cursorIso.lastIndexOf('|');
-            cursorTime = sep >= 0 ? cursorIso.substring(0, sep) : cursorIso;
+            cursorTime = cursorIso.substring(0, sep);
+            cursorId = UUID.fromString(cursorIso.substring(sep + 1));
         }
         return jdbc.query(
                 SQL_FIND_EVENTS_BY_ID_CURSOR,
                 (rs, rowNum) -> mapEventRow(rs),
                 transferId,
                 cursorTime,
                 cursorTime,
+                cursorTime,
+                cursorId,
                 limit
         );
     }

```

### ۱.۴. نتیجه تست‌ها پس از اصلاح (شامل Regression Tests)

```
[INFO] Results:
[INFO] 
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS

```

## ۲. باگ دوم — Frontend: ارسال هم‌زمان `dest_account_number` و `dest_iban` در انتقال بیرونی (External Transfer)

### ۲.۱. رفتار اشتباه و علت ریشه‌ای (Root Cause)

در `frontend/src/App.tsx`، استیت `destAccount` مقدار اولیه `'001200987654'` دارد. هنگام انتخاب انتقال بیرونی (External transfer)، شرط دوم بدون `else` و بدون بررسی `!isExternal` نوشته شده بود:

```
if (isExternal && destIban) {
  body.dest_iban = destIban
}
if (destAccount) {
  body.dest_account_number = destAccount
}

```

در نتیجه در انتقال بیرونی هر دو فیلد `dest_iban` و `dest_account_number` هم‌زمان ارسال می‌شدند.

### ۲.۲. تست بازتولید باگ و خروجی پیش از اصلاح (Fail)

در `frontend/src/App.test.tsx` تست بررسی ارسال فقط `dest_iban` و عدم ارسال `dest_account_number` در انتقال بیرونی اضافه شد.

**خروجی پیش از اصلاح (Fail):**

```
 FAIL  src/App.test.tsx > Create Transfer component > sends only dest_iban and omits dest_account_number for external transfers
AssertionError: expected { …(5) } to deeply equal { …(4) }

- Expected
+ Received

  {
    "amount": 2500000,
    "currency": "IRR",
+   "dest_account_number": "001200987654",
    "dest_iban": "IR430120000000000000000001",
    "source_account_number": "001200456789",
  }

 Test Files  1 failed (1)
      Tests  1 failed | 2 passed (3)

```

### ۲.۳. اصلاح انجام‌شده و Diff تغییرات

```
diff --git a/frontend/src/App.tsx b/frontend/src/App.tsx
--- a/frontend/src/App.tsx
+++ b/frontend/src/App.tsx
@@ -42,7 +42,7 @@ export default function App() {
       if (isExternal && destIban) {
         body.dest_iban = destIban
-      }
-      if (destAccount) {
+      } else if (!isExternal && destAccount) {
         body.dest_account_number = destAccount
       }

```

### ۲.۴. نتیجه تست‌ها پس از اصلاح (شامل Regression Tests)

```
 ✓ src/App.test.tsx (3 tests) 619ms
   ✓ Create Transfer component > shows the created transfer after a successful response (FE-05) 280ms
   ✓ Create Transfer component > shows Problem Details and no success message on failure (FE-06) 121ms
   ✓ Create Transfer component > sends only dest_iban and omits dest_account_number for external transfers 215ms

 Test Files  1 passed (1)
      Tests  3 passed (3)

```

## ۳. بازآرایی (Refactoring)

### ۳.۱. Code Smell و توضیح بازآرایی

منطق فرمت کردن خطای RFC 7807 Problem Details از `apiError` در هر دو تابع `createTransfer()` و `getTransfer()` در `frontend/src/App.tsx` تکراری بود (**Duplicated Code**). با استخراج تابع کمکی `formatProblemError(apiError)` و تایپ `ApiProblem`، تکرار کد برطرف شد و در یک کامیت مستقل ثبت گردید؛ تمام تست‌های فرانت‌اند پس از بازآرایی نیز بدون خطا پاس شدند.

### ۳.۲. Diff مربوط به Refactoring

```
diff --git a/frontend/src/App.tsx b/frontend/src/App.tsx
--- a/frontend/src/App.tsx
+++ b/frontend/src/App.tsx
@@ -14,6 +14,13 @@ type Transfer = {
   updated_at: string
 }
 
+type ApiProblem = { code?: string; title?: string; detail?: string }
+
+function formatProblemError(apiError: unknown): string {
+  const problem = apiError as ApiProblem
+  return `${problem.code ?? 'ERROR'}: ${problem.title ?? ''} — ${problem.detail ?? ''}`
+}
+
 export default function App() {
   const [loading, setLoading] = useState(false)
   const [error, setError] = useState('')
@@ -57,8 +64,7 @@ export default function App() {
       })
 
       if (apiError) {
-        const problem = apiError as { code?: string; title?: string; detail?: string }
-        setError(`${problem.code ?? 'ERROR'}: ${problem.title ?? ''} — ${problem.detail ?? ''}`)
+        setError(formatProblemError(apiError))
       } else if (data) {
         setTransfer(data as unknown as Transfer)
         setMessage('Transfer created successfully.')
@@ -84,8 +90,7 @@ export default function App() {
       })
 
       if (apiError) {
-        const problem = apiError as { code?: string; title?: string; detail?: string }
-        setError(`${problem.code ?? 'ERROR'}: ${problem.title ?? ''} — ${problem.detail ?? ''}`)
+        setError(formatProblemError(apiError))
       } else if (data) {
         setTransfer(data as unknown as Transfer)
       }

```