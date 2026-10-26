# گزارش جلسه ۳ — S3

> پیش‌نویس شواهد؛ آماده تحویل نهایی نیست. موارد تأییدنشده عمداً باز هستند. این گزارش به‌تنهایی اثبات تکمیل تمرین نیست.

## منابع و مبنای بررسی
- مخزن: https://github.com/itz-ha3san/starter-java
- snapshot بررسی‌شده main: `c5e370687d2c0d5ea0c55e260f40e8b3df591ce0`؛ تغییرات محلی یا بعدی را پوشش نمی‌دهد.
- Issues پروژه: https://labse1.atlassian.net/issues/?jql=project%20%3D%20KAN
- لینک دقیق Board/اسپرینت این هفته: **توسط تیم تکمیل شود؛ از URL مسائل به‌عنوان لینک Board استفاده نشده است.**
- Task قواعد: https://labse1.atlassian.net/browse/KAN-51
- Task گزارش: https://labse1.atlassian.net/browse/KAN-52

## سهم اعضا و PR Story این هفته
این جدول انتساب فعلی Jira است، نه انتخاب قطعی Story این هفته. هر عضو همان Story انتخاب‌شده سر کلاس را اعلام و لینک PR خودش را اضافه کند.

| عضو / GitHub | Storyهای منتسب در Jira | Story انتخاب‌شده جلسه | PR Story خودش | PR هم‌تیمی که review کرده / لینک Review |
| --- | --- | --- | --- | --- |
| حسن / `itz-ha3san` | KAN-8، KAN-11، KAN-14 | تأیید تیم لازم است | هنوز در این بررسی تأیید نشده | هنوز تأیید نشده |
| عرفان / `erfan8566` | KAN-9، KAN-12 | تأیید تیم لازم است | هنوز در این بررسی تأیید نشده | هنوز تأیید نشده |
| پارسا / `parsa-mhr` | KAN-10، KAN-13، KAN-15 | تأیید تیم لازم است | هنوز در این بررسی تأیید نشده | هنوز تأیید نشده |

فهرست PRهای موجود: https://github.com/itz-ha3san/starter-java/pulls?q=is%3Apr
PRهای قبلی صرفاً به‌علت merged بودن، به‌عنوان PR Story با دو comment واقعی و review معتبر شمرده نشده‌اند؛ محتوای reviewها باید بررسی و لینک مستقیم اضافه شود. PR با حساب agent/مالک جای PR نوشته‌شده توسط اعضای دیگر نیست.

## قواعد مخزن
- PR قواعد: https://github.com/itz-ha3san/starter-java/pull/10 ؛ **باز است، هنوز merge و approve تأیید نشده**.
- CODEOWNERS پیشنهادی: https://github.com/itz-ha3san/starter-java/blob/chore/kan-51-repository-rules/.github/CODEOWNERS
- لینک نهایی پس از merge: https://github.com/itz-ha3san/starter-java/blob/main/.github/CODEOWNERS ؛ تا قبل از merge ممکن است وجود نداشته باشد.
- بررسی checkout branch قواعد: تمام قواعد حداقل دو collaborator دارای write/admin دارند؛ ۳۳ مسیر tracked بررسی شد و env واقعی یا node_modules/target/dist پیدا نشد.
- `git diff --check` موفق؛ CRLF متنی پیدا نشد و `git add --renormalize .` تغییری ایجاد نکرد. این بررسی، secret scan تاریخچه یا تأیید GitHub برای CODEOWNERS نیست.

## حفاظت main و تنظیم Merge
- [ ] مالک تنظیمات [راهنما](../session-03-repository-settings.md) را اعمال و بازبینی کرده است.
- [ ] تصویر واقعی Branch Protection اضافه شده است.
- [ ] تصویر واقعی Squash-only و تنظیمات مرتبط اضافه شده است.
- [ ] پیام واقعی رد push مستقیم و فرمان اجراشده ثبت شده است.
- [ ] CODEOWNERS پس از merge روی main بدون خطای GitHub بررسی شده است.

