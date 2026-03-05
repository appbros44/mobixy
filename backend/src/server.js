import 'dotenv/config'
import http from 'http';
import express from 'express'
import { WebSocketServer } from 'ws'
import session from 'express-session'
import { getDb } from './db.js'
import { sendWakePush } from './fcm.js'
import jwt from 'jsonwebtoken'
import crypto from 'crypto'

const app = express()
const PORT = process.env.PORT || 8787

const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'dev-admin-token'
const ENROLL_TOKEN = process.env.ENROLL_TOKEN || 'dev-enroll-token'
const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@mobixy.local'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin'
const SESSION_SECRET = process.env.SESSION_SECRET || 'dev-session-secret'
const UI_ORIGIN = process.env.UI_ORIGIN || 'http://localhost:5173'
const USE_DB = process.env.USE_DB === 'true'

const JWT_SECRET = process.env.JWT_SECRET || 'dev-jwt-secret'
const JWT_EXPIRES_IN = process.env.JWT_EXPIRES_IN || '7d'

const DEVICE_JWT_EXPIRES_IN = process.env.DEVICE_JWT_EXPIRES_IN || '30d'

app.use(express.json({ limit: '256kb' }));

async function getDevicesList() {
  if (!USE_DB) {
    return Array.from(devices.values()).map((d) => ({
      deviceId: d.deviceId,
      connectedAt: d.connectedAt,
      lastSeenAt: d.lastSeenAt,
      online: true,
      status: d.lastStatus ?? null
    }))
  }
  const db = getDb()
  const dbDevices = await db.device.findMany({
    include: {
      status: true
    },
    orderBy: {
      updatedAt: 'desc'
    }
  })

  return dbDevices.map((d) => {
    const online = devices.has(d.id)
    const mem = devices.get(d.id)
    const status = mem?.lastStatus ?? (d.status
      ? {
          kind: 'status',
          deviceId: d.id,
          socksRunning: d.status.socksRunning,
          socksPort: d.status.socksPort,
          credsConfigured: d.status.credsConfigured,
          fcmToken: d.fcmToken ?? null
        }
      : null)

    return {
      deviceId: d.id,
      connectedAt: mem?.connectedAt ?? null,
      lastSeenAt: mem?.lastSeenAt ?? (d.lastSeenAt ? d.lastSeenAt.getTime() : null),
      online,
      status
    }
  })
}

async function getDeviceStatus(deviceId) {
  const mem = devices.get(deviceId)
  if (!USE_DB) {
    if (!mem) return null
    return { deviceId, online: true, lastSeenAt: mem.lastSeenAt, status: mem.lastStatus ?? null }
  }

  if (mem) {
    return { deviceId, online: true, lastSeenAt: mem.lastSeenAt, status: mem.lastStatus ?? null }
  }

  const db = getDb()
  const d = await db.device.findUnique({
    where: { id: deviceId },
    include: { status: true }
  })

  if (!d) return null

  const status = d.status
    ? {
        kind: 'status',
        deviceId: d.id,
        socksRunning: d.status.socksRunning,
        socksPort: d.status.socksPort,
        credsConfigured: d.status.credsConfigured,
        fcmToken: d.fcmToken ?? null
      }
    : null

  return {
    deviceId: d.id,
    online: false,
    lastSeenAt: d.lastSeenAt ? d.lastSeenAt.getTime() : null,
    status
  }
}

async function listQueuedCommands(deviceId) {
  if (!USE_DB) {
    return null
  }
  const db = getDb()
  const cmds = await db.command.findMany({
    where: {
      deviceId,
      status: 'queued'
    },
    orderBy: {
      createdAt: 'asc'
    }
  })
  return cmds.map((c) => ({
    id: c.id,
    type: c.type,
    payload: c.payload ?? null,
    status: c.status,
    createdAt: c.createdAt
  }))
}

