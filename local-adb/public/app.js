const $ = (id) => document.getElementById(id);

const el = {
  status: $('status'), statusText: $('statusText'),
  setup: $('setup'), setupMsg: $('setupMsg'),
  pairAddress: $('pairAddress'), pairCode: $('pairCode'), pairBtn: $('pairBtn'),
  player: $('player'), art: $('art'), artBlank: $('artBlank'),
  title: $('title'), artist: $('artist'), source: $('source'),
  elapsed: $('elapsed'), total: $('total'), fill: $('fill'), ppIcon: $('ppIcon'),
  vol: $('vol'), volVal: $('volVal'),
  q: $('q'), type: $('type'), results: $('results'),
};

const PLAY_PATH = 'M8 5v14l11-7z';
const PAUSE_PATH = 'M6 5h4v14H6zm8 0h4v14h-4z';

let state = null;
let volumeHeld = false;

const fmt = (sec) => {
  if (sec == null || !isFinite(sec) || sec < 0) return '--:--';
  const s = Math.floor(sec);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

async function api(path, opts) {
  const res = await fetch(path, {
    headers: { 'content-type': 'application/json' },
    ...opts,
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || body.ok === false) throw new Error(body.error || `HTTP ${res.status}`);
  return body;
}

// -------------------------------------------------------------- rendering

function render() {
  if (!state) return;

  const online = state.connected;
  el.status.className = `status ${online ? 'online' : 'offline'}`;
  el.statusText.textContent = online ? (state.device ?? 'connected') : 'phone not connected';

  el.setup.hidden = online;
  el.player.hidden = !online;
  if (!online) return;

  const t = state.track;
  const playing = state.playback === 'playing';

  el.title.textContent = t?.title ?? 'Nothing playing';
  el.artist.textContent = t?.artist ?? '';
  el.source.textContent = t?.source ?? '';
  el.ppIcon.firstElementChild.setAttribute('d', playing ? PAUSE_PATH : PLAY_PATH);

  if (t?.thumbnail) {
    if (el.art.getAttribute('src') !== t.thumbnail) el.art.src = t.thumbnail;
    el.art.hidden = false;
    el.artBlank.hidden = true;
  } else {
    el.art.hidden = true;
    el.artBlank.hidden = false;
  }

  el.total.textContent = fmt(t?.durationSeconds);

  if (state.volume && !volumeHeld) {
    el.vol.max = state.volume.max;
    el.vol.value = state.volume.current;
    el.volVal.textContent = state.volume.current;
  }

  tickProgress();
}

/** dumpsys gives a position snapshot; advance it locally between polls. */
function tickProgress() {
  if (!state?.connected) return;
  const dur = state.track?.durationSeconds;
  let pos = state.position;

  if (pos != null && state.playback === 'playing' && state.positionAt) {
    pos += Date.now() - state.positionAt;
  }
  if (pos == null || pos < 0) {
    el.elapsed.textContent = '0:00';
    el.fill.style.width = '0%';
    return;
  }

  const secs = pos / 1000;
  el.elapsed.textContent = fmt(secs);
  el.fill.style.width = dur ? `${Math.min(100, (secs / dur) * 100)}%` : '0%';
}
setInterval(tickProgress, 500);

// ------------------------------------------------------------- transport

for (const btn of document.querySelectorAll('[data-cmd]')) {
  btn.addEventListener('click', async () => {
    btn.disabled = true;
    try {
      await api(`/api/command/${btn.dataset.cmd}`, { method: 'POST' });
    } catch (e) {
      console.error(e);
    } finally {
      btn.disabled = false;
    }
  });
}

el.vol.addEventListener('input', () => {
  volumeHeld = true;
  el.volVal.textContent = el.vol.value;
});
el.vol.addEventListener('change', async () => {
  try {
    await api('/api/volume', { method: 'POST', body: JSON.stringify({ set: Number(el.vol.value) }) });
  } catch (e) {
    console.error(e);
  } finally {
    volumeHeld = false;
  }
});

el.status.addEventListener('click', () => api('/api/connect', { method: 'POST' }).catch(() => {}));

// ----------------------------------------------------------------- pair

el.pairBtn.addEventListener('click', async () => {
  const address = el.pairAddress.value.trim();
  const code = el.pairCode.value.trim();
  if (!address || !code) return;

  el.pairBtn.disabled = true;
  el.setupMsg.hidden = true;
  try {
    await api('/api/pair', { method: 'POST', body: JSON.stringify({ address, code }) });
    el.setupMsg.className = 'msg good';
    el.setupMsg.textContent = 'Paired. Connecting…';
    el.setupMsg.hidden = false;
    el.pairCode.value = '';
    setTimeout(() => api('/api/connect', { method: 'POST' }).catch(() => {}), 500);
  } catch (e) {
    el.setupMsg.className = 'msg err';
    el.setupMsg.textContent = e.message;
    el.setupMsg.hidden = false;
  } finally {
    el.pairBtn.disabled = false;
  }
});

// --------------------------------------------------------------- search

let searchSeq = 0;

async function runSearch() {
  const q = el.q.value.trim();
  if (!q) { el.results.innerHTML = ''; return; }

  const seq = ++searchSeq;
  el.results.innerHTML = '<div class="empty">Searching…</div>';
  try {
    const { results } = await api(`/api/search?q=${encodeURIComponent(q)}&type=${el.type.value}`);
    if (seq !== searchSeq) return; // a newer search already landed
    renderResults(results);
  } catch (e) {
    if (seq !== searchSeq) return;
    el.results.innerHTML = `<div class="empty">${e.message}</div>`;
  }
}

function renderResults(results) {
  el.results.innerHTML = '';
  if (!results.length) {
    el.results.innerHTML = '<div class="empty">No results.</div>';
    return;
  }
  for (const r of results.slice(0, 20)) {
    const row = document.createElement('button');
    row.className = 'result';
    row.type = 'button';

    const img = document.createElement('img');
    if (r.thumbnail) img.src = r.thumbnail;
    img.alt = '';

    const info = document.createElement('div');
    info.className = 'info';
    const n = document.createElement('div');
    n.className = 'n';
    n.textContent = r.title ?? 'Unknown';
    const a = document.createElement('div');
    a.className = 'a';
    a.textContent = [r.artist, r.album].filter(Boolean).join(' — ');
    info.append(n, a);

    const d = document.createElement('div');
    d.className = 'd';
    d.textContent = r.durationText ?? '';

    row.append(img, info, d);
    row.addEventListener('click', () => play(r, row));
    el.results.append(row);
  }
}

async function play(r, row) {
  const body = ['album', 'playlist'].includes(el.type.value) && r.playlistId
    ? { playlistId: r.playlistId }
    : { videoId: r.videoId };
  row.style.opacity = '.5';
  try {
    await api('/api/play', { method: 'POST', body: JSON.stringify(body) });
  } catch (e) {
    alert(e.message);
  } finally {
    row.style.opacity = '';
  }
}

let debounce;
el.q.addEventListener('input', () => {
  clearTimeout(debounce);
  debounce = setTimeout(runSearch, 350);
});
el.q.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { clearTimeout(debounce); runSearch(); }
});
el.type.addEventListener('change', runSearch);

// ------------------------------------------------------------ live state

function connect() {
  const ws = new WebSocket(`ws://${location.host}`);
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.type === 'state') { state = msg.state; render(); }
  };
  ws.onclose = () => {
    el.status.className = 'status offline';
    el.statusText.textContent = 'server offline';
    setTimeout(connect, 2000);
  };
}
connect();
