import { DurableObject } from 'cloudflare:workers';

// See PROTOCOL.md for the message contract shared with the app and the web UI.

const AUTH_TIMEOUT_MS = 10_000;
const PAIR_CODE_TTL_MS = 5 * 60_000;
const PAIR_CREATE_COOLDOWN_MS = 3_000;
const MAX_MESSAGE_BYTES = 256 * 1024;
const COMMANDS_PER_WINDOW = 20;
const COMMAND_WINDOW_MS = 10_000;
const PAIR_FAILURES_PER_WINDOW = 10;
const PAIR_FAILURE_WINDOW_MS = 10 * 60_000;
const MAX_GUESTS = 50;
const GUEST_NAME_MAX = 24;
const JOINS_PER_WINDOW = 10; // per IP, successful or not
const JOIN_WINDOW_MS = 10 * 60_000;
const MAX_QUEUE_BYTES = 128 * 1024;

const DEVICE_ID = /^[a-z0-9]{20,40}$/;
const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ'; // no 0/O, 1/I/L
const QUEUE_ACTIONS = ['queue.add', 'queue.remove', 'queue.move', 'queue.clear'];
// What each kind of socket may ask the phone to do. Linked browsers ("clients")
// are full remotes; party guests can only search and request songs. The phone
// enforces finer rules itself, e.g. a guest may only remove their own request.
const ACTIONS = {
  client: new Set([
    'play', 'pause', 'playPause', 'next', 'previous',
    'seek', 'volume', 'playVideo', 'search', ...QUEUE_ACTIONS,
  ]),
  guest: new Set(['search', 'queue.add', 'queue.remove']),
};

// ------------------------------------------------------------------ worker

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === 'OPTIONS') return withCors(new Response(null, { status: 204 }));
    if (url.pathname === '/health') return new Response('ok');

    if (url.pathname === '/v1/pair' && request.method === 'POST') {
      return withCors(await pair(request, env));
    }

    if (url.pathname === '/v1/party/join' && request.method === 'POST') {
      return withCors(await joinParty(request, env));
    }

    if (url.pathname === '/v1/ws') {
      if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') {
        return new Response('Expected a WebSocket upgrade', { status: 426 });
      }
      const deviceId = url.searchParams.get('device') ?? '';
      if (!DEVICE_ID.test(deviceId)) return new Response('Bad device id', { status: 400 });

      const headers = new Headers(request.headers);
      headers.set('X-Device-Id', deviceId);
      return room(env, deviceId).fetch(new Request(request, { headers }));
    }

    return new Response('Not found', { status: 404 });
  },
};

async function pair(request, env) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: 'bad_request' }, 400);
  }
  const code = normalizeCode(body?.code);
  const name = String(body?.name ?? 'Browser').trim().slice(0, 60) || 'Browser';
  const ip = request.headers.get('CF-Connecting-IP') ?? 'unknown';

  const outcome = await pairing(env).claim(code, ip);
  if (outcome.error) return json({ error: outcome.error }, outcome.error === 'rate_limited' ? 429 : 404);

  const issued = await room(env, outcome.deviceId).issueToken(name);
  return json({ deviceId: outcome.deviceId, ...issued });
}

async function joinParty(request, env) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: 'bad_request' }, 400);
  }
  const deviceId = String(body?.deviceId ?? '');
  const name = guestName(body?.name);
  if (!DEVICE_ID.test(deviceId)) return json({ error: 'invalid_link' }, 404);
  if (!name) return json({ error: 'name_required' }, 400);
  const ip = request.headers.get('CF-Connecting-IP') ?? 'unknown';

  const outcome = await room(env, deviceId).joinParty(String(body?.secret ?? ''), name, ip);
  if (outcome.error) {
    const status = { rate_limited: 429, party_full: 409 }[outcome.error] ?? 404;
    return json({ error: outcome.error }, status);
  }
  return json({ deviceId, ...outcome });
}

const room = (env, deviceId) => env.ROOMS.get(env.ROOMS.idFromName(deviceId));
// One global registry keeps code lookup strongly consistent. It holds only
// short-lived codes, so it is small; shard by code prefix if it ever gets hot.
const pairing = (env) => env.PAIRING.get(env.PAIRING.idFromName('global'));

