// Browser side of YTM Bridge. Protocol: relay/PROTOCOL.md
// All text from the phone or YouTube is inserted with textContent, never as HTML.

const $ = (id) => document.getElementById(id);
const PAIRING_KEY = 'ytm-bridge.pairing';
const RELAY_OVERRIDE_KEY = 'ytm-bridge.relay';
const PLAY_PATH = 'M8 5v14l11-7z';
const PAUSE_PATH = 'M6 5h4v14H6zm8 0h4v14h-4z';

const ERRORS = {
  device_offline: 'Your phone is offline.',
  nothing_playing: 'Nothing is playing on your phone yet. Pick a song below.',
  overlay_permission_missing: 'On your phone, allow Music Remote to "display over other apps" so it can start songs.',
  rate_limited: 'Slow down a little.',
  invalid_code: 'That code is wrong or has expired. Get a fresh one from the app.',
  timeout: 'Your phone didn\'t answer in time.',
};

// ------------------------------------------------------------------ config

function relayUrl() {
  const params = new URLSearchParams(location.search);
  if (params.has('relay')) {
    const value = params.get('relay');
    try {
      if (value) localStorage.setItem(RELAY_OVERRIDE_KEY, value);
      else localStorage.removeItem(RELAY_OVERRIDE_KEY);
    } catch { /* storage unavailable */ }
    history.replaceState(null, '', location.pathname);
  }
  let override = null;
  try { override = localStorage.getItem(RELAY_OVERRIDE_KEY); } catch { /* ignore */ }
  return (override || window.YTM_BRIDGE_CONFIG.relayUrl).replace(/\/+$/, '');
}

const RELAY = relayUrl();

function loadPairing() {
  try {
    const p = JSON.parse(localStorage.getItem(PAIRING_KEY));
    return p?.deviceId && p?.clientId && p?.token ? p : null;
  } catch {
    return null;
  }
}

function savePairing(p) {
  try { localStorage.setItem(PAIRING_KEY, JSON.stringify(p)); } catch { /* private mode: lasts this tab */ }
}

function clearPairing() {
  try { localStorage.removeItem(PAIRING_KEY); } catch { /* ignore */ }
}

// ------------------------------------------------------------------- state

let pairing = loadPairing();
let socket = null;
let reconnectTimer = null;
let keepAliveTimer = null;
let backoff = 1000;
let nextId = 1;
const pending = new Map(); // command id -> { resolve, reject, timer }

const view = {
  connected: false,
  deviceOnline: false,
  state: null,
  artwork: null,
  receivedAt: 0,    // browser clock when state arrived
  volumeHeld: false,
};

// ----------------------------------------------------------------- pairing

function deviceName() {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
    : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS'
    : /Mac OS X/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? `${browser} on ${os}` : browser;
}

$('pairCode').addEventListener('input', (e) => {
  // Format as XXXX-XXXX while typing; ambiguous characters are never issued.
  const raw = e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8);
  e.target.value = raw.length > 4 ? `${raw.slice(0, 4)}-${raw.slice(4)}` : raw;
});

