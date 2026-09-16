// End-to-end tests against the real Worker under `wrangler dev`.
// Run: npm test
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import WebSocket from 'ws';

const PORT = 8799;
const HTTP = `http://127.0.0.1:${PORT}`;
const WS = `ws://127.0.0.1:${PORT}`;

let server;

before(async () => {
  server = spawn('npx', ['wrangler', 'dev', '--port', String(PORT), '--ip', '127.0.0.1', '--local', '--persist-to', '.wrangler/test-state'], {
    shell: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  server.stdout.on('data', (d) => { output += d; });
  server.stderr.on('data', (d) => { output += d; });

  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    try {
      if ((await fetch(`${HTTP}/health`)).ok) return;
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`wrangler dev did not start:\n${output}`);
});

after(async () => {
  if (!server) return;
  // `shell: true` means killing the parent isn't enough on Windows.
  if (process.platform === 'win32') spawn('taskkill', ['/pid', String(server.pid), '/T', '/F']);
  else server.kill('SIGTERM');
});

// ---------------------------------------------------------------- helpers

const newDevice = () => ({
  id: randomBytes(16).toString('hex').slice(0, 26),
  secret: randomBytes(32).toString('base64url'),
});

/** A socket with an awaitable inbox. */
function open(deviceId) {
  const ws = new WebSocket(`${WS}/v1/ws?device=${deviceId}`);
  const inbox = [];
  const waiters = [];
  ws.on('message', (raw) => {
    const text = raw.toString();
    const msg = text === 'pong' ? { type: 'pong' } : JSON.parse(text);
    const i = waiters.findIndex((w) => w.match(msg));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(msg);
    else inbox.push(msg);
  });
  const closed = new Promise((resolve) => ws.on('close', (code, reason) => resolve({ code, reason: reason.toString() })));

  return {
    ws,
    closed,
    opened: new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); }),
    send: (msg) => ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg)),
    next(type, timeout = 5000) {
      const match = (m) => m.type === type;
      const i = inbox.findIndex(match);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((resolve, reject) => {
        const waiter = { match, resolve };
        waiters.push(waiter);
        setTimeout(() => {
          const at = waiters.indexOf(waiter);
          if (at >= 0) { waiters.splice(at, 1); reject(new Error(`timed out waiting for ${type}`)); }
        }, timeout);
      });
    },
    close: () => ws.close(),
  };
}

async function connectDevice(device) {
  const sock = open(device.id);
  await sock.opened;
  sock.send({ type: 'auth', role: 'device', secret: device.secret });
  const ready = await sock.next('ready');
  return { sock, ready };
}

async function pairClient(deviceSock, name = 'Test browser') {
  deviceSock.send({ type: 'pair.create' });
  const { code } = await deviceSock.next('pair.code');
  const res = await fetch(`${HTTP}/v1/pair`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, name }),
  });
  assert.equal(res.status, 200, 'pairing should succeed');
  return { code, ...(await res.json()) };
}

async function connectClient(deviceId, clientId, token) {
  const sock = open(deviceId);
  await sock.opened;
  sock.send({ type: 'auth', role: 'client', clientId, token });
  return sock;
}

const postPair = (code) => fetch(`${HTTP}/v1/pair`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ code, name: 'x' }),
});

// ------------------------------------------------------------------ tests

test('health check', async () => {
  assert.equal(await (await fetch(`${HTTP}/health`)).text(), 'ok');
});

test('rejects malformed device ids and non-upgrade requests', async () => {
  const status = await new Promise((resolve, reject) => {
    const ws = new WebSocket(`${WS}/v1/ws?device=bad!`);
    ws.on('unexpected-response', (_req, res) => { resolve(res.statusCode); ws.terminate(); });
    ws.on('open', () => reject(new Error('should not upgrade')));
    ws.on('error', () => {});
  });
  assert.equal(status, 400);
  assert.equal((await fetch(`${HTTP}/v1/ws?device=${newDevice().id}`)).status, 426);
});