// -------------------------------------------------------------------- room

/**
 * One per phone. Authenticates sockets, routes commands to the device and
 * results back to the right browser, and caches the latest state so a browser
 * that connects mid-song renders immediately. Uses WebSocket hibernation, so an
 * idle room costs nothing while its sockets stay open.
 */
export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    // Keep-alives are answered by the runtime without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
    this.commandBuckets = new Map(); // in-memory; resetting on eviction is fine
    this.lastPairCreateAt = 0;
    this.joinBuckets = new Map(); // ip -> { start, count }
  }

  async fetch(request) {
    const { 0: client, 1: server } = new WebSocketPair();
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({
      role: null,
      deviceId: request.headers.get('X-Device-Id'),
      connectedAt: Date.now(),
    });
    if ((await this.ctx.storage.getAlarm()) == null) {
      await this.ctx.storage.setAlarm(Date.now() + AUTH_TIMEOUT_MS);
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Called by the worker once a pairing code has been claimed. */
  async issueToken(name) {
    const clientId = randomId();
    const token = randomSecret();
    const clients = await this.clients();
    clients[clientId] = { tokenHash: await sha256(token), name, createdAt: Date.now(), lastSeenAt: null };
    await this.ctx.storage.put('clients', clients);
    // The code that got us here is spent; never hand it out again.
    await this.ctx.storage.delete('pairCode');
    this.toDevice({ type: 'clients', clients: publicClients(clients) });
    return { clientId, token };
  }

  /** Called by the worker when someone opens a party link and enters a name. */
  async joinParty(secret, name, ip) {
    const now = Date.now();
    const bucket = this.joinBuckets.get(ip);
    if (!bucket || now - bucket.start >= JOIN_WINDOW_MS) this.joinBuckets.set(ip, { start: now, count: 1 });
    else if (++bucket.count > JOINS_PER_WINDOW) return { error: 'rate_limited' };

    const party = await this.ctx.storage.get('party');
    if (!party || !constantTimeEqual(party.secret, secret)) return { error: 'invalid_link' };

    const guests = await this.guests();
    if (Object.keys(guests).length >= MAX_GUESTS) return { error: 'party_full' };

    const guestId = `g${randomId()}`;
    const token = randomSecret();
    guests[guestId] = { tokenHash: await sha256(token), name, joinedAt: now, lastSeenAt: null };
    await this.ctx.storage.put('guests', guests);
    this.toDevice({ type: 'guests', guests: publicGuests(guests) });
    this.broadcastParty(party, guests);
    return { guestId, token, name };
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== 'string') return safeClose(ws, 1003, 'Text frames only');
    if (message.length > MAX_MESSAGE_BYTES) return safeClose(ws, 1009, 'Message too large');

    let msg;
    try {
      msg = JSON.parse(message);
    } catch {
      return safeClose(ws, 1007, 'Invalid JSON');
    }

    const att = ws.deserializeAttachment() ?? {};
    if (!att.role) return this.authenticate(ws, att, msg);
    if (att.role === 'device') return this.fromDevice(ws, msg);
    return this.fromClient(ws, att, msg);
  }

  async webSocketClose(ws, code, reason) {
    // Complete the close handshake. 1005/1006/1015 describe what happened
    // locally and may never be sent, so echoing them throws and the peer
    // is left waiting for a close frame that never comes.
    const sendable = code >= 1000 && code < 5000 && ![1004, 1005, 1006, 1015].includes(code);
    safeClose(ws, sendable ? code : 1000, sendable ? reason : '');
    const att = ws.deserializeAttachment() ?? {};
    if (att.role === 'device' && !this.deviceSocket(ws)) {
      this.toClients({ type: 'presence', deviceOnline: false }, { guests: true });
    }
  }

  async webSocketError(ws) {
    await this.webSocketClose(ws, 1011, 'Socket error');
  }

  /** Drops sockets that never authenticated. */
  async alarm() {
    let pending = false;
    for (const ws of this.ctx.getWebSockets()) {
      const att = ws.deserializeAttachment() ?? {};
      if (att.role) continue;
      if (Date.now() - att.connectedAt >= AUTH_TIMEOUT_MS) safeClose(ws, 4001, 'Authentication timeout');
      else pending = true;
    }
    if (pending) await this.ctx.storage.setAlarm(Date.now() + AUTH_TIMEOUT_MS);
  }

  // ------------------------------------------------------------------- auth

  async authenticate(ws, att, msg) {
    if (msg.type !== 'auth') return safeClose(ws, 4001, 'Authenticate first');

    if (msg.role === 'device') {
      const secret = String(msg.secret ?? '');
      if (secret.length < 32 || !att.deviceId) return safeClose(ws, 4001, 'Unauthorized');

      const hash = await sha256(secret);
      const stored = await this.ctx.storage.get('deviceSecretHash');
      if (stored && !constantTimeEqual(stored, hash)) return safeClose(ws, 4001, 'Unauthorized');
      // First connection claims the room. deviceIds are random and never shown
      // to anyone before the device's first connect, so nobody can get there first.
      if (!stored) await this.ctx.storage.put({ deviceSecretHash: hash, deviceId: att.deviceId });

      for (const other of this.ctx.getWebSockets()) {
        if (other !== ws && other.deserializeAttachment()?.role === 'device') {
          safeClose(other, 4000, 'Replaced by a newer connection');
        }
      }
      // Lets browsers tell an up-to-date phone from one that can't run a party.
      const features = Array.isArray(msg.features)
        ? msg.features.filter((f) => typeof f === 'string').slice(0, 20).map((f) => f.slice(0, 40))
        : [];
      await this.ctx.storage.put('features', features);

      ws.serializeAttachment({ role: 'device' });
      const { party = null, guests = {} } = Object.fromEntries(await this.ctx.storage.get(['party', 'guests']));
      send(ws, {
        type: 'ready',
        clients: publicClients(await this.clients()),
        party: partyView(party, guests, true),
        guests: publicGuests(guests),
      });
      this.toClients({ type: 'presence', deviceOnline: true, features }, { guests: true });
      return;
    }

    if (msg.role === 'client') {
      const clientId = String(msg.clientId ?? '');
      const clients = await this.clients();
      const entry = clients[clientId];
      if (!entry || !constantTimeEqual(entry.tokenHash, await sha256(String(msg.token ?? '')))) {
        return safeClose(ws, 4001, 'Unauthorized');
      }
      entry.lastSeenAt = Date.now();
      await this.ctx.storage.put('clients', clients);

      ws.serializeAttachment({ role: 'client', clientId, name: entry.name });
      send(ws, { type: 'ready', role: 'client', ...(await this.snapshotFor(true)) });
      return;
    }

    if (msg.role === 'guest') {
      const guestId = String(msg.guestId ?? '');
      const guests = await this.guests();
      const entry = guests[guestId];
      if (!entry || !constantTimeEqual(entry.tokenHash, await sha256(String(msg.token ?? '')))) {
        // Tell "the party is over" apart from a bad token so the page can say so.
        const partyOver = !entry && !(await this.ctx.storage.get('party'));
        return partyOver ? safeClose(ws, 4004, 'Party ended') : safeClose(ws, 4001, 'Unauthorized');
      }
      entry.lastSeenAt = Date.now();
      await this.ctx.storage.put('guests', guests);

      ws.serializeAttachment({ role: 'guest', clientId: guestId, name: entry.name });
      send(ws, { type: 'ready', role: 'guest', guestId, name: entry.name, ...(await this.snapshotFor(false)) });
      return;
    }

    safeClose(ws, 4001, 'Unknown role');
  }

  // --------------------------------------------------------------- routing

  fromClient(ws, att, msg) {
    if (msg.type !== 'cmd') return;
    const id = String(msg.id ?? '').slice(0, 40);
    const reply = (error) => send(ws, { type: 'result', id, ok: false, error });

    const allowed = ACTIONS[att.role];
    if (!allowed?.has(msg.action)) {
      const known = ACTIONS.client.has(msg.action);
      return reply(known ? 'forbidden' : 'unknown_action');
    }
    if (!this.allowCommand(att.clientId)) return reply('rate_limited');

    const device = this.deviceSocket();
    if (!device) return reply('device_offline');

    // Encode the sender in the id so results route back statelessly —
    // nothing to lose if the room hibernates between command and result.
    // `from` is set here, never taken from the browser, so nobody can request
    // songs (or remove them) in someone else's name.
    send(device, {
      type: 'cmd',
      id: `${att.clientId}|${id}`,
      action: msg.action,
      args: msg.args && typeof msg.args === 'object' ? msg.args : {},
      from: { id: att.clientId, name: att.name ?? null, role: att.role },
    });
  }

  async fromDevice(ws, msg) {
    switch (msg.type) {
      case 'state':
        await this.ctx.storage.put('state', msg.state ?? null);
        this.toClients({ type: 'state', state: msg.state ?? null }, { guests: true });
        return;

      case 'artwork':
        await this.ctx.storage.put('artwork', msg.artwork ?? null);
        this.toClients({ type: 'artwork', artwork: msg.artwork ?? null }, { guests: true });
        return;

      case 'queue': {
        // The phone owns the queue; the relay only caches and fans it out.
        const queue = msg.queue && typeof msg.queue === 'object' ? msg.queue : null;
        if (queue && JSON.stringify(queue).length > MAX_QUEUE_BYTES) {
          send(ws, { type: 'error', error: 'queue_too_large' });
          return;
        }
        await this.ctx.storage.put('queue', queue);
        this.toClients({ type: 'queue', queue }, { guests: true });
        return;
      }

      case 'party.start': {
        // Idempotent: starting a running party just re-sends it.
        let party = await this.ctx.storage.get('party');
        if (!party) {
          party = { secret: randomSecret(), startedAt: Date.now() };
          await this.ctx.storage.put('party', party);
        }
        const guests = await this.guests();
        this.broadcastParty(party, guests);
        send(ws, { type: 'guests', guests: publicGuests(guests) });
        return;
      }

      case 'party.newLink': {
        const party = await this.ctx.storage.get('party');
        if (!party) {
          send(ws, { type: 'party', party: partyView(null, {}, true) });
          return;
        }
        party.secret = randomSecret(); // old link stops working; guests stay
        await this.ctx.storage.put('party', party);
        this.broadcastParty(party, await this.guests());
        return;
      }

      case 'party.end': {
        await this.ctx.storage.delete(['party', 'guests', 'queue']);
        for (const guest of this.ctx.getWebSockets()) {
          if (guest.deserializeAttachment()?.role === 'guest') safeClose(guest, 4004, 'Party ended');
        }
        this.broadcastParty(null, {});
        this.toClients({ type: 'queue', queue: null });
        send(ws, { type: 'guests', guests: [] });
        return;
      }

      case 'guests.list':
        send(ws, { type: 'guests', guests: publicGuests(await this.guests()) });
        return;

      case 'guests.remove': {
        const guestId = String(msg.guestId ?? '');
        const guests = await this.guests();
        if (guests[guestId]) {
          delete guests[guestId];
          await this.ctx.storage.put('guests', guests);
          for (const guest of this.clientSockets(guestId, 'guest')) safeClose(guest, 4003, 'Removed by the host');
          this.broadcastParty(await this.ctx.storage.get('party'), guests);
        }
        send(ws, { type: 'guests', guests: publicGuests(guests) });
        return;
      }

      case 'result': {
        const raw = String(msg.id ?? '');
        const split = raw.indexOf('|');
        if (split < 0) return;
        const clientId = raw.slice(0, split);
        const result = { type: 'result', id: raw.slice(split + 1), ok: !!msg.ok };
        if (msg.ok) result.data = msg.data ?? null;
        else result.error = String(msg.error ?? 'failed');
        for (const client of this.clientSockets(clientId)) send(client, result);
        return;
      }

      case 'pair.create':
        return this.createPairCode(ws);

      case 'clients.list':
        send(ws, { type: 'clients', clients: publicClients(await this.clients()) });
        return;

      case 'clients.revoke': {
        const clientId = String(msg.clientId ?? '');
        const clients = await this.clients();
        if (clients[clientId]) {
          delete clients[clientId];
          await this.ctx.storage.put('clients', clients);
          for (const client of this.clientSockets(clientId, 'client')) safeClose(client, 4003, 'Revoked');
        }
        send(ws, { type: 'clients', clients: publicClients(clients) });
        return;
      }
    }
  }

  async createPairCode(ws) {
    const now = Date.now();
    const previous = await this.ctx.storage.get('pairCode');

    // Asked again too soon: hand back the live code rather than going silent,
    // so a double-tap never leaves the app waiting on a reply.
    if (now - this.lastPairCreateAt < PAIR_CREATE_COOLDOWN_MS && previous?.expiresAt > now) {
      send(ws, { type: 'pair.code', code: formatCode(previous.code), expiresAt: previous.expiresAt });
      return;
    }
    this.lastPairCreateAt = now;

    const deviceId = await this.ctx.storage.get('deviceId');
    const expiresAt = now + PAIR_CODE_TTL_MS;

    for (let attempt = 0; attempt < 5; attempt++) {
      const code = randomCode();
      if (await pairing(this.env).register(code, deviceId, expiresAt, previous?.code)) {
        await this.ctx.storage.put('pairCode', { code, expiresAt });
        send(ws, { type: 'pair.code', code: formatCode(code), expiresAt });
        return;
      }
    }
    send(ws, { type: 'error', error: 'pair_code_unavailable' });
  }

  // --------------------------------------------------------------- helpers

  async clients() {
    return (await this.ctx.storage.get('clients')) ?? {};
  }

  async guests() {
    return (await this.ctx.storage.get('guests')) ?? {};
  }

  /** What a browser needs to render immediately after connecting. */
  async snapshotFor(isRemote) {
    const stored = Object.fromEntries(await this.ctx.storage.get(['state', 'artwork', 'queue', 'party', 'guests', 'features']));
    return {
      deviceOnline: !!this.deviceSocket(),
      features: stored.features ?? [],
      state: stored.state ?? null,
      artwork: stored.artwork ?? null,
      queue: stored.queue ?? null,
      party: partyView(stored.party ?? null, stored.guests ?? {}, isRemote),
    };
  }

  /** Remotes and the phone get the link secret so they can share it; guests don't. */
  broadcastParty(party, guests) {
    const full = { type: 'party', party: partyView(party, guests, true) };
    const limited = { type: 'party', party: partyView(party, guests, false) };
    this.toDevice(full);
    for (const ws of this.ctx.getWebSockets()) {
      const role = ws.deserializeAttachment()?.role;
      if (role === 'client') send(ws, full);
      else if (role === 'guest') send(ws, limited);
    }
  }

  deviceSocket(exclude) {
    return this.ctx.getWebSockets().find((ws) =>
      ws !== exclude && ws.readyState === WebSocket.READY_STATE_OPEN &&
      ws.deserializeAttachment()?.role === 'device');
  }

  /** Browser sockets: linked remotes, party guests, or both (role omitted). */
  clientSockets(clientId, role) {
    return this.ctx.getWebSockets().filter((ws) => {
      const att = ws.deserializeAttachment();
      const browser = role ? att?.role === role : att?.role === 'client' || att?.role === 'guest';
      return browser && (clientId == null || att.clientId === clientId);
    });
  }

  toDevice(message) {
    const device = this.deviceSocket();
    if (device) send(device, message);
  }

  toClients(message, { guests = false } = {}) {
    for (const ws of this.clientSockets(null, guests ? null : 'client')) send(ws, message);
  }

  allowCommand(clientId) {
    const now = Date.now();
    const bucket = this.commandBuckets.get(clientId);
    if (!bucket || now - bucket.start >= COMMAND_WINDOW_MS) {
      this.commandBuckets.set(clientId, { start: now, count: 1 });
      return true;
    }
    bucket.count++;
    return bucket.count <= COMMANDS_PER_WINDOW;
  }
}