async function sendQueuedCommandsNow(deviceId) {
  if (!USE_DB) {
    return { ok: false, error: 'db_disabled' }
  }

  const device = devices.get(deviceId)
  if (!device) {
    return { ok: false, error: 'device_not_connected' }
  }

  const queued = await listQueuedCommands(deviceId)
  if (!queued || queued.length === 0) {
    return { ok: true, sent: 0 }
  }

  let sent = 0
  for (const cmd of queued) {
    const msg = JSON.stringify({ kind: 'command', id: cmd.id, type: cmd.type, payload: cmd.payload ?? null })
    device.socket.send(msg)
    sent += 1
  }

  const db = getDb()
  await db.command.updateMany({
    where: { deviceId, status: 'queued' },
    data: { status: 'sent' }
  })

  return { ok: true, sent }
}

async function getDeviceFcmToken(deviceId) {
  const mem = devices.get(deviceId)
  const memToken = mem?.lastStatus?.fcmToken
  if (typeof memToken === 'string' && memToken.trim()) return memToken.trim()

  if (!USE_DB) return null

  const db = getDb()
  const d = await db.device.findUnique({
    where: { id: deviceId },
    select: { fcmToken: true }
  })
  const token = d?.fcmToken
  if (typeof token === 'string' && token.trim()) return token.trim()
  return null
}

async function wakeDevice(deviceId) {
  const token = await getDeviceFcmToken(deviceId)
  if (!token) return { ok: false, error: 'missing_fcm_token' }
  const r = await sendWakePush({ token, deviceId })
  return { ok: true, messageId: r.id }
}

async function wakeDeviceWithTokenOverride(deviceId, fcmToken) {
  const token = typeof fcmToken === 'string' && fcmToken.trim() ? fcmToken.trim() : await getDeviceFcmToken(deviceId)
  if (!token) return { ok: false, error: 'missing_fcm_token' }
  const r = await sendWakePush({ token, deviceId })
  return { ok: true, messageId: r.id }
}

app.use(
  session({
    secret: SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    cookie: {
      httpOnly: true,
      sameSite: 'lax'
    }
  })
);

app.use((req, res, next) => {
  const p = req.path || ''
  if (p.startsWith('/ui/') || p.startsWith('/auth/') || p.startsWith('/admin/')) {
    res.setHeader('Access-Control-Allow-Origin', UI_ORIGIN);
    res.setHeader('Vary', 'Origin')
    res.setHeader('Access-Control-Allow-Credentials', 'true');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
    if (req.method === 'OPTIONS') return res.status(204).end();
  }
  next();
});

/** @type {Map<string, { deviceId: string, connectedAt: number, lastSeenAt: number, socket: any, lastStatus: any }>} */
const devices = new Map();

function signAdminJwt() {
  return jwt.sign(
    {
      sub: 'admin',
      role: 'admin'
    },
    JWT_SECRET,
    {
      expiresIn: JWT_EXPIRES_IN
    }
  )
}

function getBearerToken(req) {
  const header = req.headers['authorization'];
  if (!header) return null;
  const m = /^Bearer\s+(.+)$/i.exec(String(header));
  if (!m) return null;
  return m[1];
}

function verifyAdminJwt(token) {
  try {
    const payload = jwt.verify(token, JWT_SECRET)
    if (!payload || payload.role !== 'admin') return null
    return payload
  } catch {
    return null
  }
}

function signDeviceJwt(deviceId) {
  return jwt.sign(
    {
      sub: String(deviceId),
      role: 'device'
    },
    JWT_SECRET,
    {
      expiresIn: DEVICE_JWT_EXPIRES_IN
    }
  )
}

function verifyDeviceJwt(token) {
  try {
    const payload = jwt.verify(token, JWT_SECRET)
    if (!payload || payload.role !== 'device') return null
    const sub = String(payload.sub || '').trim()
    if (!sub) return null
    return payload
  } catch {
    return null
  }
}

