// A stand-in for the Android app, for developing the relay and web UI without a phone.
// Speaks the device side of PROTOCOL.md, with real YouTube Music search results.
//
//   node tools/fake-phone.mjs [relayUrl]      (default http://127.0.0.1:8787)
import WebSocket from 'ws';
import { randomBytes } from 'node:crypto';

const RELAY = (process.argv[2] ?? 'http://127.0.0.1:8787').replace(/\/+$/, '');
const device = { id: randomBytes(16).toString('hex').slice(0, 26), secret: randomBytes(32).toString('base64url') };

const tracks = new Map();
let state = {
  source: 'YouTube Music', packageName: 'app.fake.youtube.music',
  title: null, artist: null, album: null, durationMs: null,
  positionMs: null, positionAt: Date.now(), playback: 'none',
  volume: 6, maxVolume: 15, artworkKey: null,
};
let ws;

function log(...args) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}

function send(msg) {
  if (ws?.readyState === 1) ws.send(JSON.stringify(msg));
}

function livePosition() {
  if (state.positionMs == null) return null;
  return state.playback === 'playing' ? state.positionMs + (Date.now() - state.positionAt) : state.positionMs;
}

function publish(patch) {
  state = { ...state, positionMs: livePosition(), positionAt: Date.now(), ...patch };
  send({ type: 'state', state });
}

async function ytmSearch(query) {
  const res = await fetch('https://music.youtube.com/youtubei/v1/search?prettyPrint=false', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: 'https://music.youtube.com', 'User-Agent': 'Mozilla/5.0' },
    body: JSON.stringify({
      context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20250219.01.00', hl: 'en', gl: 'US' } },
      query, params: 'EgWKAQIIAWoMEA4QChADEAQQCRAF',
    }),
  });
  const j = await res.json();
  const sections = j.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents ?? [];
  const out = [];
  for (const s of sections) {
    for (const item of s.musicShelfRenderer?.contents ?? []) {
      const r = item.musicResponsiveListItemRenderer;
      const runs = (i) => r?.flexColumns?.[i]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs ?? [];
      const videoId = r?.playlistItemData?.videoId;
      if (!videoId) continue;
      const [artist, album, duration] = runs(1).map((x) => x.text).join('').split(' • ');
      out.push({
        videoId, title: runs(0).map((x) => x.text).join(''), artist, album, duration,
        thumbnail: r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.at(-1)?.url ?? null,
      });
    }
  }
  return out.slice(0, 20);
}

async function play(track) {
  const [m, s] = (track.duration ?? '3:00').split(':').map(Number);
  const artworkKey = track.videoId;
  publish({
    title: track.title, artist: track.artist, album: track.album,
    durationMs: (m * 60 + s) * 1000, positionMs: 0, positionAt: Date.now(),
    playback: 'playing', artworkKey,
  });
  let data = null;
  if (track.thumbnail) {
    const img = await fetch(track.thumbnail.replace(/=w\d+-h\d+/, '=w320-h320'));
    data = Buffer.from(await img.arrayBuffer()).toString('base64');
  }
  send({ type: 'artwork', artwork: { key: artworkKey, mime: 'image/jpeg', data } });
}

async function execute({ id, action, args }) {
  const ok = (data = null) => send({ type: 'result', id, ok: true, data });
  const fail = (error) => send({ type: 'result', id, ok: false, error });
  log('cmd', action, JSON.stringify(args));

  const hasTrack = !!state.title;
  switch (action) {
    case 'search': {
      try {
        const results = await ytmSearch(String(args.query ?? ''));
        results.forEach((r) => tracks.set(r.videoId, r));
        return ok(results);
      } catch (e) {
        return fail(`search_failed: ${e.message}`);
      }
    }
    case 'playVideo': {
      const track = tracks.get(args.videoId);
      if (!track) return fail('unknown video (search first)');
      ok();
      return play(track);
    }
    case 'playPause':
    case 'play':
    case 'pause': {
      if (!hasTrack) return fail('nothing_playing');
      const playing = action === 'play' || (action === 'playPause' && state.playback !== 'playing');
      publish({ playback: playing ? 'playing' : 'paused' });
      return ok();
    }
    case 'next':
    case 'previous': {
      if (!hasTrack) return fail('nothing_playing');
      const list = [...tracks.values()];
      const at = list.findIndex((t) => t.title === state.title);
      const nextTrack = list[(at + (action === 'next' ? 1 : list.length - 1)) % list.length];
      ok();
      return nextTrack && play(nextTrack);
    }
    case 'seek':
      if (!hasTrack) return fail('nothing_playing');
      publish({ positionMs: args.positionMs, positionAt: Date.now() });
      state.positionMs = args.positionMs;
      return ok();
    case 'volume':
      publish({ volume: Math.max(0, Math.min(state.maxVolume, Number(args.value))) });
      return ok();
    default:
      return fail('unknown_action');
  }
}

function connect() {
  ws = new WebSocket(`${RELAY.replace(/^http/, 'ws')}/v1/ws?device=${device.id}`);
  ws.on('open', () => ws.send(JSON.stringify({ type: 'auth', role: 'device', secret: device.secret })));
  ws.on('message', (raw) => {
    const text = raw.toString();
    if (text === 'pong') return;
    const msg = JSON.parse(text);
    if (msg.type === 'ready') {
      log(`connected as ${device.id}; ${msg.clients.length} linked browser(s)`);
      send({ type: 'state', state });
      send({ type: 'pair.create' });
    } else if (msg.type === 'pair.code') {
      log(`PAIRING CODE: ${msg.code}   (valid 5 minutes)`);
    } else if (msg.type === 'clients') {
      log(`linked browsers: ${msg.clients.map((c) => c.name).join(', ') || 'none'}`);
    } else if (msg.type === 'cmd') {
      execute(msg);
    }
  });
  ws.on('close', (code) => {
    log(`disconnected (${code}); reconnecting in 2s`);
    setTimeout(connect, 2000);
  });
  ws.on('error', (e) => log('socket error:', e.message));
}

log(`fake phone -> ${RELAY}`);
connect();
