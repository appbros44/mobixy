const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8787';

const TOKEN_KEY = 'mobixy_admin_jwt'

export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

export function setToken(token) {
  try {
    if (!token) localStorage.removeItem(TOKEN_KEY)
    else localStorage.setItem(TOKEN_KEY, token)
  } catch {
  }
}

async function request(path, options = {}) {
  const token = getToken()
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {})
    }
  });

  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }

  if (!res.ok) {
    const msg = json?.error || `http_${res.status}`;
    throw new Error(msg);
  }

  return json;
}

export async function uiMe() {
  return request('/auth/me', { method: 'GET', headers: {} });
}

export async function uiLogin(email, password) {
  const r = await request('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  });
  if (r?.token) setToken(r.token)
  return r
}

export async function uiLogout() {
  setToken('')
  return { ok: true }
}

export async function uiDevices() {
  return request('/admin/devices', { method: 'GET', headers: {} });
}

export async function uiCommand(deviceId, type, payload = {}) {
  return request(`/admin/devices/${encodeURIComponent(deviceId)}/command`, {
    method: 'POST',
    body: JSON.stringify({ type, payload })
  });
}

export async function wakeDevice(deviceId) {
  return request(`/admin/devices/${encodeURIComponent(deviceId)}/wake`, {
    method: 'POST',
    body: JSON.stringify({})
  })
}

export async function listQueuedCommands(deviceId) {
  return request(`/admin/devices/${encodeURIComponent(deviceId)}/commands?status=queued`, {
    method: 'GET',
    headers: {}
  })
}

export async function sendQueuedNow(deviceId) {
  return request(`/admin/devices/${encodeURIComponent(deviceId)}/commands/sendQueued`, {
    method: 'POST',
    body: JSON.stringify({})
  })
}