function hashSecret(secret) {
  return crypto.createHash('sha256').update(String(secret)).digest('hex')
}

function hashPassword(password) {
  const salt = crypto.randomBytes(16)
  const key = crypto.scryptSync(String(password), salt, 64)
  return `scrypt:${salt.toString('hex')}:${key.toString('hex')}`
}

function verifyPassword(password, stored) {
  try {
    const s = String(stored || '')
    const parts = s.split(':')
    if (parts.length !== 3) return false
    if (parts[0] !== 'scrypt') return false
    const salt = Buffer.from(parts[1], 'hex')
    const expected = Buffer.from(parts[2], 'hex')
    const key = crypto.scryptSync(String(password), salt, expected.length)
    return crypto.timingSafeEqual(expected, key)
  } catch {
    return false
  }
}

async function ensureAdminUser() {
  if (!USE_DB) return
  if (!ADMIN_EMAIL.trim()) return

  const db = getDb()
  const email = ADMIN_EMAIL.trim().toLowerCase()
  const existing = await db.user.findUnique({ where: { email } })
  if (existing) return

  await db.user.create({
    data: {
      email,
      passwordHash: hashPassword(ADMIN_PASSWORD),
      role: 'ADMIN'
    }
  })
}

function safeEq(a, b) {
  try {
    const ab = Buffer.from(String(a))
    const bb = Buffer.from(String(b))
    if (ab.length !== bb.length) return false
    return crypto.timingSafeEqual(ab, bb)
  } catch {
    return false
  }
}

function isAuthorized(req) {
  const token = getBearerToken(req)
  if (!token) return false;

  const adminJwt = verifyAdminJwt(token)
  if (adminJwt) return true

  // legacy fallback
  return token === ADMIN_TOKEN
}

function isUiAuthed(req) {
  const token = getBearerToken(req)
  if (token && verifyAdminJwt(token)) return true
  return Boolean(req.session && req.session.isAdmin === true);
}

app.post('/auth/login', (req, res) => {
  const email = String(req.body?.email || '').trim().toLowerCase()
  const password = String(req.body?.password || '')

  // DB-backed admin login (preferred)
  if (USE_DB && ADMIN_EMAIL.trim()) {
    if (!email) return res.status(400).json({ error: 'missing_email' })

    const db = getDb()
    db.user
      .findUnique({ where: { email }, select: { passwordHash: true, role: true } })
      .then((u) => {
        if (!u || u.role !== 'ADMIN') return res.status(401).json({ error: 'invalid_credentials' })
        if (!verifyPassword(password, u.passwordHash)) return res.status(401).json({ error: 'invalid_credentials' })
        const token = signAdminJwt()
        res.json({ ok: true, token })
      })
      .catch(() => res.status(500).json({ error: 'server_error' }))
    return
  }

  // Legacy dev login
  if (password !== ADMIN_PASSWORD) return res.status(401).json({ error: 'invalid_credentials' })
  const token = signAdminJwt()
  res.json({ ok: true, token })
})

app.get('/auth/me', (req, res) => {
  const token = getBearerToken(req)
  if (!token) return res.status(401).json({ error: 'unauthorized' })
  const payload = verifyAdminJwt(token)
  if (!payload) return res.status(401).json({ error: 'unauthorized' })
  res.json({ ok: true, role: 'admin' })
})

// Device bootstrap: exchange global ENROLL_TOKEN for a per-device secret (one-time / rotate)
app.post('/device/register', (req, res) => {
  const deviceId = String(req.body?.deviceId || '').trim()
  const enrollToken = String(req.body?.enrollToken || '').trim()
  if (!deviceId) return res.status(400).json({ error: 'missing_device_id' })
  if (!enrollToken || enrollToken !== ENROLL_TOKEN) return res.status(401).json({ error: 'unauthorized' })

  const secret = crypto.randomBytes(24).toString('hex')
  const secretHash = hashSecret(secret)

  if (!USE_DB) {
    return res.status(400).json({ error: 'db_disabled' })
  }

  const db = getDb()
  db.device
    .upsert({
      where: { id: deviceId },
      create: { id: deviceId, secretHash, lastSeenAt: new Date() },
      update: { secretHash, lastSeenAt: new Date() }
    })
    .then(() => res.json({ ok: true, deviceId, secret }))
    .catch(() => res.status(500).json({ error: 'server_error' }))
})

