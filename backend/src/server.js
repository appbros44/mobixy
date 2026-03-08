import 'dotenv/config'
import http from 'http';
import express from 'express'
import { WebSocketServer } from 'ws'
import { getDb } from './db.js'
import { sendWakePush } from './fcm.js'
import jwt from 'jsonwebtoken'
import crypto from 'crypto'

const app = express()
const PORT = process.env.PORT || 8787

const ENROLL_TOKEN = process.env.ENROLL_TOKEN || 'dev-enroll-token'
const ADMIN_EMAIL = process.env.ADMIN_EMAIL || ''
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin'
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
      publicIp: d.publicIp ?? null,
      lanIp: d.lanIp ?? null,
      proxyUsername: d.proxyUsername ?? null,
      proxyPassword: d.proxyPassword ?? null,
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
      publicIp: mem?.publicIp ?? d.publicIp ?? null,
      lanIp: mem?.lanIp ?? d.lanIp ?? null,
      proxyUsername: mem?.proxyUsername ?? d.proxyUsername ?? null,
      proxyPassword: mem?.proxyPassword ?? d.proxyPassword ?? null,
      connectedAt: mem?.connectedAt ?? null,
      lastSeenAt: mem?.lastSeenAt ?? (d.lastSeenAt ? d.lastSeenAt.getTime() : null),
      online,
      status
    }
  })
}

function normalizeIp(ip) {
  const s = String(ip || '').trim()
  if (!s) return null
  if (s.startsWith('::ffff:')) return s.slice('::ffff:'.length)
  return s
}