// ----------------------------------------------------------------- pairing

/** Short-lived pairing codes, looked up by the code a person types in. */
export class Pairing extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.failures = new Map(); // ip -> { start, count }
  }

  async register(code, deviceId, expiresAt, previous) {
    if (previous) await this.ctx.storage.delete(`code:${previous}`);
    const existing = await this.ctx.storage.get(`code:${code}`);
    if (existing && existing.expiresAt > Date.now()) return false;

    await this.ctx.storage.put(`code:${code}`, { deviceId, expiresAt });
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm == null || alarm > expiresAt) await this.ctx.storage.setAlarm(expiresAt);
    return true;
  }

  async claim(code, ip) {
    const now = Date.now();
    const f = this.failures.get(ip);
    if (f && now - f.start < PAIR_FAILURE_WINDOW_MS && f.count >= PAIR_FAILURES_PER_WINDOW) {
      return { error: 'rate_limited' };
    }

    const entry = code ? await this.ctx.storage.get(`code:${code}`) : null;
    if (!entry || entry.expiresAt <= now) {
      if (!f || now - f.start >= PAIR_FAILURE_WINDOW_MS) this.failures.set(ip, { start: now, count: 1 });
      else f.count++;
      return { error: 'invalid_code' };
    }

    await this.ctx.storage.delete(`code:${code}`); // single use
    return { deviceId: entry.deviceId };
  }

  async alarm() {
    const now = Date.now();
    let next = null;
    for (const [key, entry] of await this.ctx.storage.list({ prefix: 'code:' })) {
      if (entry.expiresAt <= now) await this.ctx.storage.delete(key);
      else if (next == null || entry.expiresAt < next) next = entry.expiresAt;
    }
    if (next != null) await this.ctx.storage.setAlarm(next);
  }
}