test('asking for a pairing code twice quickly returns the same live code', async () => {
  const { sock: dev } = await connectDevice(newDevice());
  dev.send({ type: 'pair.create' });
  const first = await dev.next('pair.code');
  dev.send({ type: 'pair.create' });
  const second = await dev.next('pair.code');
  assert.equal(second.code, first.code);
  dev.close();
});

test('closing without a status code completes promptly', async () => {
  const { sock } = await connectDevice(newDevice());
  const started = Date.now();
  sock.close();
  await sock.closed;
  assert.ok(Date.now() - started < 2000, `close took ${Date.now() - started}ms`);
});

test('a socket that sends anything before auth is closed with 4001', async () => {
  const sock = open(newDevice().id);
  await sock.opened;
  sock.send({ type: 'cmd', action: 'play' });
  assert.equal((await sock.closed).code, 4001);
});

test('first device connection claims the room; a different secret is refused', async () => {
  const device = newDevice();
  const { sock, ready } = await connectDevice(device);
  assert.deepEqual(ready.clients, []);
  sock.close();
  await sock.closed;

  const impostor = open(device.id);
  await impostor.opened;
  impostor.send({ type: 'auth', role: 'device', secret: randomBytes(32).toString('base64url') });
  assert.equal((await impostor.closed).code, 4001);
});

test('keep-alive ping is answered with pong', async () => {
  const { sock } = await connectDevice(newDevice());
  sock.send('ping');
  await sock.next('pong');
  sock.close();
});

test('pairing: code works once, tokens authenticate, device sees the new browser', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);

  const paired = await pairClient(dev, 'Chrome on Windows');
  assert.equal(paired.deviceId, device.id);
  assert.match(paired.token, /^[\w-]{40,}$/);

  const { clients } = await dev.next('clients');
  assert.equal(clients.length, 1);
  assert.equal(clients[0].name, 'Chrome on Windows');
  assert.ok(!('tokenHash' in clients[0]), 'token hashes must never leave the relay');

  assert.equal((await postPair(paired.code)).status, 404, 'a code is single-use');

  const client = await connectClient(device.id, paired.clientId, paired.token);
  const ready = await client.next('ready');
  assert.equal(ready.deviceOnline, true);
  assert.equal(ready.state, null);

  const forged = await connectClient(device.id, paired.clientId, 'not-the-token');
  assert.equal((await forged.closed).code, 4001);

  client.close();
  dev.close();
});

test('codes are normalised: lowercase and no dash still pair', async () => {
  const { sock: dev } = await connectDevice(newDevice());
  dev.send({ type: 'pair.create' });
  const { code } = await dev.next('pair.code');
  const res = await postPair(code.replace('-', '').toLowerCase());
  assert.equal(res.status, 200);
  dev.close();
});

test('state is broadcast live and cached for browsers that connect later', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const { clientId, token } = await pairClient(dev);

  const early = await connectClient(device.id, clientId, token);
  await early.next('ready');

  const state = { title: 'Numb', artist: 'Elderbrook', playback: 'playing', positionMs: 1000, positionAt: Date.now() };
  dev.send({ type: 'state', state });
  assert.deepEqual((await early.next('state')).state, state);

  const artwork = { key: 'k1', mime: 'image/jpeg', data: 'AAAA' };
  dev.send({ type: 'artwork', artwork });
  assert.deepEqual((await early.next('artwork')).artwork, artwork);

  const late = await connectClient(device.id, clientId, token);
  const ready = await late.next('ready');
  assert.deepEqual(ready.state, state);
  assert.deepEqual(ready.artwork, artwork);

  early.close(); late.close(); dev.close();
});

test('commands reach the device and results return only to the sender', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const a = await pairClient(dev, 'A');
  const b = await pairClient(dev, 'B');
  const clientA = await connectClient(device.id, a.clientId, a.token);
  const clientB = await connectClient(device.id, b.clientId, b.token);
  await clientA.next('ready');
  await clientB.next('ready');

  clientA.send({ type: 'cmd', id: '42', action: 'playVideo', args: { videoId: 'RvegizX3GqY' } });
  const cmd = await dev.next('cmd');
  assert.equal(cmd.action, 'playVideo');
  assert.deepEqual(cmd.args, { videoId: 'RvegizX3GqY' });

  dev.send({ type: 'result', id: cmd.id, ok: true, data: { started: true } });
  const result = await clientA.next('result');
  assert.deepEqual(result, { type: 'result', id: '42', ok: true, data: { started: true } });
  await assert.rejects(clientB.next('result', 800), /timed out/, 'B must not see A\'s result');

  clientA.close(); clientB.close(); dev.close();
});

