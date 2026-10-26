# تنظیمات سرور GitHub و شواهد جلسه ۳

این فایل راهنماست؛ وجود آن به معنی فعال‌شدن تنظیمات نیست. اتصال فعلی GitHub MCP عملیات ویرایش Branch Protection/merge settings ندارد؛ مالک باید در Settings اعمال و نتیجه را ثبت کند. بدون بررسی، هیچ تنظیمی در گزارش انجام‌شده اعلام نشود.

## پیش‌نیاز و ترتیب
- ابتدا PR قواعد #10 با review واقعی merge شود تا CODEOWNERS روی main باشد.
- مالک‌های تأییدشده با دسترسی write/admin: `itz-ha3san`، `erfan8566` و `parsa-mhr`.
- Settings → Branches → Add classic branch protection rule، الگوی `main`؛ اگر ruleset موجود است ابتدا هم‌پوشانی آن بررسی شود.
- طبق تمرین، در GitHub Free قابلیت حفاظت برای repo عمومی قابل استفاده است؛ repo خصوصی به پلن مناسب نیاز دارد. بدون تصمیم مالک، visibility را تغییر ندهید.

## Branch Protection برای main
- [ ] Require a pull request before merging.
- [ ] Required approvals = 1.
- [ ] Dismiss stale pull request approvals when new commits are pushed.
- [ ] Require review from Code Owners.
- [ ] Require approval of the most recent reviewable push.
- [ ] Require conversation resolution before merging.
- [ ] Require linear history.
- [ ] Do not allow bypassing the above settings؛ bypass استثنایی برای انجام تمرین نسازید.
- [ ] Allow force pushes خاموش.
- [ ] Allow deletions خاموش.
- [ ] Require status checks to pass فعلاً خاموش؛ طبق تمرین از جلسه ۸.

## Settings → General → Pull Requests
- [ ] Allow squash merging روشن؛ Default commit message = Pull request title.
- [ ] Allow merge commits خاموش.
- [ ] Allow rebase merging خاموش.
- [ ] Always suggest updating pull request branches روشن.
- [ ] Automatically delete head branches روشن.

## بررسی CODEOWNERS
پس از merge، `.github/CODEOWNERS` را روی main باز کنید، خطاهای GitHub را بررسی کنید و auto-review request در یک PR بعدی را ببینید. هر قاعده باید حداقل دو مالک معتبر داشته باشد؛ آخرین الگوی منطبق برنده است.

## شواهد واقعی
تصاویر را بعد از اجرا در `docs/reports/evidence/session-03/` قرار دهید و لینکشان را در گزارش اضافه کنید. نام‌های پیشنهادی: `branch-protection.png`، `merge-settings.png` و `direct-push-rejected.png`. این تصاویر هنوز تولید یا تأیید نشده‌اند.

تست رد push مستقیم را فقط بعد از فعال‌کردن و بررسی حفاظت با هماهنگی تیم انجام دهید. `git push` بدون commit جدید معمولاً فقط Everything up-to-date می‌دهد و اثبات حفاظت نیست. از force push و حذف branch استفاده نکنید. تست باید به commit جدید کم‌خطر و قابل بازیابی روی branch آزمایشی اشاره کند؛ اگر غیرمنتظره پذیرفته شد، به‌عنوان موفقیت گزارش نشود و با PR اصلاح شود، نه بازنویسی تاریخچه. پیام واقعی خطا و فرمان را ثبت کنید؛ پیام فرضی ننویسید.

## فایل‌های قبلاً ثبت‌شده و secret
`.gitignore` فایل tracked را حذف نمی‌کند. اگر موردی وجود دارد، فقط با `git rm --cached` و مسیر دقیق از index خارج کنید، نه از دیسک. اگر secret واقعی در commit فعلی یا تاریخچه بوده، ابتدا revoke/rotate کنید؛ پاک‌کردن فایل یا تاریخچه به‌تنهایی کافی نیست. بررسی مسیرها جای secret scan تاریخچه را نمی‌گیرد.

## تگ تحویل
بعد از merge تمام PRهای پذیرفته‌شده و تکمیل گزارش:
```powershell
git switch main
git pull --ff-only origin main
git ls-remote --tags origin refs/tags/s03
```
اگر s03 موجود است، آن را دست نزنید. اگر موجود نیست، نسخه نهایی تأیید شده و هنوز در مهلت هستید:
```powershell
git tag s03
git push origin s03
```
این راهنما هیچ تگ یا تاریخ فعالیتی را ایجاد، جابه‌جا یا عقب‌گذاری نمی‌کند.
