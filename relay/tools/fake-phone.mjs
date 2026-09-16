// A stand-in for the Android app, for developing the relay and web UI without a phone.
// Speaks the device side of PROTOCOL.md, with real YouTube Music search results, and
// runs a simple version of the party queue.
//
//   node tools/fake-phone.mjs [relayUrl] [--party] [--web=http://localhost:5173] [--song-seconds=30]
//
//   --party            start a party on connect and print the guest link
//   --web=URL          website the printed links point at (default http://localhost:5173)
//   --song-seconds=N   pretend every song is N seconds long, to watch the queue advance
import WebSocket from 'ws';
import { randomBytes } from 'node:crypto';

const args = process.argv.slice(2);
const flag = (name) => args.find((a) => a === `--${name}` || a.startsWith(`--${name}=`));
const flagValue = (name) => flag(name)?.split('=').slice(1).join('=') || null;

const RELAY = (args.find((a) => !a.startsWith('--')) ?? 'http://127.0.0.1:8787').replace(/\/+$/, '');
const WEB = (flagValue('web') ?? 'http://localhost:5173').replace(/\/+$/, '');
const SONG_SECONDS = Number(flagValue('song-seconds')) || null;
const AUTO_PARTY = !!flag('party');
const GUEST_LIMIT = 3;

const device = { id: randomBytes(16).toString('hex').slice(0, 26), secret: randomBytes(32).toString('base64url') };

const tracks = new Map();
let state = {
  source: 'YouTube Music', packageName: 'app.fake.youtube.music',
  title: null, artist: null, album: null, durationMs: null,
  positionMs: null, positionAt: Date.now(), playback: 'none',
  volume: 6, maxVolume: 15, artworkKey: null,
};
let party = { active: false };
/** Upcoming requests, plus the requested song playing now (null while something else plays). */
let queue = { current: null, items: [] };
let endTimer = null;
let ws;

function log(...parts) {
  console.log(new Date().toISOString().slice(11, 19), ...parts);
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
  scheduleEnd();
}

