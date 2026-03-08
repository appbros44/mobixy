# Mobixy Production Deployment (AlmaLinux + cPanel + nginx front proxy + PM2 + Postgres)

This document is an end-to-end runbook for deploying Mobixy on a VPS running AlmaLinux with cPanel, using:

- Postgres for persistence (Prisma)
- Node.js backend managed by PM2
- nginx as a front proxy for `api.*` (HTTP + WebSocket)
- Apache/cPanel static hosting for `admin.*`

---

## Assumptions (adjust if different)

- **API domain**: `api.apkroute.com`
- **Admin domain**: `admin.apkroute.com`
- **Backend listens**: `127.0.0.1:8787`
- **Backend path**: `/opt/mobixy/backend`
- **Admin build output**: `/opt/mobixy/backend/admin-panel/dist`
- **Admin site docroot (cPanel)**: `/home/freeypfb/admin.apkroute.com/`
- **nginx include dir (cPanel)**: `/etc/nginx/conf.d/users/freeypfb/api.apkroute.com/`
- **Process manager**: `pm2`

---

## 0) DNS (and Cloudflare if used)

- Create `A`/`AAAA` records:
  - `api.apkroute.com` -> VPS IP
  - `admin.apkroute.com` -> VPS IP

If using Cloudflare (orange cloud):

- WebSocket is supported.
- Ensure nginx forwards headers for real client IP (`CF-Connecting-IP`, etc.).

---

## 1) OS packages

Install essentials (package names may vary):

```bash
sudo dnf -y update
sudo dnf -y install git nginx
sudo dnf -y install postgresql-server postgresql postgresql-contrib
sudo dnf -y install gcc-c++ make python3
```

Install a compatible Node.js LTS version for your project (method depends on your server policy).

---

## 2) Postgres setup

### 2.1 Initialize + start

```bash
sudo postgresql-setup --initdb
sudo systemctl enable --now postgresql
```

### 2.2 Create database and user

```bash
sudo -u postgres psql
```

Inside `psql`:

```sql
CREATE DATABASE mobixy;
CREATE USER mobixy WITH PASSWORD 'CHANGE_ME_STRONG';
GRANT ALL PRIVILEGES ON DATABASE mobixy TO mobixy;
ALTER DATABASE mobixy OWNER TO mobixy;
\q
```

### 2.3 Ensure password auth works

Edit `pg_hba.conf` (commonly `/var/lib/pgsql/data/pg_hba.conf`) so local app connections work with password auth (recommended: `scram-sha-256`).

Restart Postgres:

```bash
sudo systemctl restart postgresql
```

---

## 3) Get backend code onto the VPS

### 3.1 Create base directory

```bash
sudo mkdir -p /opt/mobixy
sudo chown -R $USER:$USER /opt/mobixy
```

### 3.2 Clone repo

```bash
git clone <YOUR_GIT_URL> /opt/mobixy/backend
```

If you deploy from `main`:

```bash
git -C /opt/mobixy/backend checkout main
```

---

## 4) Backend environment (.env)

Create:

- `/opt/mobixy/backend/.env`

Example:

```dotenv
PORT=8787
USE_DB=true
DATABASE_URL=postgresql://mobixy:CHANGE_ME_STRONG@127.0.0.1:5432/mobixy?schema=public

ENROLL_TOKEN=dev-enroll-token

ADMIN_EMAIL=admin@mobixy.local
ADMIN_PASSWORD=admin

JWT_SECRET=CHANGE_ME_LONG_RANDOM
JWT_EXPIRES_IN=7d
DEVICE_JWT_EXPIRES_IN=30d

UI_ORIGIN=https://admin.apkroute.com

# FCM (required for Wake push notifications)
# Path to Firebase service account JSON on the server
FIREBASE_SERVICE_ACCOUNT_PATH=/opt/mobixy/secrets/firebase-service-account.json
```

Notes:

- Do not change `JWT_SECRET` casually (it invalidates existing tokens).
- `UI_ORIGIN` must match the admin panel origin.
- Push notifications (Wake) require `FIREBASE_SERVICE_ACCOUNT_PATH` to be set.

---

## 4.1) Firebase Cloud Messaging (FCM) setup (Wake push)

Wake push notifications are sent by the backend using Firebase Admin SDK.

The backend expects:

- A **service account JSON** file on the server
- `FIREBASE_SERVICE_ACCOUNT_PATH` env var pointing to it

### 4.1.1 Get the service account JSON

In Firebase Console:

- Project settings
- Service accounts
- Generate new private key

Download the JSON and upload it to the VPS.

### 4.1.2 Place it on the VPS

Recommended:

```bash
sudo mkdir -p /opt/mobixy/secrets
sudo chown root:root /opt/mobixy/secrets
sudo chmod 700 /opt/mobixy/secrets
```

Copy the JSON as:

- `/opt/mobixy/secrets/firebase-service-account.json`

Permissions (keep it private):

```bash
sudo chown root:root /opt/mobixy/secrets/firebase-service-account.json
sudo chmod 600 /opt/mobixy/secrets/firebase-service-account.json
```

### 4.1.3 Configure backend env + restart

Set in `/opt/mobixy/backend/.env`:

- `FIREBASE_SERVICE_ACCOUNT_PATH=/opt/mobixy/secrets/firebase-service-account.json`

Restart backend **with env refresh**:

```bash
pm2 restart mobixy-backend --update-env
```

### 4.1.4 Verify FCM works

