# راهنمای mirrorهای داخلی برای دسترسی بهتر از ایران

این فایل راهنمای کامل استفاده از mirrorهای داخلی برای اجزای مختلف پروژه است.

## ۱. Maven (Backend Java)

فایل [`.mvn-settings.xml`](.mvn-settings.xml) در ریشه‌ی پروژه قرار دارد و سه mirror داخلی را به‌ترتیب اولویت تنظیم می‌کند:

| # | Mirror | آدرس | موقعیت |
|---|--------|------|--------|
| 1 | Aliyun Public | `https://maven.aliyun.com/repository/public` | چین (سریع از ایران) |
| 2 | Huawei Cloud | `https://mirrors.huaweicloud.com/repository/maven/public` | چین (fallback) |
| 3 | Tencent Cloud | `https://mirrors.cloud.tencent.com/nexus/repository/maven-public` | چین (fallback) |

این فایل به‌صورت خودکار در `Dockerfile` به `/root/.m2/settings.xml` کپی می‌شود و Maven از آن استفاده می‌کند.

### اجرای محلی (بدون Docker)

```powershell
# در PowerShell (ویندوز):
mvn -s .mvn-settings.xml package -DskipTests

# یا در Linux/macOS:
mvn -s .mvn-settings.xml package -DskipTests
```

برای دائمی کردن آن، می‌توانید فایل را به `~/.m2/settings.xml` (یا `%USERPROFILE%\.m2\settings.xml` در ویندوز) کپی کنید.

## ۲. npm (Frontend)

### اجرای محلی

فایل [`frontend/.npmrc`](frontend/.npmrc) به‌صورت خودکار توسط npm خوانده می‌شود و registry را به mirror تابع علی‌بابا تغییر می‌دهد:

```
registry=https://registry.npmmirror.com
strict-ssl=false
legacy-peer-deps=true
```

بعد از قرار دادن این فایل، نیازی به `--registry` در دستور نیست:

```powershell
cd frontend
npm install --no-audit --no-fund --legacy-peer-deps
npm run build
npm test
```

### اجرای Docker

[`frontend/Dockerfile`](frontend/Dockerfile) هم به‌صورت صریح mirror را تنظیم می‌کند:

```dockerfile
RUN npm config set registry https://registry.npmmirror.com && \
    npm config set strict-ssl false && \
    npm install --no-audit --no-fund --legacy-peer-deps
```

### چرا npmmirror (نه runflare)؟

قبلاً از `https://mirror-npm.runflare.com` استفاده می‌شد، اما این سرویس اکنون خطای 402 (پولی/محدود) برمی‌گرداند. `npmmirror` (تابعه‌ی علی‌بابا) معادل کامل `registry.npmjs.org` است و از داخل ایران به‌خوبی قابل‌دسترس است.

## ۳. Docker image registry (برای pull کردن imageهای رسمی)

این مورد در سطح Docker daemon تنظیم می‌شود (نه در سطح پروژه). اگر Docker Desktop در pull کردن imageهایی مثل `postgres:16-alpine`, `node:20-alpine`, `confluentinc/cp-kafka:7.7.1` مشکل دارد، این mirrorها را به daemon.json اضافه کنید.

### در Docker Desktop (ویندوز/Mac)

1. روی آیکون Docker Desktop کلیک کنید → **Settings** → **Docker Engine**
2. JSON را ویرایش کنید تا `registry-mirrors` را شامل شود:

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1panel.live",
    "https://hub.rat.dev"
  ],
  "experimental": false,
  "builder": {
    "gc": {
      "defaultKeepStorage": "20GB",
      "enabled": true
    }
  }
}
```

3. روی **Apply & Restart** کلیک کنید.

### تست mirrorها

```powershell
# قبل از restart:
docker pull hello-world  # اگر fail شد، نیاز به mirror دارید

# بعد از اضافه کردن mirror و restart:
docker pull hello-world  # باید موفق شود
docker info  # باید در بخش Registry Mirrors آدرسهای جدید را نشان دهد
```

### لیست mirrorهای Docker قابل‌دسترس از ایران

| Mirror | آدرس | وضعیت |
|--------|------|-------|
| DaoCloud | `https://docker.m.daocloud.io` | فعال |
| 1Panel | `https://docker.1panel.live` | فعال |
| Hub Rat | `https://hub.rat.dev` | فعال |
| Nuwa | `https://docker.nju.edu.cn` | گاهی کند |

> **توجه:** mirrorهای Docker غالباً تغییر می‌کنند. اگر یکی fail شد، دیگری را امتحان کنید.

## ۴. تست سریع دسترسی

برای تست اینکه کدام mirrorها از سیستم شما قابل‌دسترس‌اند:

```powershell
# Maven mirrors
curl.exe -sI https://maven.aliyun.com/repository/public/
curl.exe -sI https://mirrors.huaweicloud.com/repository/maven/public/

# npm mirror
curl.exe -sI https://registry.npmmirror.com/

# Docker registry mirrors
curl.exe -sI https://docker.m.daocloud.io/v2/
curl.exe -sI https://docker.1panel.live/v2/
```

کد 200 یا 401 (برای Docker) یعنی mirror در دسترس است.
