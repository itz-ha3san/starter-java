# روال کار تیم S3 — جلسه ۳

## مبنا
این مخزن برای تمرین تیمی است. سهم اعضا باید از PR، commit و review واقعی خودشان قابل پیگیری باشد. کمک ابزار یا agent را به‌جای فعالیت انجام‌شده اعضای دیگر گزارش نکنید.

## ترتیب PRها
1. ابتدا PR قواعد [#10](https://github.com/itz-ha3san/starter-java/pull/10) با review واقعی و squash وارد main شود.
2. کار مشترک DB، migration، Problem Details و `GET /ready` یک Task واقعی Jira و PR جدا می‌خواهد؛ جای PR Story نیست.
3. هر عضو Story ثبت‌شده به نام خودش را در branch مستقل و PR مستقل ادامه دهد. Story را صرفاً برای آسان‌شدن تمرین عوض نکنید.
4. گزارش و شواهد تکمیل و از طریق PR وارد main شوند؛ در آخر تگ نهایی.

## شروع هر کار
```powershell
git status
git switch main
git pull --ff-only origin main
git switch -c feat/kan-8-internal-transfer
```
اگر working tree تغییر محلی دارد، قبل از switch آن را با دقت حفظ کنید؛ از reset/clean استفاده نکنید. KAN-8 فقط نمونه است و باید با Issue واقعی خودتان جایگزین شود.

قالب branch: `<type>/<jira-key-lowercase>-<short-description>`؛ فقط حروف کوچک انگلیسی، عدد، خط تیره و یک `/` جداکننده. قالب commit و عنوان PR: `feat(transfers): persist transfer request (KAN-8)`؛ انواع مجاز: feat، fix، refactor، test، docs، chore.

## پیاده‌سازی و تست Story
- endpointهای کسب‌وکار زیر `/api/v1` و داده در PostgreSQL، نه حافظه.
- مبلغ عدد صحیح به ریال؛ بدون float/double.
- خطاها Problem Details با code از فهرست مشترک تأییدشده؛ code خودساخته را مشترک معرفی نکنید.
- فراخوانی همسایه پشت Interface و Fake قابل تست؛ جلسه ۳ به سرویس واقعی همسایه وابسته نباشد.
- درخواست‌های Bruno در `api-tests/` برای موفقیت و حداقل یک خطا commit شوند؛ تست‌های پیشنهادی/اجرا نشده را جدا بنویسید.
- اسکلت Vite به‌تنهایی Task است، نه Story فرانت‌اند؛ Story فرانت‌اند باید صفحه واقعی و فراخوانی endpoint واقعی داشته باشد.
- تغییرات را در commitهای کوچک و معنادار نگه دارید؛ PR تقریباً حداکثر ۴۰۰ خط بدون lock/generated.

## Review
PR ناتمام Draft است. وقتی آماده شد Ready for review و هم‌تیمی Reviewer تعیین شود؛ حداقل ۴۸ ساعت زمان دهید. هر عضو حداقل یک PR هم‌تیمی را با دو comment واقعی روی خطوط کد review کند و درخواست‌های Bruno را خودش اجرا کند.

commentها درباره کد و با دلیل باشند، نه نویسنده؛ برچسب‌های مناسب: `issue:`، `suggestion:`، `question:`، `nitpick (non-blocking):`، `praise:`. برای سهم/نمره comment ساختگی، کپی یا بی‌ربط نسازید. چرخه Request changes → اصلاح → Approve فقط برای ایراد واقعی انجام شود؛ برای نمایش چرخه عمداً باگ نسازید.

نویسنده پاسخ یا اصلاح می‌دهد. Reviewer بعد از قانع‌شدن conversation را resolve می‌کند. approve خودنویسنده معتبر نیست. بعد از approve و حل گفتگوها فقط Squash and merge، سپس حذف branch همان PR (نه branch فعال شخص دیگر).

## پایان جلسه
تنظیمات و شواهد در [راهنمای حفاظت](docs/session-03-repository-settings.md) و [گزارش](docs/reports/session-03.md) آمده‌اند. تگ s03 فقط بعد از تکمیل و merge کارها روی main و در مهلت ساخته شود. تگ موجود را جابه‌جا/حذف نکنید. اسپرینت بدون تمدید بسته شود، کار ناتمام به Backlog برگردد و نقش‌های هفته بعد با تصمیم واقعی تیم بچرخند.
