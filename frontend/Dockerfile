# ===== Build stage =====
FROM node:20-alpine AS build
WORKDIR /app

# نصب dependencyها (کش‌شدنی)
COPY package*.json ./
# استفاده از میرور npm داخلی (رانفلر)
RUN npm config set registry https://mirror-npm.runflare.com && \
    npm config set strict-ssl false && \
    npm ci
# کپی سورس و بیلد
COPY . .
# اگر URL بک‌اند را در build لازم داری، اینجا ARG/ENV بده:
# ARG VITE_API_URL=/api
# ENV VITE_API_URL=$VITE_API_URL
RUN npm run build

# ===== Serve stage =====
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]