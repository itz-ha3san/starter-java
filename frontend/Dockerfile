# ===== Build stage =====
FROM node:20-alpine AS build
WORKDIR /app

# نصب dependencyها (کش‌شدنی)
COPY package*.json ./

# استفاده از میرور npm داخلی و قابل‌دسترس از ایران.
# npmmirror (تابعه‌ی علی‌بابا) معادل کامل registry.npmjs.org است و از داخل
# ایران به‌خوبی قابل‌دسترس است. mirror-npm.runflare.com که قبلاً استفاده
# می‌شد در حال حاضر خطای 402 (پولی) برمی‌گرداند.
#
# نکته: از npm install استفاده می‌کنیم (نه npm ci) تا با نبود یا قدیمی بودن
# package-lock.json هم سازگار باشد. npm ci نیاز به lockfile دقیق دارد.
# --legacy-peer-deps برای جلوگیری از خطای peer dependency در برخی پکیج‌ها.
RUN npm config set registry https://registry.npmmirror.com && \
    npm config set strict-ssl false && \
    npm install --no-audit --no-fund --legacy-peer-deps

# کپی سورس و بیلد
COPY . .
# تولید TypeScript types از OpenAPI spec.
# نکته: در Docker build، context فقط ./frontend است و به ../openapi/Payment.yaml
# دسترسی ندارد؛ به همین دلیل || true استفاده می‌شود. فایل src/api/schema.d.ts
# از قبل در ریپو تولید شده و commit شده است، پس نیازی به تولید مجدد نیست.
# اگر spec تغییر کرد، آن را با `npm run gen:api` به‌صورت محلی بازتولید کنید
# و فایل schema.d.ts را commit کنید.
RUN npm run gen:api || true
# بیلد تولیدی
# نکته: tsconfig.app.json فایل‌های تست (*.test.ts/tsx) را از build خارج می‌کند
# تا @testing-library/dom (peer dep) در زمان build پروداکشن لازم نباشد.
RUN npm run build

# ===== Serve stage =====
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