- Ensure the Android app has a valid FCM token stored (it saves it in prefs).
- From admin panel, trigger **Wake**.

If it fails, check backend logs:

```bash
pm2 logs mobixy-backend --lines 200
```

Common errors:

- `missing_firebase_service_account_path` (env var not set in PM2)
- JSON path wrong/permissions
- Firebase project mismatch (service account from a different project than the Android app)

## 5) Install backend deps + Prisma

```bash
cd /opt/mobixy/backend
npm install
```

Prisma (choose one approach):

- Using schema push:

```bash
npx prisma generate
npx prisma db push
```

- Using migrations:

```bash
npx prisma generate
npx prisma migrate deploy
```

---

## 6) Run backend with PM2

### 6.1 Install PM2

```bash
sudo npm i -g pm2
```

### 6.2 Start backend

Adjust entrypoint if different (common: `src/server.js`):

```bash
cd /opt/mobixy/backend
pm2 start src/server.js --name mobixy-backend --update-env
pm2 save
```

### 6.3 Enable on boot

```bash
pm2 startup
# run the printed command
pm2 save
```

### 6.4 Verify locally

```bash
curl -i http://127.0.0.1:8787/health
```

---

## 7) nginx front proxy (API: HTTP + WebSocket)

Goal:

- `https://api.apkroute.com/health` -> Node
- `https://api.apkroute.com/auth/*` -> Node
- `https://api.apkroute.com/admin/*` -> Node
- `wss://api.apkroute.com/ws/device` -> Node (Upgrade)

On cPanel-managed nginx, place includes in:

- `/etc/nginx/conf.d/users/freeypfb/api.apkroute.com/`

### 7.1 WebSocket include (`mobixy-ws.conf`)

```nginx
location ^~ /ws/device {
  proxy_pass http://127.0.0.1:8787;
  proxy_http_version 1.1;

  proxy_set_header Upgrade $http_upgrade;
  proxy_set_header Connection "upgrade";

  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;

  proxy_read_timeout 3600s;
  proxy_send_timeout 3600s;
}
```

### 7.2 HTTP include (`mobixy-api.conf`)

```nginx
location = /health {
  proxy_pass http://127.0.0.1:8787;
  proxy_http_version 1.1;

  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
}

location ^~ /auth/ {
  proxy_pass http://127.0.0.1:8787;
  proxy_http_version 1.1;

  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
}

location ^~ /admin/ {
  proxy_pass http://127.0.0.1:8787;
  proxy_http_version 1.1;

  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
}
```

### 7.3 Validate + reload

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### 7.4 Verify

```bash
curl -i https://api.apkroute.com/health
```

---

## 8) Admin panel deployment (static)

Admin panel is a Vite build served via Apache/cPanel.

### 8.1 Build

```bash
cd /opt/mobixy/backend/admin-panel
npm install
npm run build
```

### 8.2 Deploy to docroot

```bash
sudo rsync -av --delete /opt/mobixy/backend/admin-panel/dist/ /home/freeypfb/admin.apkroute.com/
```

### 8.3 Verify

- Open `https://admin.apkroute.com`
- Login
- Confirm devices list renders

---

## 9) Verification checklist

### 9.1 API

```bash
curl -i https://api.apkroute.com/health
```

### 9.2 Auth

```bash
curl -i https://api.apkroute.com/auth/login \
  -H 'Content-Type: application/json' \
  --data '{"email":"admin@mobixy.local","password":"admin"}'
```

### 9.3 Admin endpoints require token

```bash
curl -i https://api.apkroute.com/admin/devices
# expected 401 without Bearer token
```

### 9.4 PM2 status + logs

```bash
pm2 ls
pm2 logs mobixy-backend --lines 100
```

---

## 10) Updating code from git on the VPS (recommended sequence)

Use this after merging to `main`.

### 10.1 Pull latest

```bash
git -C /opt/mobixy/backend fetch --all
git -C /opt/mobixy/backend checkout main
git -C /opt/mobixy/backend pull --ff-only
```

### 10.2 Install deps

```bash
cd /opt/mobixy/backend
npm install
```

### 10.3 Prisma update (only when schema changed)

Choose one:

```bash
npx prisma generate
npx prisma db push
```

or

```bash
npx prisma generate
npx prisma migrate deploy
```

### 10.4 Restart backend

If `.env` changed:

```bash
pm2 restart mobixy-backend --update-env
```

If `.env` did not change:

```bash
pm2 restart mobixy-backend
```

### 10.5 Rebuild + redeploy admin panel (only when admin-panel changed)

```bash
cd /opt/mobixy/backend/admin-panel
npm install
npm run build
sudo rsync -av --delete dist/ /home/freeypfb/admin.apkroute.com/
```

### 10.6 Reload nginx (only when config changed)

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### 10.7 Smoke test

```bash
curl -i https://api.apkroute.com/health
```

---

## 11) Operations / troubleshooting

- Backend logs:

```bash
pm2 logs mobixy-backend --lines 200
```

- Restart backend + reload nginx:

```bash
pm2 restart mobixy-backend --update-env
sudo systemctl reload nginx
```

- Check what is listening on 8787:

```bash
sudo ss -ltnp | grep 8787 || true
```

- If PM2 duplicate processes exist:

```bash
pm2 ls
pm2 delete mobixy-backend
cd /opt/mobixy/backend
pm2 start src/server.js --name mobixy-backend --update-env
pm2 save
```