function publishQueue() {
  send({ type: 'queue', queue: party.active ? { ...queue, limitPerGuest: GUEST_LIMIT } : null });
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

function durationOf(track) {
  if (SONG_SECONDS) return SONG_SECONDS * 1000;
  if (track.durationMs) return track.durationMs;
  const parts = String(track.duration ?? '3:00').split(':').map(Number);
  return parts.reduce((total, n) => total * 60 + (Number.isFinite(n) ? n : 0), 0) * 1000 || 180_000;
}

async function play(track) {
  const artworkKey = track.videoId;
  publish({
    title: track.title, artist: track.artist, album: track.album ?? null,
    durationMs: durationOf(track), positionMs: 0, positionAt: Date.now(),
    playback: 'playing', artworkKey,
  });
  let data = null;
  if (track.thumbnail) {
    try {
      const img = await fetch(track.thumbnail.replace(/=w\d+-h\d+/, '=w320-h320'));
      data = Buffer.from(await img.arrayBuffer()).toString('base64');
    } catch { /* artwork is optional */ }
  }
  send({ type: 'artwork', artwork: { key: artworkKey, mime: 'image/jpeg', data } });
}

// ------------------------------------------------------------------ queue

/** Plays the next request, if any. Returns whether something started. */
function advance(reason) {
  const next = queue.items.shift();
  if (!next) {
    queue.current = null;
    publishQueue();
    return false;
  }
  queue.current = next;
  log(`queue: playing "${next.title}" (${reason}), ${queue.items.length} left`);
  publishQueue();
  play(next);
  return true;
}

function scheduleEnd() {
  clearTimeout(endTimer);
  if (state.playback !== 'playing' || !state.durationMs) return;
  const left = state.durationMs - livePosition();
  endTimer = setTimeout(() => {
    if (party.active && advance('previous song ended')) return;
    queue.current = null;
    publishQueue();
    publish({ playback: 'paused', positionMs: state.durationMs, positionAt: Date.now() });
  }, Math.max(0, left));
}

function queueCommand(action, a, from) {
  if (!party.active) return { error: 'party_off' };
  const isHost = from?.role !== 'guest';

  switch (action) {
    case 'queue.add': {
      const videoId = String(a.videoId ?? '');
      if (!/^[\w-]{6,20}$/.test(videoId)) return { error: 'bad_request' };
      const requester = { id: from.id, name: from.name ?? 'Remote', role: from.role };

      const existing = queue.items.find((i) => i.videoId === videoId);
      if (existing) {
        if (!existing.requestedBy.some((r) => r.id === requester.id)) existing.requestedBy.push(requester);
        publishQueue();
        return { data: { itemId: existing.itemId, position: queue.items.indexOf(existing) + 1, duplicate: true } };
      }
      if (!isHost) {
        const pending = queue.items.filter((i) => i.requestedBy[0].id === requester.id).length;
        if (pending >= GUEST_LIMIT) return { error: 'limit_reached' };
      }
      const item = {
        itemId: randomBytes(6).toString('hex'),
        videoId,
        title: String(a.title ?? 'Unknown song').slice(0, 200),
        artist: a.artist ? String(a.artist).slice(0, 200) : null,
        album: a.album ? String(a.album).slice(0, 200) : null,
        durationMs: durationOf(a),
        thumbnail: typeof a.thumbnail === 'string' && a.thumbnail.startsWith('https://') ? a.thumbnail : null,
        requestedBy: [requester],
        addedAt: Date.now(),
      };
      queue.items.push(item);
      log(`queue: ${requester.name} added "${item.title}"`);
      // Nothing requested is playing: start right away.
      if (!queue.current) advance('first request');
      else publishQueue();
      const position = queue.items.indexOf(item) + 1; // 0 when it started playing
      return { data: { itemId: item.itemId, position, duplicate: false } };
    }

    case 'queue.remove': {
      const item = queue.items.find((i) => i.itemId === a.itemId);
      if (!item) return { error: 'not_found' };
      if (!isHost && item.requestedBy[0].id !== from.id) return { error: 'not_yours' };
      queue.items.splice(queue.items.indexOf(item), 1);
      publishQueue();
      return { data: null };
    }

    case 'queue.move': {
      const at = queue.items.findIndex((i) => i.itemId === a.itemId);
      if (at < 0) return { error: 'not_found' };
      const [item] = queue.items.splice(at, 1);
      const to = Math.max(0, Math.min(queue.items.length, Number(a.toIndex) || 0));
      queue.items.splice(to, 0, item);
      publishQueue();
      return { data: null };
    }

    case 'queue.clear':
      queue.items = [];
      publishQueue();
      return { data: null };
  }
  return { error: 'unknown_action' };
}

// --------------------------------------------------------------- commands

async function execute({ id, action, args: a = {}, from }) {
  const ok = (data = null) => send({ type: 'result', id, ok: true, data });
  const fail = (error) => send({ type: 'result', id, ok: false, error });
  log('cmd', action, from ? `from ${from.name ?? from.id} (${from.role})` : '', JSON.stringify(a));

  if (action.startsWith('queue.')) {
    const outcome = queueCommand(action, a, from);
    return outcome.error ? fail(outcome.error) : ok(outcome.data);
  }

  const hasTrack = !!state.title;
  switch (action) {
    case 'search': {
      try {
        const results = await ytmSearch(String(a.query ?? ''));
        results.forEach((r) => tracks.set(r.videoId, r));
        return ok(results);
      } catch (e) {
        return fail(`search_failed: ${e.message}`);
      }
    }
    case 'playVideo': {
      const track = tracks.get(a.videoId);
      if (!track) return fail('unknown video (search first)');
      queue.current = null; // "Play now" is the host's own pick, not a request
      if (party.active) publishQueue();
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
      if (party.active && queue.items.length) {
        ok();
        return advance('skipped');
      }
    // falls through: no queue, behave like the player's own next
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
      publish({ positionMs: a.positionMs, positionAt: Date.now() });
      return ok();
    case 'volume':
      publish({ volume: Math.max(0, Math.min(state.maxVolume, Number(a.value))) });
      return ok();
    default:
      return fail('unknown_action');
  }
}

function onParty(next) {
  const wasActive = party.active;
  party = next;
  if (party.active && party.secret) {
    log(`PARTY LINK: ${WEB}/#party=${device.id}.${party.secret}   (${party.guestCount} guest(s))`);
  }
  if (party.active && !wasActive) publishQueue();
  if (!party.active && wasActive) {
    queue = { current: null, items: [] };
    log('party ended');
  }
}

function connect() {
  ws = new WebSocket(`${RELAY.replace(/^http/, 'ws')}/v1/ws?device=${device.id}`);
  ws.on('open', () => ws.send(JSON.stringify({ type: 'auth', role: 'device', secret: device.secret, features: ['queue'] })));
  ws.on('message', (raw) => {
    const text = raw.toString();
    if (text === 'pong') return;
    const msg = JSON.parse(text);
    if (msg.type === 'ready') {
      log(`connected as ${device.id}; ${msg.clients.length} linked browser(s)`);
      send({ type: 'state', state });
      send({ type: 'pair.create' });
      onParty(msg.party ?? { active: false });
      if (AUTO_PARTY && !party.active) send({ type: 'party.start' });
    } else if (msg.type === 'pair.code') {
      log(`PAIRING CODE: ${msg.code}   (valid 5 minutes)   ${WEB}/?code=${msg.code}`);
    } else if (msg.type === 'clients') {
      log(`linked browsers: ${msg.clients.map((c) => c.name).join(', ') || 'none'}`);
    } else if (msg.type === 'party') {
      onParty(msg.party);
    } else if (msg.type === 'guests') {
      log(`guests: ${msg.guests.map((g) => g.name).join(', ') || 'none'}`);
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
