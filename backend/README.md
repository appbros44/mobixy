# Mobixy Backend (Phase 2)

## Run locally

1. Install deps:

```bash
npm install
```

2. Set env vars (example):

```bash
export PORT=8787
export ADMIN_TOKEN=dev-admin-token
export ENROLL_TOKEN=dev-enroll-token
```

3. Start:

```bash
npm run dev
```

## API

- `GET /health`
- `GET /admin/devices` (requires `Authorization: Bearer $ADMIN_TOKEN`)
- `POST /admin/devices/:deviceId/command` (requires `Authorization: Bearer $ADMIN_TOKEN`)

Example command:

```bash
curl -X POST \
  -H "Authorization: Bearer dev-admin-token" \
  -H "Content-Type: application/json" \
  -d '{"type":"proxy_start","payload":{}}' \
  http://localhost:8787/admin/devices/DEVICE_ID/command
```

## WebSocket (device)

- `ws://localhost:8787/ws/device?deviceId=DEVICE_ID&token=$ENROLL_TOKEN`
