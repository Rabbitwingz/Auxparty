import express from 'express';
import { WebSocketServer } from 'ws';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import * as adb from './adb.js';
import * as ytm from './ytmusic.js';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PORT = Number(process.env.PORT) || 8781;

const POLL_MS = 1500;        // how often we read the phone's media session
const RECONNECT_MS = 10000;  // how often we retry a dropped device
const VOLUME_EVERY = 4;      // poll volume once every N state polls

const app = express();
app.use(express.json());
app.use(express.static(join(ROOT, 'public')));

const server = createServer(app);
const wss = new WebSocketServer({ server });

// ------------------------------------------------------------------- state

let state = {
  device: null,       // connected adb serial
  connected: false,
  error: null,
  track: null,        // { title, artist, album, thumbnail, durationSeconds, package }
  playback: 'none',   // playing | paused | stopped | none
  position: null,     // ms, as of positionAt
  positionAt: null,   // Date.now() when position was sampled
  volume: null,       // { current, max }
};

function broadcast() {
  const payload = JSON.stringify({ type: 'state', state });
  for (const c of wss.clients) {
    if (c.readyState === 1) c.send(payload);
  }
}

function patch(next) {
  const before = JSON.stringify(state);
  state = { ...state, ...next };
  if (JSON.stringify(state) !== before) broadcast();
}

// -------------------------------------------------------------- poll loops

let pollTick = 0;
let polling = false;
let lastDescription = null; // guards the metadata lookup against repeat work

async function pollOnce() {
  if (polling) return;
  polling = true;
  try {
    if (!state.connected) return;

    const { current } = await adb.nowPlaying();

    if (!current) {
      patch({ track: null, playback: 'none', position: null, positionAt: null });
      return;
    }

    // Only hit the network when the track actually changes.
    const changed = current.descriptionRaw !== lastDescription || current.package !== state.track?.package;
    let track = state.track;
    if (changed) {
      lastDescription = current.descriptionRaw;
      const meta = current.descriptionRaw
        ? await ytm.resolveTrack(current.descriptionRaw, current.title)
        : null;

      // A YT Music track is by definition in the catalogue, so trust the
      // canonical metadata. Anything else (Plex, podcasts) may have no match at
      // all, so only borrow artwork when the title really lines up.
      const isYtm = adb.rankPackage(current.package) === 0;
      const titleMatches = meta?.title && current.title
        && meta.title.toLowerCase().includes(current.title.toLowerCase().slice(0, 24));
      const trusted = isYtm || titleMatches ? meta : null;

      track = {
        title: trusted?.title ?? current.title,
        artist: trusted?.artist ?? current.artist,
        album: trusted?.album ?? current.album,
        thumbnail: trusted?.thumbnail ?? null,
        durationSeconds: trusted?.durationSeconds ?? null,
        videoId: trusted?.videoId ?? null,
        package: current.package,
        source: adb.labelFor(current.package),
      };
    }

    const next = {
      track,
      playback: current.state,
      position: current.position,
      positionAt: Date.now(),
      error: null,
    };

    if (pollTick % VOLUME_EVERY === 0) {
      next.volume = await adb.getVolume().catch(() => state.volume);
    }
    pollTick++;

    patch(next);
  } catch (err) {
    // A dead socket shows up here first; let the connection loop re-establish.
    patch({ connected: false, error: String(err.message ?? err) });
  } finally {
    polling = false;
  }
}

async function ensureConnected() {
  if (await adb.isAlive()) {
    if (!state.connected) patch({ connected: true, device: adb.currentSerial(), error: null });
    return;
  }
  const res = await adb.autoConnect().catch(() => null);
  if (res) {
    patch({ connected: true, device: res.address, error: null });
    const pkg = await adb.detectYtmPackage().catch(() => null);
    console.log(`[adb] connected to ${res.address} (${res.via}); YT Music package: ${pkg ?? 'not found'}`);
  } else if (state.connected || state.device) {
    patch({ connected: false, device: null });
  }
}

setInterval(pollOnce, POLL_MS);
setInterval(ensureConnected, RECONNECT_MS);

/** After a command, sample quickly so the UI feels immediate. */
function nudge() {
  setTimeout(pollOnce, 250);
  setTimeout(pollOnce, 900);
  setTimeout(pollOnce, 2000);
}

// ----------------------------------------------------------------- routes

const ok = (res, body = {}) => res.json({ ok: true, ...body });
const fail = (res, err, code = 500) =>
  res.status(code).json({ ok: false, error: String(err?.message ?? err) });

app.get('/api/state', (_req, res) => ok(res, { state }));

app.get('/api/devices', async (_req, res) => {
  try {
    ok(res, { devices: await adb.listDevices(), discovered: await adb.discover() });
  } catch (e) { fail(res, e); }
});

app.post('/api/pair', async (req, res) => {
  const { address, code } = req.body ?? {};
  if (!address || !code) return fail(res, 'address and code are required', 400);
  try {
    const msg = await adb.pair(address, code);
    await ensureConnected();
    ok(res, { message: msg });
  } catch (e) { fail(res, e); }
});

app.post('/api/connect', async (req, res) => {
  const { address } = req.body ?? {};
  try {
    if (address) {
      await adb.connect(address);
      patch({ connected: true, device: address, error: null });
    } else {
      await ensureConnected();
    }
    ok(res, { state });
  } catch (e) { fail(res, e); }
});

app.post('/api/command/:key', async (req, res) => {
  try {
    await adb.dispatch(req.params.key);
    nudge();
    ok(res);
  } catch (e) { fail(res, e); }
});

app.post('/api/play', async (req, res) => {
  const { videoId, playlistId } = req.body ?? {};
  try {
    if (videoId) await adb.playVideo(videoId);
    else if (playlistId) await adb.playList(playlistId);
    else return fail(res, 'videoId or playlistId is required', 400);
    nudge();
    ok(res);
  } catch (e) { fail(res, e); }
});

app.post('/api/volume', async (req, res) => {
  const { set, adjust } = req.body ?? {};
  try {
    if (typeof set === 'number') await adb.setVolume(set);
    else if (adjust) await adb.adjustVolume(adjust);
    else return fail(res, 'set or adjust is required', 400);
    patch({ volume: (await adb.getVolume()) ?? state.volume });
    ok(res);
  } catch (e) { fail(res, e); }
});

app.get('/api/search', async (req, res) => {
  const { q, type = 'song' } = req.query;
  if (!q) return fail(res, 'q is required', 400);
  try {
    ok(res, { results: await ytm.search(String(q), String(type)) });
  } catch (e) { fail(res, e); }
});

app.get('/api/suggest', async (req, res) => {
  try {
    ok(res, { suggestions: await ytm.suggestions(String(req.query.q ?? '')) });
  } catch (e) { fail(res, e); }
});

wss.on('connection', (ws) => {
  ws.send(JSON.stringify({ type: 'state', state }));
});

server.listen(PORT, () => {
  console.log(`\n  YT Music Remote  ->  http://localhost:${PORT}\n`);
  ensureConnected();
});