// Device auth: exchange deviceId + secret for a device JWT
app.post('/device/auth', (req, res) => {
  const deviceId = String(req.body?.deviceId || '').trim()
  const secret = String(req.body?.secret || '').trim()
  if (!deviceId) return res.status(400).json({ error: 'missing_device_id' })
  if (!secret) return res.status(400).json({ error: 'missing_secret' })

  if (!USE_DB) {
    return res.status(400).json({ error: 'db_disabled' })
  }

  const db = getDb()
  db.device
    .findUnique({ where: { id: deviceId }, select: { secretHash: true } })
    .then((d) => {
      const expected = d?.secretHash
      if (!expected) return res.status(401).json({ error: 'unauthorized' })

      const got = hashSecret(secret)
      if (!safeEq(expected, got)) return res.status(401).json({ error: 'unauthorized' })

      const token = signDeviceJwt(deviceId)
      res.json({ ok: true, token })
    })
    .catch(() => res.status(500).json({ error: 'server_error' }))
})

app.post('/ui/login', (req, res) => {
  const password = String(req.body?.password || '');
  if (password !== ADMIN_PASSWORD) return res.status(401).json({ error: 'invalid_credentials' });
  req.session.isAdmin = true;
  res.json({ ok: true });
});

app.post('/ui/logout', (req, res) => {
  if (!req.session) return res.json({ ok: true });
  req.session.destroy(() => {
    res.json({ ok: true });
  });
});

app.get('/ui/me', (req, res) => {
  res.json({ ok: true, isAdmin: isUiAuthed(req) });
});

app.get('/health', (_req, res) => {
  res.json({ ok: true });
});

app.get('/admin/devices', (req, res) => {
  if (!isAuthorized(req)) return res.status(401).json({ error: 'unauthorized' });

  getDevicesList()
    .then((list) => res.json({ devices: list }))
    .catch(() => res.status(500).json({ error: 'server_error' }))
});

app.get('/admin/devices/:deviceId/commands', (req, res) => {
  if (!isAuthorized(req)) return res.status(401).json({ error: 'unauthorized' });
  if (!USE_DB) return res.status(400).json({ error: 'db_disabled' })

  const deviceId = String(req.params.deviceId);
  const status = String(req.query.status || 'queued')
  if (status !== 'queued') return res.status(400).json({ error: 'unsupported_status' })

  listQueuedCommands(deviceId)
    .then((cmds) => res.json({ deviceId, commands: cmds ?? [] }))
    .catch(() => res.status(500).json({ error: 'server_error' }))
})

app.post('/admin/devices/:deviceId/wake', (req, res) => {
  if (!isAuthorized(req)) return res.status(401).json({ error: 'unauthorized' });

  const deviceId = String(req.params.deviceId);
  if (!deviceId.trim()) return res.status(400).json({ ok: false, error: 'missing_device_id' })
  const fcmToken = req.body?.fcmToken
  wakeDeviceWithTokenOverride(deviceId, fcmToken)
    .then((r) => {
      if (!r.ok) return res.status(400).json(r)
      res.json(r)
    })
    .catch(() => res.status(500).json({ error: 'server_error' }))
})

app.post('/admin/devices/:deviceId/commands/sendQueued', (req, res) => {
  if (!isAuthorized(req)) return res.status(401).json({ error: 'unauthorized' });
  if (!USE_DB) return res.status(400).json({ error: 'db_disabled' })

  const deviceId = String(req.params.deviceId);
  sendQueuedCommandsNow(deviceId)
    .then((r) => {
      if (!r.ok) {
        if (r.error === 'device_not_connected') return res.status(409).json(r)
        return res.status(400).json(r)
      }
      res.json(r)
    })
    .catch(() => res.status(500).json({ error: 'server_error' }))
})