$('pairForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const code = $('pairCode').value.trim();
  if (code.replace('-', '').length !== 8) return showPairMessage('Enter the 8-character code from the app.');

  $('pairBtn').disabled = true;
  $('pairMsg').hidden = true;
  try {
    const res = await fetch(`${RELAY}/v1/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, name: deviceName() }),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(ERRORS[body.error] ?? `Pairing failed (${res.status}).`);

    pairing = { deviceId: body.deviceId, clientId: body.clientId, token: body.token, pairedAt: Date.now() };
    savePairing(pairing);
    $('pairCode').value = '';
    showRemote();
  } catch (err) {
    showPairMessage(err instanceof TypeError ? 'Couldn\'t reach the relay. Check your connection.' : err.message);
  } finally {
    $('pairBtn').disabled = false;
  }
});

function showPairMessage(text) {
  $('pairMsg').textContent = text;
  $('pairMsg').hidden = false;
}

$('unlink').addEventListener('click', () => {
  if (!confirm('Unlink this browser from your phone? You\'ll need a new code to link it again.')) return;
  forget('Unlinked. You can revoke old browsers from the app too.');
});

function forget(message) {
  clearPairing();
  pairing = null;
  disconnect();
  showPair(message);
}

// -------------------------------------------------------------- connection

function connect() {
  clearTimeout(reconnectTimer);
  if (!pairing || socket) return;

  const ws = new WebSocket(`${RELAY.replace(/^http/, 'ws')}/v1/ws?device=${encodeURIComponent(pairing.deviceId)}`);
  socket = ws;
  setStatus('connecting');

  ws.addEventListener('open', () => {
    ws.send(JSON.stringify({ type: 'auth', role: 'client', clientId: pairing.clientId, token: pairing.token }));
  });

  ws.addEventListener('message', (event) => {
    if (event.data === 'pong') return;
    let msg;
    try { msg = JSON.parse(event.data); } catch { return; }
    handle(msg);
  });

  ws.addEventListener('close', (event) => {
    if (socket !== ws) return;
    socket = null;
    clearInterval(keepAliveTimer);
    view.connected = false;
    failPending('connection_lost');

    if (event.code === 4001 || event.code === 4003) {
      forget(event.code === 4003
        ? 'This browser was unlinked from the phone.'
        : 'This browser is no longer linked. Enter a new code from the app.');
      return;
    }
    setStatus('reconnecting');
    render();
    reconnectTimer = setTimeout(connect, backoff + Math.random() * 500);
    backoff = Math.min(backoff * 2, 30_000);
  });
}

function disconnect() {
  clearTimeout(reconnectTimer);
  clearInterval(keepAliveTimer);
  const ws = socket;
  socket = null;
  ws?.close(1000);
  failPending('connection_lost');
}

function handle(msg) {
  switch (msg.type) {
    case 'ready':
      backoff = 1000;
      view.connected = true;
      view.deviceOnline = msg.deviceOnline;
      setState(msg.state);
      view.artwork = msg.artwork;
      clearInterval(keepAliveTimer);
      // Keeps proxies and NATs from silently dropping an idle socket.
      keepAliveTimer = setInterval(() => socket?.readyState === 1 && socket.send('ping'), 30_000);
      setStatus('ready');
      break;
    case 'state':
      setState(msg.state);
      break;
    case 'artwork':
      view.artwork = msg.artwork;
      break;
    case 'presence':
      view.deviceOnline = msg.deviceOnline;
      setStatus('ready');
      break;
    case 'result': {
      const p = pending.get(msg.id);
      if (!p) return;
      pending.delete(msg.id);
      clearTimeout(p.timer);
      if (msg.ok) p.resolve(msg.data);
      else p.reject(new Error(msg.error));
      return;
    }
    default:
      return;
  }
  render();
}

function setState(state) {
  view.state = state;
  view.receivedAt = Date.now();
}

/** Sends a command to the phone; resolves with its reply. */
function command(action, args = {}, timeoutMs = 10_000) {
  if (!socket || socket.readyState !== 1 || !view.connected) return Promise.reject(new Error('device_offline'));
  const id = String(nextId++);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error('timeout'));
    }, timeoutMs);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ type: 'cmd', id, action, args }));
  });
}

function failPending(reason) {
  for (const [id, p] of pending) {
    clearTimeout(p.timer);
    p.reject(new Error(reason));
    pending.delete(id);
  }
}

function explain(err) {
  const key = String(err?.message ?? err);
  return ERRORS[key] ?? (key.startsWith('search_failed') ? 'Search failed on your phone. Try again.' : key);
}

// ----------------------------------------------------------------- views

function showPair(message) {
  $('remoteView').hidden = true;
  $('pairView').hidden = false;
  $('unlink').hidden = true;
  $('status').hidden = true;
  if (message) showPairMessage(message);
  else $('pairMsg').hidden = true;
  $('pairCode').focus();
}

function showRemote() {
  $('pairView').hidden = true;
  $('remoteView').hidden = false;
  $('unlink').hidden = false;
  $('status').hidden = false;
  render();
  connect();
}

function setStatus(kind) {
  const el = $('status');
  const text = kind === 'ready'
    ? (view.deviceOnline ? 'Phone connected' : 'Phone offline')
    : kind === 'connecting' ? 'Connecting…' : 'Reconnecting…';
  const online = kind === 'ready' && view.deviceOnline;
  el.className = `status ${online ? 'online' : kind === 'ready' ? 'offline' : 'pending'}`;
  $('statusText').textContent = text;
}

function render() {
  const s = view.state;
  $('offline').hidden = !view.connected || view.deviceOnline;

  const hasTrack = !!s?.title;
  $('title').textContent = hasTrack ? s.title : 'Nothing playing';
  $('artist').textContent = hasTrack ? [s.artist, s.album].filter(Boolean).join(' — ') : 'Search for something below.';
  $('source').textContent = hasTrack ? s.source ?? '' : '';
  $('playIcon').setAttribute('d', s?.playback === 'playing' ? PAUSE_PATH : PLAY_PATH);

  const art = view.artwork;
  if (hasTrack && art?.data && (!s.artworkKey || art.key === s.artworkKey)) {
    const src = `data:${art.mime || 'image/jpeg'};base64,${art.data}`;
    if ($('art').src !== src) $('art').src = src;
    $('art').hidden = false;
    $('artBlank').hidden = true;
  } else {
    $('art').hidden = true;
    $('artBlank').hidden = false;
  }

  if (s?.maxVolume && !view.volumeHeld) {
    $('volume').max = s.maxVolume;
    $('volume').value = s.volume;
    $('volumeValue').textContent = s.volume;
  }

  for (const btn of document.querySelectorAll('[data-action]')) btn.disabled = !view.deviceOnline;
  $('volume').disabled = !view.deviceOnline;

  tick();
}

/** Position is extrapolated locally between the phone's (sparse) updates. */
function currentPosition() {
  const s = view.state;
  if (s?.positionMs == null) return null;
  let pos = s.positionMs;
  // With the phone gone there's no evidence anything is still playing, so freeze.
  if (s.playback === 'playing' && view.deviceOnline) pos += Date.now() - (s.positionAt ?? view.receivedAt);
  return s.durationMs ? Math.min(Math.max(pos, 0), s.durationMs) : Math.max(pos, 0);
}

function tick() {
  const s = view.state;
  const pos = currentPosition();
  $('elapsed').textContent = pos == null ? '0:00' : fmt(pos);
  $('total').textContent = s?.durationMs ? fmt(s.durationMs) : '--:--';
  $('fill').style.width = pos != null && s?.durationMs ? `${(pos / s.durationMs) * 100}%` : '0%';
}
setInterval(tick, 500);

function fmt(ms) {
  const total = Math.floor(ms / 1000);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

let toastTimer;
function toast(text) {
  $('toast').textContent = text;
  $('toast').hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4000);
}

// -------------------------------------------------------------- controls

for (const btn of document.querySelectorAll('[data-action]')) {
  btn.addEventListener('click', async () => {
    const action = btn.dataset.action;
    // Flip the icon straight away; the phone's next state confirms or corrects it.
    if (action === 'playPause' && view.state) {
      view.state = {
        ...view.state,
        playback: view.state.playback === 'playing' ? 'paused' : 'playing',
        positionMs: currentPosition(),
        positionAt: Date.now(),
      };
      render();
    }
    try {
      await command(action);
    } catch (err) {
      toast(explain(err));
    }
  });
}

$('track').addEventListener('click', async (e) => {
  const s = view.state;
  if (!s?.durationMs || !view.deviceOnline) return;
  const rect = e.currentTarget.getBoundingClientRect();
  const positionMs = Math.round(((e.clientX - rect.left) / rect.width) * s.durationMs);
  view.state = { ...s, positionMs, positionAt: Date.now() };
  tick();
  try {
    await command('seek', { positionMs });
  } catch (err) {
    toast(explain(err));
  }
});

$('track').addEventListener('keydown', (e) => {
  const s = view.state;
  if (!s?.durationMs || !['ArrowLeft', 'ArrowRight'].includes(e.key)) return;
  e.preventDefault();
  const positionMs = Math.max(0, Math.min(s.durationMs, (currentPosition() ?? 0) + (e.key === 'ArrowRight' ? 10_000 : -10_000)));
  view.state = { ...s, positionMs, positionAt: Date.now() };
  tick();
  command('seek', { positionMs }).catch((err) => toast(explain(err)));
});

$('volume').addEventListener('input', () => {
  view.volumeHeld = true;
  $('volumeValue').textContent = $('volume').value;
});

$('volume').addEventListener('change', async () => {
  try {
    await command('volume', { value: Number($('volume').value) });
  } catch (err) {
    toast(explain(err));
  } finally {
    view.volumeHeld = false;
  }
});

// ----------------------------------------------------------------- search

let searchTimer;
let searchSeq = 0;

$('query').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(search, 400);
});

$('query').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    clearTimeout(searchTimer);
    search();
  }
});

async function search() {
  const query = $('query').value.trim();
  const results = $('results');
  if (!query) {
    results.replaceChildren();
    return;
  }
  const seq = ++searchSeq;
  results.replaceChildren(note('Searching on your phone…'));
  try {
    const items = await command('search', { query }, 25_000);
    if (seq !== searchSeq) return;
    results.replaceChildren(...(items.length ? items.map(resultRow) : [note('No results.')]));
  } catch (err) {
    if (seq !== searchSeq) return;
    results.replaceChildren(note(explain(err)));
  }
}

function note(text) {
  const el = document.createElement('div');
  el.className = 'empty';
  el.textContent = text;
  return el;
}

function resultRow(r) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'result';

  const img = document.createElement('img');
  img.alt = '';
  img.loading = 'lazy';
  img.referrerPolicy = 'no-referrer';
  if (r.thumbnail) img.src = r.thumbnail.replace(/=w\d+-h\d+/, '=w120-h120');

  const info = document.createElement('div');
  info.className = 'info';
  const name = document.createElement('div');
  name.className = 'n';
  name.textContent = r.title;
  const sub = document.createElement('div');
  sub.className = 'a';
  sub.textContent = [r.artist, r.album].filter(Boolean).join(' — ');
  info.append(name, sub);

  const dur = document.createElement('div');
  dur.className = 'd';
  dur.textContent = r.duration ?? '';

  row.append(img, info, dur);
  row.addEventListener('click', async () => {
    row.classList.add('busy');
    try {
      await command('playVideo', { videoId: r.videoId });
      toast(`Playing "${r.title}" on your phone`);
    } catch (err) {
      toast(explain(err));
    } finally {
      row.classList.remove('busy');
    }
  });
  return row;
}

// ------------------------------------------------------------------- boot

document.addEventListener('visibilitychange', () => {
  // Mobile browsers kill sockets in the background; come straight back.
  if (!document.hidden && pairing && !socket) {
    backoff = 1000;
    connect();
  }
});

if (pairing) showRemote();
else showPair();
