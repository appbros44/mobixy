import http from 'http';
import express from 'express';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT || 8787);
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'dev-admin-token';
const ENROLL_TOKEN = process.env.ENROLL_TOKEN || 'dev-enroll-token';

const app = express();
app.use(express.json({ limit: '256kb' }));

/** @type {Map<string, { deviceId: string, connectedAt: number, lastSeenAt: number, socket: any, lastStatus: any }>} */
const devices = new Map();

function isAuthorized(req) {
  const header = req.headers['authorization'];
  if (!header) return false;
  const m = /^Bearer\s+(.+)$/i.exec(String(header));
  if (!m) return false;
  return m[1] === ADMIN_TOKEN;
}

app.get('/health', (_req, res) => {
  res.json({ ok: true });
});

app.get('/admin/devices', (req, res) => {
  if (!isAuthorized(req)) return res.status(401).json({ error: 'unauthorized' });

  const list = Array.from(devices.values()).map((d) => ({
    deviceId: d.deviceId,
    connectedAt: d.connectedAt,
    lastSeenAt: d.lastSeenAt,
    online: true,
    status: d.lastStatus ?? null
  }));

  res.json({ devices: list });
});

app.get('/admin/devices/:deviceId/status', (req, res) => {
  if (!isAuthorized(req)) return res.status(401).json({ error: 'unauthorized' });
  const deviceId = String(req.params.deviceId);
  const device = devices.get(deviceId);
  if (!device) return res.status(404).json({ error: 'device_not_connected' });
  res.json({ deviceId, online: true, lastSeenAt: device.lastSeenAt, status: device.lastStatus ?? null });
});

app.post('/admin/devices/:deviceId/command', (req, res) => {
  if (!isAuthorized(req)) return res.status(401).json({ error: 'unauthorized' });

  const deviceId = String(req.params.deviceId);
  const device = devices.get(deviceId);
  if (!device) return res.status(404).json({ error: 'device_not_connected' });

  const { type, payload } = req.body || {};
  if (!type || typeof type !== 'string') return res.status(400).json({ error: 'missing_type' });

  const cmdId = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const msg = JSON.stringify({ kind: 'command', id: cmdId, type, payload: payload ?? null });

  try {
    device.socket.send(msg);
  } catch {
    return res.status(500).json({ error: 'send_failed' });
  }

  res.json({ ok: true, id: cmdId });
});

const server = http.createServer(app);

const wss = new WebSocketServer({ server, path: '/ws/device' });

wss.on('connection', (socket, req) => {
  // eslint-disable-next-line no-console
  console.log('ws connection attempt:', req.url);
  try {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const deviceId = String(url.searchParams.get('deviceId') || '').trim();
    const token = String(url.searchParams.get('token') || '').trim();

    if (!deviceId || token !== ENROLL_TOKEN) {
      // eslint-disable-next-line no-console
      console.log('ws unauthorized:', { deviceId, tokenPresent: Boolean(token) });
      socket.close(1008, 'unauthorized');
      return;
    }

    const now = Date.now();
    // eslint-disable-next-line no-console
    console.log('ws device connected:', deviceId);
    devices.set(deviceId, { deviceId, connectedAt: now, lastSeenAt: now, socket, lastStatus: null });

    socket.on('message', (data) => {
      const device = devices.get(deviceId);
      if (device) device.lastSeenAt = Date.now();

      const text = typeof data === 'string' ? data : data?.toString?.();
      if (!text) return;
      const obj = (() => {
        try {
          return JSON.parse(text);
        } catch {
          return null;
        }
      })();

      if (device && obj && obj.kind === 'status') {
        device.lastStatus = obj;
      }
    });

    socket.on('close', () => {
      // eslint-disable-next-line no-console
      console.log('ws device disconnected:', deviceId);
      devices.delete(deviceId);
    });

    socket.on('error', () => {
      // eslint-disable-next-line no-console
      console.log('ws device error:', deviceId);
      devices.delete(deviceId);
    });

    socket.send(JSON.stringify({ kind: 'hello', deviceId }));
  } catch {
    // eslint-disable-next-line no-console
    console.log('ws server_error');
    try {
      socket.close(1011, 'server_error');
    } catch {
    }
  }
});

server.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(`mobixy-backend listening on http://0.0.0.0:${PORT}`);
  // eslint-disable-next-line no-console
  console.log(`ws: ws://0.0.0.0:${PORT}/ws/device?deviceId=YOUR_DEVICE_ID&token=ENROLL_TOKEN`);
});