app.post('/ui/devices/:deviceId/wake', (req, res) => {
  if (!isUiAuthed(req)) return res.status(401).json({ error: 'unauthorized' });

  const deviceId = String(req.params.deviceId);
  if (!deviceId.trim()) return res.status(400).json({ ok: false, error: 'missing_device_id' })
  const fcmToken = req.body?.fcmToken
  wakeDeviceWithTokenOverride(deviceId, fcmToken)
    .then((r) => {
      if (!r.ok) return res.status(400).json(r)
      res.json(r)
    })
    .catch(() => res.status(500).json({ error: 'server_error' }))
})

app.get('/admin/devices/:deviceId/status', (req, res) => {
  if (!isAuthorized(req)) return res.status(401).json({ error: 'unauthorized' });
  const deviceId = String(req.params.deviceId);

  getDeviceStatus(deviceId)
    .then((s) => {
      if (!s) return res.status(404).json({ error: 'device_not_found' })
      res.json(s)
    })
    .catch(() => res.status(500).json({ error: 'server_error' }))
});

app.post('/admin/devices/:deviceId/command', (req, res) => {
  if (!isAuthorized(req)) return res.status(401).json({ error: 'unauthorized' });

  const deviceId = String(req.params.deviceId);
  const device = devices.get(deviceId);
  if (!USE_DB && !device) return res.status(404).json({ error: 'device_not_connected' });

  const { type, payload } = req.body || {};
  if (!type || typeof type !== 'string') return res.status(400).json({ error: 'missing_type' });

  const cmdId = `${Date.now()}-${Math.random().toString(16).slice(2)}`;

  const persist = !USE_DB
    ? Promise.resolve()
    : getDb().command.create({
        data: {
          id: cmdId,
          deviceId,
          type,
          payload: payload ?? null,
          status: device ? 'sent' : 'queued'
        }
      })

  persist
    .then(() => {
      if (!device) return res.json({ ok: true, id: cmdId, queued: true })
      const msg = JSON.stringify({ kind: 'command', id: cmdId, type, payload: payload ?? null });
      try {
        device.socket.send(msg);
      } catch {
        return res.status(500).json({ error: 'send_failed' });
      }
      res.json({ ok: true, id: cmdId });
    })
    .catch(() => res.status(500).json({ error: 'server_error' }))
});

app.get('/ui/devices', (req, res) => {
  if (!isUiAuthed(req)) return res.status(401).json({ error: 'unauthorized' });

  getDevicesList()
    .then((list) => res.json({ devices: list }))
    .catch(() => res.status(500).json({ error: 'server_error' }))
});

app.post('/ui/devices/:deviceId/command', (req, res) => {
  if (!isUiAuthed(req)) return res.status(401).json({ error: 'unauthorized' });

  const deviceId = String(req.params.deviceId);
  const device = devices.get(deviceId);
  if (!USE_DB && !device) return res.status(404).json({ error: 'device_not_connected' });

  const { type, payload } = req.body || {};
  if (!type || typeof type !== 'string') return res.status(400).json({ error: 'missing_type' });

  const cmdId = `${Date.now()}-${Math.random().toString(16).slice(2)}`;

  const persist = !USE_DB
    ? Promise.resolve()
    : getDb().command.create({
        data: {
          id: cmdId,
          deviceId,
          type,
          payload: payload ?? null,
          status: device ? 'sent' : 'queued'
        }
      })

  persist
    .then(() => {
      if (!device) return res.json({ ok: true, id: cmdId, queued: true })
      const msg = JSON.stringify({ kind: 'command', id: cmdId, type, payload: payload ?? null });
      try {
        device.socket.send(msg);
      } catch {
        return res.status(500).json({ error: 'send_failed' });
      }
      res.json({ ok: true, id: cmdId });
    })
    .catch(() => res.status(500).json({ error: 'server_error' }))
});

