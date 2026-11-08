# ===== Build stage =====
FROM node:20-alpine AS build
WORKDIR /app

# نصب dependencyها (کش‌شدنی)
COPY package*.json ./
# استفاده از میرور npm داخلی (رانفلر)
# نکته: از npm install استفاده می‌کنیم (نه npm ci) تا با نبود یا قدیمی بودن
# package-lock.json هم سازگار باشد. npm ci نیاز به lockfile دقیق دارد.
# --legacy-peer-deps برای جلوگیری از خطای peer dependency در برخی پکیج‌ها
RUN npm config set registry https://mirror-npm.runflare.com && \
    npm config set strict-ssl false && \
    npm install --no-audit --no-fund --legacy-peer-deps
# کپی سورس و بیلد
COPY . .
# تولید TypeScript types از OpenAPI spec (اگر هنوز تولید نشده)
RUN npm run gen:api || true
# بیلد تولیدی
RUN npm run build

# ===== Serve stage =====
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