test('unknown actions are refused without reaching the device', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const { clientId, token } = await pairClient(dev);
  const client = await connectClient(device.id, clientId, token);
  await client.next('ready');

  client.send({ type: 'cmd', id: '1', action: 'formatPhone' });
  const result = await client.next('result');
  assert.equal(result.error, 'unknown_action');
  await assert.rejects(dev.next('cmd', 800), /timed out/);

  client.close(); dev.close();
});

test('device going offline: presence broadcast and commands fail fast', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const { clientId, token } = await pairClient(dev);
  const client = await connectClient(device.id, clientId, token);
  await client.next('ready');

  dev.close();
  assert.equal((await client.next('presence')).deviceOnline, false);

  client.send({ type: 'cmd', id: '9', action: 'next' });
  assert.equal((await client.next('result')).error, 'device_offline');

  await connectDevice(device);
  assert.equal((await client.next('presence')).deviceOnline, true);
  client.close();
});

test('a newer device connection replaces the old one', async () => {
  const device = newDevice();
  const first = await connectDevice(device);
  const second = await connectDevice(device);
  assert.equal((await first.sock.closed).code, 4000);
  second.sock.close();
});

test('revoking a browser disconnects it and its token stops working', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const { clientId, token } = await pairClient(dev);
  await dev.next('clients');
  const client = await connectClient(device.id, clientId, token);
  await client.next('ready');

  dev.send({ type: 'clients.revoke', clientId });
  assert.equal((await client.closed).code, 4003);
  assert.deepEqual((await dev.next('clients')).clients, []);

  const again = await connectClient(device.id, clientId, token);
  assert.equal((await again.closed).code, 4001);
  dev.close();
});

test('each client is rate limited on commands', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const { clientId, token } = await pairClient(dev);
  const client = await connectClient(device.id, clientId, token);
  await client.next('ready');

  for (let i = 0; i < 25; i++) client.send({ type: 'cmd', id: String(i), action: 'next' });
  const limited = await client.next('result');
  assert.equal(limited.error, 'rate_limited');

  client.close(); dev.close();
});

// ------------------------------------------------------------------ party

