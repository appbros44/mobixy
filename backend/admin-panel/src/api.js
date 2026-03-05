const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8787';

async function request(path, options = {}) {
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    },
    credentials: 'include'
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
  return request('/ui/me', { method: 'GET', headers: {} });
}

export async function uiLogin(password) {
  return request('/ui/login', {
    method: 'POST',
    body: JSON.stringify({ password })
  });
}

export async function uiLogout() {
  return request('/ui/logout', {
    method: 'POST',
    body: JSON.stringify({})
  });
}

export async function uiDevices() {
  return request('/ui/devices', { method: 'GET', headers: {} });
}

export async function uiCommand(deviceId, type, payload = {}) {
  return request(`/ui/devices/${encodeURIComponent(deviceId)}/command`, {
    method: 'POST',
    body: JSON.stringify({ type, payload })
  });
}