**هیچ تصویر تنظیمات یا پیام خطای push در این بررسی تولید نشده است. فعال‌بودن حفاظت از روی محتوای فایل‌ها قابل اثبات نیست.**

## وضعیت Storyها
در زمان خواندن Jira، تمام KAN-8 تا KAN-15 در وضعیت `To Do` بودند:

| Issue | موضوع | وضعیت خوانده‌شده |
| --- | --- | --- |
| [KAN-8](https://labse1.atlassian.net/browse/KAN-8) | انتقال داخلی | To Do |
| [KAN-9](https://labse1.atlassian.net/browse/KAN-9) | اعتبارسنجی درخواست | To Do |
| [KAN-10](https://labse1.atlassian.net/browse/KAN-10) | بررسی انسانی | To Do |
| [KAN-11](https://labse1.atlassian.net/browse/KAN-11) | رزرو وجه | To Do |
| [KAN-12](https://labse1.atlassian.net/browse/KAN-12) | انتقال خارجی | To Do |
| [KAN-13](https://labse1.atlassian.net/browse/KAN-13) | آزادسازی رزرو | To Do |
| [KAN-14](https://labse1.atlassian.net/browse/KAN-14) | استعلام وضعیت | To Do |
| [KAN-15](https://labse1.atlassian.net/browse/KAN-15) | تاریخچه | To Do |

فهرست Done هفته: **تا تطبیق کد، تست، PR و review، Story تکمیل‌شده‌ای در این گزارش اعلام نمی‌شود.**
فهرست ناتمام نهایی و انتقال به Backlog: **پس از بررسی اسپرینت توسط تیم تکمیل شود**؛ این بررسی اسپرینت را نبسته یا تمدید نکرده است.

## زیرساخت و تست
- main شامل Spring JDBC، driver PostgreSQL، Swagger، Compose و بررسی اتصال هنگام startup است؛ وجود کد به معنی اجرای موفق تست در این بررسی نیست.
- [ ] Task مشترک زیرساخت و PR جدا برای migration، Problem Details و GET /ready لینک شده است.
- [ ] هر Story endpoint زیر /api/v1، ذخیره PostgreSQL، مبلغ صحیح ریالی و Fake پشت Interface دارد.
- [ ] فهرست codeهای مشترک خطا تأیید شده است.
- [ ] Bruno موفق/خطا در api-tests ثبت و توسط نویسنده و Reviewer اجرا شده است.
- [ ] هر عضو حداقل دو comment واقعی روی کد PR هم‌تیمی دارد.
- [ ] یک چرخه واقعی Request changes → اصلاح → Approve با لینک ثبت شده است.

## بازنگری و نقش‌های بعدی
دو جمله بازنگری باید حاصل توافق تیم باشند: چه چیزی خوب پیش رفت و چه چیزی هفته بعد عوض می‌شود. **هنوز توسط تیم تکمیل نشده؛ متن ساختگی به نام اعضا نوشته نشده است.**

| نقش اسپرینت بعد | عضو منتخب تیم |
| --- | --- |
| Product Owner | تعیین نشده |
| Scrum Master | تعیین نشده |
| Reviewer هفته | تعیین نشده |

## تگ و تحویل
- [ ] PR قواعد ابتدا merge شده است.
- [ ] سه PR Story مستقل با review/comment واقعی اعضا merge شده‌اند.
- [ ] این گزارش از طریق PR پس از تکمیل شواهد merge شده است.
- [ ] main نهایی اجرا و تأیید شده است.
- [ ] تگ s03 روی commit نهایی main و در مهلت ساخته شده است.

در زمان این بررسی، فهرست تگ‌های GitHub خالی بود. **s03 ساخته نشده است**؛ قبل از ساخت دوباره بررسی کنید و تگ موجود را جابه‌جا/حذف نکنید. انجام کار پس از مهلت، به‌عنوان تحویل قبل از مهلت گزارش نشود.
