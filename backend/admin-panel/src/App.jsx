import React, { useEffect, useMemo, useState } from 'react';
import { uiCommand, uiDevices, uiLogin, uiLogout, uiMe } from './api.js';

function formatTs(ms) {
  if (!ms) return '-';
  try {
    return new Date(ms).toLocaleString();
  } catch {
    return String(ms);
  }
}

export default function App() {
  const [loading, setLoading] = useState(true);
  const [isAdmin, setIsAdmin] = useState(false);
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  const [devices, setDevices] = useState([]);
  const [busyDeviceId, setBusyDeviceId] = useState('');

  const sortedDevices = useMemo(() => {
    return [...devices].sort((a, b) => String(a.deviceId).localeCompare(String(b.deviceId)));
  }, [devices]);

  async function refreshDevices() {
    const data = await uiDevices();
    setDevices(data.devices || []);
  }

  useEffect(() => {
    let mounted = true;
    (async () => {
      try {
        const me = await uiMe();
        if (!mounted) return;
        setIsAdmin(Boolean(me.isAdmin));
        if (me.isAdmin) {
          await refreshDevices();
        }
      } catch (e) {
        if (!mounted) return;
        setError(e.message || String(e));
      } finally {
        if (mounted) setLoading(false);
      }
    })();

    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    if (!isAdmin) return;
    const id = setInterval(() => {
      refreshDevices().catch(() => {
      });
    }, 2000);
    return () => clearInterval(id);
  }, [isAdmin]);

  async function onLogin(e) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await uiLogin(password);
      setIsAdmin(true);
      setPassword('');
      await refreshDevices();
    } catch (e2) {
      setError(e2.message || String(e2));
    } finally {
      setLoading(false);
    }
  }

  async function onLogout() {
    setError('');
    setLoading(true);
    try {
      await uiLogout();
      setIsAdmin(false);
      setDevices([]);
    } catch (e) {
      setError(e.message || String(e));
    } finally {
      setLoading(false);
    }
  }

  async function runCommand(deviceId, type, payload) {
    setError('');
    setBusyDeviceId(deviceId);
    try {
      await uiCommand(deviceId, type, payload);
      await refreshDevices();
    } catch (e) {
      setError(e.message || String(e));
    } finally {
      setBusyDeviceId('');
    }
  }

  if (loading) {
    return (
      <div className="container">
        <div className="card">
          <div className="title">Mobixy Admin</div>
          <div className="muted">Loading…</div>
        </div>
      </div>
    );
  }

  if (!isAdmin) {
    return (
      <div className="container">
        <div className="card">
          <div className="title">Mobixy Admin</div>
          <div className="muted">Login to manage connected devices.</div>
          <form onSubmit={onLogin} className="form">
            <label className="label">Admin Password</label>
            <input
              className="input"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Enter admin password"
              autoFocus
            />
            <button className="button" type="submit" disabled={!password.trim()}>
              Login
            </button>
          </form>
          {error ? <div className="error">{error}</div> : null}
        </div>
      </div>
    );
  }

  return (
    <div className="container">
      <div className="header">
        <div>
          <div className="title">Mobixy Admin</div>
          <div className="muted">Connected devices: {sortedDevices.length}</div>
        </div>
        <div className="row">
          <button className="button secondary" onClick={() => refreshDevices().catch(() => {})}>Refresh</button>
          <button className="button danger" onClick={onLogout}>Logout</button>
        </div>
      </div>

      {error ? <div className="error">{error}</div> : null}

      <div className="card">
        <table className="table">
          <thead>
            <tr>
              <th>Device</th>
              <th>Online</th>
              <th>Last Seen</th>
              <th>SOCKS</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {sortedDevices.length === 0 ? (
              <tr>
                <td colSpan="5" className="muted">No devices connected.</td>
              </tr>
            ) : null}

            {sortedDevices.map((d) => {
              const status = d.status || {};
              const socksRunning = Boolean(status.socksRunning);
              const busy = busyDeviceId === d.deviceId;

              return (
                <tr key={d.deviceId}>
                  <td>
                    <div className="mono">{d.deviceId}</div>
                  </td>
                  <td>{d.online ? 'yes' : 'no'}</td>
                  <td>{formatTs(d.lastSeenAt)}</td>
                  <td>
                    <div className={socksRunning ? 'pill ok' : 'pill off'}>
                      {socksRunning ? `running:${status.socksPort || 1080}` : 'stopped'}
                    </div>
                  </td>
                  <td>
                    <div className="row">
                      <button
                        className="button"
                        disabled={busy}
                        onClick={() => runCommand(d.deviceId, 'proxy_start', {})}
                      >
                        Start
                      </button>
                      <button
                        className="button secondary"
                        disabled={busy}
                        onClick={() => runCommand(d.deviceId, 'proxy_stop', {})}
                      >
                        Stop
                      </button>
                      <button
                        className="button secondary"
                        disabled={busy}
                        onClick={() => runCommand(d.deviceId, 'get_status', {})}
                      >
                        Status
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="footer muted">
        Tip: if you see “No devices connected”, make sure the phone Control Agent is connected and backend is reachable on LAN.
      </div>
    </div>
  );
}