const postJoin = (body) => fetch(`${HTTP}/v1/party/join`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

async function startParty(dev) {
  dev.send({ type: 'party.start' });
  const { party } = await dev.next('party');
  await dev.next('guests');
  return party;
}

async function joinGuest(deviceId, secret, name = 'Sam') {
  const res = await postJoin({ deviceId, secret, name });
  assert.equal(res.status, 200, 'joining should succeed');
  return res.json();
}

async function connectGuest(deviceId, guestId, token) {
  const sock = open(deviceId);
  await sock.opened;
  sock.send({ type: 'auth', role: 'guest', guestId, token });
  return sock;
}

test('party: start is idempotent and the link lets guests join by name', async () => {
  const device = newDevice();
  const { sock: dev, ready } = await connectDevice(device);
  assert.deepEqual(ready.party, { active: false });

  const party = await startParty(dev);
  assert.equal(party.active, true);
  assert.match(party.secret, /^[\w-]{40,}$/);
  assert.equal(party.guestCount, 0);
  assert.equal((await startParty(dev)).secret, party.secret, 'starting again keeps the same link');

  assert.equal((await postJoin({ deviceId: device.id, secret: 'wrong', name: 'Sam' })).status, 404);
  assert.equal((await postJoin({ deviceId: device.id, secret: party.secret, name: '  \u0000 ' })).status, 400);

  const joined = await joinGuest(device.id, party.secret, '  Sam\u200b   the   DJ with a very long name indeed ');
  assert.equal(joined.deviceId, device.id);
  assert.match(joined.guestId, /^g[a-z2-7]{26}$/);
  assert.equal(joined.name, 'Sam the DJ with a very l');
  const { guests } = await dev.next('guests');
  assert.equal(guests.length, 1);
  assert.ok(!('tokenHash' in guests[0]));
  assert.equal((await dev.next('party')).party.guestCount, 1);

  const guest = await connectGuest(device.id, joined.guestId, joined.token);
  const guestReady = await guest.next('ready');
  assert.equal(guestReady.role, 'guest');
  assert.equal(guestReady.name, joined.name);
  assert.equal(guestReady.party.active, true);
  assert.ok(!('secret' in guestReady.party), 'guests never receive the link secret');

  const forged = await connectGuest(device.id, joined.guestId, 'nope');
  assert.equal((await forged.closed).code, 4001);

  guest.close(); dev.close();
});

test('party: guests may only search and request; the relay stamps who asked', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const party = await startParty(dev);
  const joined = await joinGuest(device.id, party.secret, 'Priya');
  const guest = await connectGuest(device.id, joined.guestId, joined.token);
  await guest.next('ready');

  for (const action of ['next', 'playVideo', 'volume', 'queue.clear', 'queue.move']) {
    guest.send({ type: 'cmd', id: action, action });
    const result = await guest.next('result');
    assert.equal(result.error, 'forbidden', action);
  }
  await assert.rejects(dev.next('cmd', 500), /timed out/, 'forbidden commands never reach the phone');

  guest.send({ type: 'cmd', id: 'a1', action: 'queue.add', args: { videoId: 'RvegizX3GqY' }, from: { id: 'someone-else', name: 'Host' } });
  const cmd = await dev.next('cmd');
  assert.equal(cmd.action, 'queue.add');
  assert.deepEqual(cmd.from, { id: joined.guestId, name: 'Priya', role: 'guest' });

  dev.send({ type: 'result', id: cmd.id, ok: true, data: { position: 1 } });
  assert.deepEqual(await guest.next('result'), { type: 'result', id: 'a1', ok: true, data: { position: 1 } });

  guest.close(); dev.close();
});

test('party: remotes keep full control and see the link; queue is cached for everyone', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const { clientId, token } = await pairClient(dev, 'PC');
  const remote = await connectClient(device.id, clientId, token);
  assert.deepEqual((await remote.next('ready')).party, { active: false });

  const party = await startParty(dev);
  assert.equal((await remote.next('party')).party.secret, party.secret);

  remote.send({ type: 'cmd', id: 'm', action: 'queue.move', args: { itemId: 'x', toIndex: 0 } });
  const cmd = await dev.next('cmd');
  assert.deepEqual(cmd.from, { id: clientId, name: 'PC', role: 'client' });

  const joined = await joinGuest(device.id, party.secret);
  const guest = await connectGuest(device.id, joined.guestId, joined.token);
  await guest.next('ready');

  const queue = { items: [{ itemId: 'i1', videoId: 'RvegizX3GqY', title: 'Numb', requestedBy: [{ id: joined.guestId, name: 'Sam' }] }] };
  dev.send({ type: 'queue', queue });
  assert.deepEqual((await remote.next('queue')).queue, queue);
  assert.deepEqual((await guest.next('queue')).queue, queue);

  const late = await connectGuest(device.id, joined.guestId, joined.token);
  const lateReady = await late.next('ready');
  assert.deepEqual(lateReady.queue, queue);
  assert.equal(lateReady.party.guestCount, 1);

  dev.send({ type: 'queue', queue: { items: [{ blob: 'x'.repeat(130 * 1024) }] } });
  assert.equal((await dev.next('error')).error, 'queue_too_large');

  remote.close(); guest.close(); late.close(); dev.close();
});

test('party: guests see presence and state; features reach browsers', async () => {
  const device = newDevice();
  const first = await connectDevice(device);
  const party = await startParty(first.sock);
  const joined = await joinGuest(device.id, party.secret);
  first.sock.close();
  await first.sock.closed;

  const guest = await connectGuest(device.id, joined.guestId, joined.token);
  assert.equal((await guest.next('ready')).deviceOnline, false);

  const dev = open(device.id);
  await dev.opened;
  dev.send({ type: 'auth', role: 'device', secret: device.secret, features: ['queue', 42] });
  const ready = await dev.next('ready');
  assert.equal(ready.party.secret, party.secret, 'the phone gets its party back after reconnecting');
  assert.equal(ready.guests.length, 1);
  assert.deepEqual(await guest.next('presence'), { type: 'presence', deviceOnline: true, features: ['queue'] });

  dev.send({ type: 'state', state: { title: 'Numb' } });
  assert.equal((await guest.next('state')).state.title, 'Numb');

  guest.close(); dev.close();
});