app.get('/ui/devices/:deviceId/commands', (req, res) => {
  if (!isUiAuthed(req)) return res.status(401).json({ error: 'unauthorized' });
  if (!USE_DB) return res.status(400).json({ error: 'db_disabled' })

  const deviceId = String(req.params.deviceId);
  const status = String(req.query.status || 'queued')
  if (status !== 'queued') return res.status(400).json({ error: 'unsupported_status' })

  listQueuedCommands(deviceId)
    .then((cmds) => res.json({ deviceId, commands: cmds ?? [] }))
    .catch(() => res.status(500).json({ error: 'server_error' }))
})

app.post('/ui/devices/:deviceId/commands/sendQueued', (req, res) => {
  if (!isUiAuthed(req)) return res.status(401).json({ error: 'unauthorized' });
  if (!USE_DB) return res.status(400).json({ error: 'db_disabled' })

  const deviceId = String(req.params.deviceId);
  sendQueuedCommandsNow(deviceId)
    .then((r) => {
      if (!r.ok) {
        if (r.error === 'device_not_connected') return res.status(409).json(r)
        return res.status(400).json(r)
      }
      res.json(r)
    })
    .catch(() => res.status(500).json({ error: 'server_error' }))
})

const server = http.createServer(app);

const wss = new WebSocketServer({ server, path: '/ws/device' });

wss.on('connection', (socket, req) => {
  // eslint-disable-next-line no-console
  console.log('ws connection attempt:', req.url);
  try {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const deviceId = String(url.searchParams.get('deviceId') || '').trim();
    const token = String(url.searchParams.get('token') || '').trim();
    const deviceJwt = String(url.searchParams.get('jwt') || '').trim();

    const jwtOk = deviceJwt ? verifyDeviceJwt(deviceJwt) : null

    if (!deviceId || (jwtOk ? String(jwtOk.sub) !== deviceId : token !== ENROLL_TOKEN)) {
      // eslint-disable-next-line no-console
      console.log('ws unauthorized:', { deviceId, tokenPresent: Boolean(token), jwtPresent: Boolean(deviceJwt) });
      socket.close(1008, 'unauthorized');
      return;
    }

    const now = Date.now();
    // eslint-disable-next-line no-console
    console.log('ws device connected:', deviceId);
    devices.set(deviceId, { deviceId, connectedAt: now, lastSeenAt: now, socket, lastStatus: null });

    if (USE_DB) {
      const db = getDb()
      db.device.upsert({
        where: { id: deviceId },
        create: { id: deviceId, lastSeenAt: new Date(now) },
        update: { lastSeenAt: new Date(now) }
      }).catch(() => {})
    }

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

        if (USE_DB) {
          const db = getDb()
          const socksRunning = Boolean(obj.socksRunning)
          const socksPort = Number.isFinite(obj.socksPort) ? Number(obj.socksPort) : 1080
          const credsConfigured = Boolean(obj.credsConfigured)
          const fcmToken = typeof obj.fcmToken === 'string' && obj.fcmToken.trim() ? obj.fcmToken.trim() : null

          db.device
            .upsert({
              where: { id: deviceId },
              create: { id: deviceId, fcmToken, lastSeenAt: new Date() },
              update: { fcmToken, lastSeenAt: new Date() }
            })
            .then(() =>
              db.deviceStatus.upsert({
                where: { deviceId },
                create: { deviceId, socksRunning, socksPort, credsConfigured },
                update: { socksRunning, socksPort, credsConfigured }
              })
            )
            .catch(() => {})
        }
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
  if (USE_DB) {
    console.log('DB mode enabled');
  }

  ensureAdminUser().catch(() => {})
});