// ----------------------------------------------------------------- utility

function send(ws, message) {
  try {
    ws.send(JSON.stringify(message));
  } catch {
    // Socket already closing; the close handler cleans up.
  }
}

function safeClose(ws, code, reason) {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed.
  }
}

const formatCode = (code) => `${code.slice(0, 4)}-${code.slice(4)}`;

function publicClients(clients) {
  return Object.entries(clients).map(([clientId, c]) => ({
    clientId, name: c.name, createdAt: c.createdAt, lastSeenAt: c.lastSeenAt,
  }));
}

function publicGuests(guests) {
  return Object.entries(guests).map(([guestId, g]) => ({
    guestId, name: g.name, joinedAt: g.joinedAt, lastSeenAt: g.lastSeenAt,
  }));
}

function partyView(party, guests, includeSecret) {
  if (!party) return { active: false };
  const view = { active: true, startedAt: party.startedAt, guestCount: Object.keys(guests).length };
  if (includeSecret) view.secret = party.secret;
  return view;
}

/** Display names shown next to requests: printable, collapsed whitespace, short. */
export function guestName(input) {
  const name = String(input ?? '')
    .replace(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, '')
    .replace(/\s+/g, ' ')
    .trim();
  return [...name].slice(0, GUEST_NAME_MAX).join('').trim() || null;
}

export function normalizeCode(input) {
  const code = String(input ?? '').toUpperCase().replace(/[\s-]/g, '');
  return code.length === 8 && [...code].every((ch) => CODE_ALPHABET.includes(ch)) ? code : null;
}

function randomCode() {
  const out = [];
  const bytes = new Uint8Array(32);
  while (out.length < 8) {
    crypto.getRandomValues(bytes);
    for (const b of bytes) {
      // Rejection sampling keeps every character equally likely.
      if (b < 256 - (256 % CODE_ALPHABET.length) && out.length < 8) out.push(CODE_ALPHABET[b % CODE_ALPHABET.length]);
    }
  }
  return out.join('');
}

function randomId() {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz234567';
  const bytes = crypto.getRandomValues(new Uint8Array(26));
  return [...bytes].map((b) => alphabet[b & 31]).join('');
}

function randomSecret() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function sha256(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function constantTimeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

// Tokens travel in request bodies, never cookies, so a wildcard origin grants
// no ambient authority.
function withCors(response) {
  const r = new Response(response.body, response);
  r.headers.set('Access-Control-Allow-Origin', '*');
  r.headers.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
  r.headers.set('Access-Control-Allow-Headers', 'Content-Type');
  return r;
}