test('party: new link stops the old one but keeps guests', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const party = await startParty(dev);
  const joined = await joinGuest(device.id, party.secret);
  await dev.next('party');
  const guest = await connectGuest(device.id, joined.guestId, joined.token);
  await guest.next('ready');

  dev.send({ type: 'party.newLink' });
  const renewed = (await dev.next('party')).party;
  assert.notEqual(renewed.secret, party.secret);
  assert.equal((await postJoin({ deviceId: device.id, secret: party.secret, name: 'Late' })).status, 404);
  await joinGuest(device.id, renewed.secret, 'Late');
  assert.equal((await guest.next('party')).party.active, true);

  guest.send({ type: 'cmd', id: 's', action: 'search', args: { query: 'numb' } });
  assert.equal((await dev.next('cmd')).action, 'search');

  guest.close(); dev.close();
});

test('party: removing a guest disconnects them; ending the party clears everything', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const { clientId, token } = await pairClient(dev);
  await dev.next('clients');
  const remote = await connectClient(device.id, clientId, token);
  await remote.next('ready');

  const party = await startParty(dev);
  const a = await joinGuest(device.id, party.secret, 'A');
  const b = await joinGuest(device.id, party.secret, 'B');
  const guestA = await connectGuest(device.id, a.guestId, a.token);
  const guestB = await connectGuest(device.id, b.guestId, b.token);
  await guestA.next('ready');
  await guestB.next('ready');
  dev.send({ type: 'queue', queue: { items: [{ itemId: 'i1' }] } });
  await remote.next('queue');

  dev.send({ type: 'guests.remove', guestId: a.guestId });
  assert.equal((await guestA.closed).code, 4003);
  let guests;
  do ({ guests } = await dev.next('guests')); while (guests.some((g) => g.name === 'A'));
  assert.equal(guests[0].name, 'B');
  assert.equal((await connectGuest(device.id, a.guestId, a.token).then((s) => s.closed)).code, 4001);

  dev.send({ type: 'party.end' });
  assert.equal((await guestB.closed).code, 4004);
  let view;
  do ({ party: view } = await remote.next('party')); while (view.active);
  assert.deepEqual(view, { active: false });
  assert.equal((await remote.next('queue')).queue, null);
  assert.equal((await connectGuest(device.id, b.guestId, b.token).then((s) => s.closed)).code, 4004);
  assert.equal((await postJoin({ deviceId: device.id, secret: party.secret, name: 'C' })).status, 404);

  // Remotes are untouched by the party ending.
  remote.send({ type: 'cmd', id: 'n', action: 'next' });
  assert.equal((await dev.next('cmd')).action, 'next');

  remote.close(); dev.close();
});

test('party: joins are limited per network and per party size', async () => {
  const device = newDevice();
  const { sock: dev } = await connectDevice(device);
  const party = await startParty(dev);
  const statuses = [];
  for (let i = 0; i < 12; i++) statuses.push((await postJoin({ deviceId: device.id, secret: party.secret, name: `G${i}` })).status);
  assert.deepEqual(statuses.slice(0, 10), Array(10).fill(200));
  assert.equal(statuses.at(-1), 429);
  dev.close();
});

// Last: it rate-limits this IP for every later pairing attempt.
test('guessing pairing codes gets rate limited', async () => {
  const statuses = [];
  for (let i = 0; i < 12; i++) statuses.push((await postPair('ZZZZ-ZZZZ')).status);
  // Earlier tests already spent one failure (re-using a claimed code).
  assert.equal(statuses[0], 404, `first guess is a plain miss: ${statuses}`);
  assert.equal(statuses.at(-1), 429, `sustained guessing is blocked: ${statuses}`);
});