function getReqPublicIp(req) {
  const cf = normalizeIp(req.headers?.['cf-connecting-ip'])
  if (cf) return cf

  const xri = normalizeIp(req.headers?.['x-real-ip'])
  if (xri) return xri

  const xff = String(req.headers?.['x-forwarded-for'] || '').trim()
  if (xff) {
    const first = xff.split(',')[0]?.trim()
    const n = normalizeIp(first)
    if (n) return n
  }

  return normalizeIp(req.socket?.remoteAddress)
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

async function wakeDeviceWithTokenOverride(deviceId, fcmToken) {
  const token = typeof fcmToken === 'string' && fcmToken.trim() ? fcmToken.trim() : await getDeviceFcmToken(deviceId)
  if (!token) return { ok: false, error: 'missing_fcm_token' }
  const r = await sendWakePush({ token, deviceId })
  return { ok: true, messageId: r.id }
}

function isAllowedUiOrigin(origin) {
  if (!origin) return false

  const o = String(origin).trim()
  if (!o) return false
  if (o === UI_ORIGIN) return true

  try {
    const ui = new URL(UI_ORIGIN)
    const req = new URL(o)
    if (ui.protocol !== req.protocol) return false
    if (ui.port !== req.port) return false

    const uiHost = ui.hostname
    const reqHost = req.hostname
    const hostOk =
      uiHost === reqHost ||
      (uiHost === 'localhost' && reqHost === '127.0.0.1') ||
      (uiHost === '127.0.0.1' && reqHost === 'localhost')

    return hostOk
  } catch {
    return false
  }
}

app.use((req, res, next) => {
  const p = req.path || ''
  if (p.startsWith('/auth/') || p.startsWith('/admin/')) {
    const origin = String(req.headers.origin || '').trim()
    if (origin && isAllowedUiOrigin(origin)) {
      res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Vary', 'Origin')
      res.setHeader('Access-Control-Allow-Credentials', 'true');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
      res.setHeader('Access-Control-Allow-Methods', 'GET,POST,DELETE,PUT,PATCH,OPTIONS');
      if (req.method === 'OPTIONS') return res.status(204).end();
    }
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

  return false
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

app.delete('/admin/devices/:deviceId', (req, res) => {
  if (!isAuthorized(req)) return res.status(401).json({ error: 'unauthorized' });
  if (!USE_DB) return res.status(400).json({ error: 'db_disabled' })

  const deviceId = String(req.params.deviceId).trim()
  if (!deviceId) return res.status(400).json({ error: 'missing_device_id' })

  const mem = devices.get(deviceId)
  if (mem?.socket) {
    try {
      mem.socket.close(1000, 'deleted')
    } catch {
    }
  }
  devices.delete(deviceId)

  const db = getDb()
  db.device
    .delete({ where: { id: deviceId } })
    .then(() => res.json({ ok: true, deviceId }))
    .catch((e) => {
      // Prisma throws if record missing
      if (String(e?.code || '') === 'P2025') return res.status(404).json({ error: 'device_not_found' })
      res.status(500).json({ error: 'server_error' })
    })
})

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


const server = http.createServer(app);

// HTTP proxy listener for Phase 2 (supports both HTTP and HTTPS)
const proxyConnections = new Map() // streamId -> { socket, deviceId, targetHost, targetPort }

// Handle regular HTTP requests through proxy
app.use('/proxy', (req, res, next) => {
  console.log('Proxy request:', req.method, req.url)
  
  // Only handle proxy requests
  if (!req.headers['x-proxy-key']) {
    return res.status(401).json({ error: 'Missing API key' })
  }

  const apiKey = req.headers['x-proxy-key']
  if (apiKey.length < 10) {
    return res.status(401).json({ error: 'Invalid API key' })
  }

  // Get online devices for round-robin selection
  const onlineDevices = Array.from(devices.entries()).filter(([_, device]) => {
    const lastSeen = device.lastSeenAt || 0
    return Date.now() - lastSeen < 60000
  })

  console.log('Online devices count:', onlineDevices.length)

  if (onlineDevices.length === 0) {
    return res.status(502).json({ error: 'No devices available' })
  }

  // Round-robin device selection
  const deviceIndex = Math.floor(Math.random() * onlineDevices.length)
  const [deviceId, device] = onlineDevices[deviceIndex]
  console.log('Selected device:', deviceId)

  // Generate unique stream ID
  const streamId = crypto.randomBytes(16).toString('hex')

  // Parse target URL - remove /proxy/ prefix
  let targetUrl = req.url
  if (targetUrl.startsWith('/')) {
    targetUrl = targetUrl.substring(1)
  }
  
  console.log('Target URL:', targetUrl)
  
  if (!targetUrl) {
    return res.status(400).json({ error: 'Missing target URL' })
  }

  // Add protocol if missing
  if (!targetUrl.startsWith('http://') && !targetUrl.startsWith('https://')) {
    targetUrl = 'https://' + targetUrl
  }

  let url
  try {
    url = new URL(targetUrl)
    console.log('Parsed URL:', url.hostname, url.port || (url.protocol === 'https:' ? 443 : 80))
  } catch (e) {
    console.log('URL parse error:', e.message)
    return res.status(400).json({ error: 'Invalid target URL: ' + e.message })
  }

  // Store connection info
  proxyConnections.set(streamId, {
    res,
    deviceId,
    targetHost: url.hostname,
    targetPort: url.port || (url.protocol === 'https:' ? 443 : 80),
    isHttps: url.protocol === 'https:',
    method: req.method,
    headers: req.headers,
    body: req.body
  })

  // Send tunnel open request to device
  const deviceSocket = devices.get(deviceId)?.socket
  if (!deviceSocket || deviceSocket.readyState !== 1) {
    proxyConnections.delete(streamId)
    return res.status(502).json({ error: 'Device not connected' })
  }

  const openMsg = {
    t: 'open',
    sid: streamId,
    host: url.hostname,
    port: parseInt(url.port) || (url.protocol === 'https:' ? 443 : 80),
    timeoutMs: 10000
  }

  deviceSocket.send(JSON.stringify(openMsg))

  // Set timeout
  const timeout = setTimeout(() => {
    proxyConnections.delete(streamId)
    if (!res.headersSent) {
      res.status(504).json({ error: 'Gateway timeout' })
    }
  }, 10000)

  const conn = proxyConnections.get(streamId)
  if (conn) conn.timeout = timeout
})

// Keep CONNECT for direct connections (not through Cloudflare)
server.on('connect', (req, clientSocket, head) => {
  console.log('HTTP CONNECT request:', req.url)
  console.log('All headers:', JSON.stringify(req.headers, null, 2))
  
  // Check API key authentication - try multiple headers
  const apiKey = req.headers['x-proxy-key'] || req.headers['proxy-authorization']
  console.log('API key check - x-proxy-key:', req.headers['x-proxy-key'])
  console.log('API key check - proxy-authorization:', req.headers['proxy-authorization'])
  
  if (!apiKey) {
    console.log('Rejecting: No API key found')
    clientSocket.end('HTTP/1.1 401 Unauthorized\r\n\r\n')
    return
  }
  
  // Remove "Bearer " or "Basic " prefix if present
  let cleanKey = apiKey
  if (apiKey.startsWith('Bearer ')) {
    cleanKey = apiKey.substring(7)
  } else if (apiKey.startsWith('Basic ')) {
    cleanKey = apiKey.substring(6)
  }
  
  if (cleanKey.length < 10) {
    console.log('Rejecting: API key too short')
    clientSocket.end('HTTP/1.1 401 Unauthorized\r\n\r\n')
    return
  }

  // Parse target from URL (e.g., "example.com:443")
  const { hostname: targetHost, port: targetPort } = new URL(`http://${req.url}`)
  
  // Get online devices for round-robin selection
  const onlineDevices = Array.from(devices.entries()).filter(([_, device]) => {
    const lastSeen = device.lastSeenAt || 0
    return Date.now() - lastSeen < 60000 // Consider online if seen within last minute
  })

  if (onlineDevices.length === 0) {
    clientSocket.end('HTTP/1.1 502 No devices available\r\n\r\n')
    return
  }

  // Round-robin device selection
  const deviceIndex = Math.floor(Math.random() * onlineDevices.length)
  const [deviceId, device] = onlineDevices[deviceIndex]

  // Generate unique stream ID
  const streamId = crypto.randomBytes(16).toString('hex')

  // Store connection info
  proxyConnections.set(streamId, {
    socket: clientSocket,
    deviceId,
    targetHost,
    targetPort: parseInt(targetPort) || 80
  })

  // Send tunnel open request to device
  const deviceSocket = devices.get(deviceId)?.socket
  if (!deviceSocket || deviceSocket.readyState !== 1) {
    proxyConnections.delete(streamId)
    clientSocket.end('HTTP/1.1 502 Device not connected\r\n\r\n')
    return
  }

  const openMsg = {
    t: 'open',
    sid: streamId,
    host: targetHost,
    port: parseInt(targetPort) || 80,
    timeoutMs: 10000
  }

  deviceSocket.send(JSON.stringify(openMsg))

  // Set timeout for connection establishment
  const timeout = setTimeout(() => {
    proxyConnections.delete(streamId)
    clientSocket.end('HTTP/1.1 504 Gateway Timeout\r\n\r\n')
  }, 10000)

  // Store timeout for cleanup
  const conn = proxyConnections.get(streamId)
  if (conn) conn.timeout = timeout

  // Send 200 Connection established
  clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n')
})

// Handle tunnel binary data frames from devices
function handleTunnelBinaryData(deviceId, data) {
  try {
    // Parse tunnel frame: [4 bytes sid][1 byte flags][payload]
    if (data.length < 5) return

    const sid = data.readUInt32BE(0)
    const flags = data[1]
    const payload = data.slice(5)

    const conn = proxyConnections.get(sid.toString())
    if (!conn || conn.deviceId !== deviceId) return

    // For HTTP proxy, accumulate response and send when complete
    if (conn.res && !conn.res.headersSent) {
      // Check if this is the first chunk (HTTP response headers)
      if (!conn.responseBuffer) {
        conn.responseBuffer = Buffer.alloc(0)
      }
      
      conn.responseBuffer = Buffer.concat([conn.responseBuffer, payload])
      
      // Check if we have complete headers
      const headerEnd = conn.responseBuffer.toString().indexOf('\r\n\r\n')
      if (headerEnd !== -1) {
        // Parse response headers
        const headerText = conn.responseBuffer.toString().substring(0, headerEnd)
        const lines = headerText.split('\r\n')
        const statusLine = lines[0]
        const statusMatch = statusLine.match(/HTTP\/\d\.\d (\d{3})/)
        
        if (statusMatch) {
          const statusCode = parseInt(statusMatch[1])
          
          // Copy headers
          for (let i = 1; i < lines.length; i++) {
            const [key, ...valueParts] = lines[i].split(':')
            if (key && valueParts.length > 0) {
              const value = valueParts.join(':').trim()
              if (key.toLowerCase() !== 'connection' && 
                  key.toLowerCase() !== 'transfer-encoding' &&
                  key.toLowerCase() !== 'content-encoding') {
                conn.res.setHeader(key, value)
              }
            }
          }
          
          // Send status and remaining body
          conn.res.status(statusCode)
          const bodyStart = headerEnd + 4
          if (conn.responseBuffer.length > bodyStart) {
            conn.res.send(conn.responseBuffer.slice(bodyStart))
          } else {
            conn.res.end()
          }
        }
      }
    } else if (conn.res && conn.res.headersSent) {
      // Streaming additional data
      conn.res.write(payload)
    }
  } catch (error) {
    console.log('Error handling tunnel binary data:', error)
  }
}

// Handle tunnel responses from devices
function handleTunnelMessage(deviceId, message) {
  if (!message.t) return

  const { t, sid } = message
  const conn = proxyConnections.get(sid)

  console.log(`Tunnel message from ${deviceId}:`, message.t, 'sid:', sid, 'conn exists:', !!conn)

  if (!conn || conn.deviceId !== deviceId) {
    // Connection not found or wrong device
    if (conn) {
      clearTimeout(conn.timeout)
      proxyConnections.delete(sid)
    }
    return
  }

  switch (t) {
    case 'open_ok':
      // Device accepted connection
      console.log('Device accepted connection, sending HTTP request')
      clearTimeout(conn.timeout)
      conn.timeout = null
      
      // For HTTP proxy, send the request to device
      if (conn.res && !conn.res.headersSent) {
        // Build HTTP request to send through tunnel
        const path = conn.body?.url || '/'
        let httpRequest = `${conn.method} ${path} HTTP/1.1\r\n`
        httpRequest += `Host: ${conn.targetHost}\r\n`
        
        // Copy relevant headers
        for (const [key, value] of Object.entries(conn.headers)) {
          if (!key.startsWith('x-proxy') && key !== 'host') {
            httpRequest += `${key}: ${value}\r\n`
          }
        }
        
        httpRequest += '\r\n'
        
        console.log('Sending HTTP request through tunnel:', httpRequest.substring(0, 100) + '...')
        
        // Send request through tunnel
        const device = devices.get(conn.deviceId)
        if (device?.socket?.readyState === 1) {
          const sidInt = parseInt(sid)
          const frame = Buffer.allocUnsafe(5 + httpRequest.length)
          frame.writeUInt32BE(sidInt, 0)
          frame[4] = 0 // flags
          Buffer.from(httpRequest).copy(frame, 5)
          device.socket.send(frame)
        } else {
          console.log('Device socket not ready')
        }
      }
      break

    case 'open_fail':
      // Device rejected connection
      clearTimeout(conn.timeout)
      proxyConnections.delete(sid)
      if (conn.res && !conn.res.headersSent) {
        conn.res.status(502).json({ error: 'Device rejected connection' })
      }
      break

    case 'data':
      // Forward binary data to client (handled separately in binary message handler)
      break

    case 'close':
      // Device closed connection
      clearTimeout(conn.timeout)
      proxyConnections.delete(sid)
      if (conn.res && !conn.res.headersSent) {
        conn.res.status(502).json({ error: 'Device closed connection' })
      }
      break
  }
}

// Handle client socket close/errors and data forwarding
server.on('connection', (socket) => {
  // Track if this is a proxy connection
  let isProxySocket = false
  let streamId = null

  socket.on('data', (data) => {
    // Find the proxy connection for this socket
    if (!isProxySocket) {
      for (const [sid, conn] of proxyConnections.entries()) {
        if (conn.socket === socket) {
          isProxySocket = true
          streamId = sid
          break
        }
      }
    }

    // Forward data to device if this is a proxy connection
    if (isProxySocket && streamId) {
      const conn = proxyConnections.get(streamId)
      if (conn && conn.deviceId) {
        const device = devices.get(conn.deviceId)
        if (device?.socket?.readyState === 1) {
          // Create tunnel frame: [4 bytes sid][1 byte flags][payload]
          const sid = parseInt(streamId)
          const frame = Buffer.allocUnsafe(5 + data.length)
          frame.writeUInt32BE(sid, 0)
          frame[4] = 0 // flags
          data.copy(frame, 5)
          device.socket.send(frame)
        }
      }
    }
  })

  socket.on('close', () => {
    // Find and clean up any proxy connections using this socket
    for (const [sid, conn] of proxyConnections.entries()) {
      if (conn.socket === socket) {
        clearTimeout(conn.timeout)
        proxyConnections.delete(sid)

        // Notify device to close stream
        const device = devices.get(conn.deviceId)
        if (device?.socket?.readyState === 1) {
          device.socket.send(JSON.stringify({
            t: 'close',
            sid: sid,
            reason: 'client_close'
          }))
        }
        break
      }
    }
  })
})

const wss = new WebSocketServer({ server, path: '/ws/device' });

wss.on('connection', async (socket, req) => {
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

    if (USE_DB && jwtOk) {
      const db = getDb()
      const d = await db.device.findUnique({ where: { id: deviceId }, select: { id: true } })
      if (!d) {
        // eslint-disable-next-line no-console
        console.log('ws rejected: device deleted:', deviceId)
        socket.close(1008, 'unauthorized');
        return
      }
    }

    const now = Date.now();
    const publicIp = getReqPublicIp(req)
    // eslint-disable-next-line no-console
    console.log('ws device connected:', deviceId);
    devices.set(deviceId, {
      deviceId,
      connectedAt: now,
      lastSeenAt: now,
      socket,
      lastStatus: null,
      publicIp,
      lanIp: null,
      proxyUsername: null,
      proxyPassword: null
    });

    if (USE_DB) {
      const db = getDb()
      db.device.upsert({
        where: { id: deviceId },
        create: { id: deviceId, lastSeenAt: new Date(now), publicIp },
        update: { lastSeenAt: new Date(now), publicIp }
      }).catch((e) => {
        // eslint-disable-next-line no-console
        console.log('db upsert device (publicIp) failed:', e?.message || e)
      })
    }

    socket.on('message', (data) => {
      const device = devices.get(deviceId);
      if (device) device.lastSeenAt = Date.now();

      // Handle binary tunnel data frames
      if (Buffer.isBuffer(data)) {
        handleTunnelBinaryData(deviceId, data)
        return
      }

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

        const lanIp = typeof obj.lanIp === 'string' && obj.lanIp.trim() ? obj.lanIp.trim() : null
        const proxyUsername = typeof obj.proxyUsername === 'string' && obj.proxyUsername.trim() ? obj.proxyUsername.trim() : null
        const proxyPassword = typeof obj.proxyPassword === 'string' && obj.proxyPassword.trim() ? obj.proxyPassword.trim() : null
        if (lanIp) device.lanIp = lanIp
        if (proxyUsername) device.proxyUsername = proxyUsername
        if (proxyPassword) device.proxyPassword = proxyPassword

        if (USE_DB) {
          const db = getDb()
          const socksRunning = Boolean(obj.socksRunning)
          const socksPort = Number.isFinite(obj.socksPort) ? Number(obj.socksPort) : 1080
          const credsConfigured = Boolean(obj.credsConfigured)
          const fcmToken = typeof obj.fcmToken === 'string' && obj.fcmToken.trim() ? obj.fcmToken.trim() : null

          db.device
            .upsert({
              where: { id: deviceId },
              create: { id: deviceId, fcmToken, lastSeenAt: new Date(), lanIp, proxyUsername, proxyPassword },
              update: { fcmToken, lastSeenAt: new Date(), lanIp, proxyUsername, proxyPassword }
            })
            .then(() =>
              db.deviceStatus.upsert({
                where: { deviceId },
                create: { deviceId, socksRunning, socksPort, credsConfigured },
                update: { socksRunning, socksPort, credsConfigured }
              })
            )
            .catch((e) => {
              // eslint-disable-next-line no-console
              console.log('db upsert device/status (lanIp/creds/status) failed:', e?.message || e)
            })
        }
      }

      // Handle tunnel messages for Phase 2
      if (obj && obj.t) {
        handleTunnelMessage(deviceId, obj)
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
