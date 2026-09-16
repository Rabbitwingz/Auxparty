// Post-deploy smoke test: a throwaway phone and browser exercise the real relay.
//   node tools/smoke.mjs https://relay.auxparty.workers.dev
// Deliberately avoids anything that trips rate limits (e.g. bad pairing codes).
import WebSocket from 'ws';
import { randomBytes } from 'node:crypto';

const RELAY = (process.argv[2] ?? '').replace(/\/+$/, '');
if (!RELAY) {
  console.error('usage: node tools/smoke.mjs <relay url>');
  process.exit(2);
}

function open(deviceId) {
  const ws = new WebSocket(`${RELAY.replace(/^http/, 'ws')}/v1/ws?device=${deviceId}`);
  const inbox = [];
  const waiters = [];
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    const i = waiters.findIndex((w) => w.type === msg.type);
    if (i >= 0) waiters.splice(i, 1)[0].resolve(msg);
    else inbox.push(msg);
  });
  const closed = new Promise((r) => ws.on('close', (code) => r(code)));
  return {
    ws, closed,
    opened: new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); }),
    send: (m) => ws.send(JSON.stringify(m)),
    next(type, ms = 8000) {
      const i = inbox.findIndex((m) => m.type === type);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((resolve, reject) => {
        const w = { type, resolve };
        waiters.push(w);
        setTimeout(() => { if (waiters.includes(w)) reject(new Error(`timed out waiting for ${type}`)); }, ms);
      });
    },
  };
}

const steps = [];
async function step(name, fn) {
  const t = Date.now();
  try {
    const out = await fn();
    steps.push(`  ok   ${name} (${Date.now() - t}ms)${out ? ` — ${out}` : ''}`);
  } catch (e) {
    steps.push(`  FAIL ${name}: ${e.message}`);
    console.log(steps.join('\n'));
    process.exit(1);
  }
}

const device = { id: randomBytes(16).toString('hex').slice(0, 26), secret: randomBytes(32).toString('base64url') };
let dev, client, paired;

await step('health', async () => {
  const r = await fetch(`${RELAY}/health`);
  if (r.status !== 200) throw new Error(`HTTP ${r.status}`);
});

await step('device connects and authenticates', async () => {
  dev = open(device.id);
  await dev.opened;
  dev.send({ type: 'auth', role: 'device', secret: device.secret });
  await dev.next('ready');
});

await step('pairing code issued and exchanged for a token', async () => {
  dev.send({ type: 'pair.create' });
  const { code } = await dev.next('pair.code');
  const r = await fetch(`${RELAY}/v1/pair`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, name: 'smoke test' }),
  });
  if (r.status !== 200) throw new Error(`HTTP ${r.status} ${await r.text()}`);
  paired = await r.json();
  return `code ${code}`;
});

await step('browser authenticates and sees the phone online', async () => {
  client = open(device.id);
  await client.opened;
  client.send({ type: 'auth', role: 'client', clientId: paired.clientId, token: paired.token });
  const ready = await client.next('ready');
  if (!ready.deviceOnline) throw new Error('deviceOnline=false');
});

await step('state published by the phone reaches the browser', async () => {
  dev.send({ type: 'state', state: { title: 'smoke', playback: 'paused' } });
  const { state } = await client.next('state');
  if (state.title !== 'smoke') throw new Error(JSON.stringify(state));
});

await step('command round trip', async () => {
  const t = Date.now();
  client.send({ type: 'cmd', id: 'x1', action: 'next' });
  const cmd = await dev.next('cmd');
  dev.send({ type: 'result', id: cmd.id, ok: true, data: 'pong' });
  const result = await client.next('result');
  if (result.id !== 'x1' || result.data !== 'pong') throw new Error(JSON.stringify(result));
  return `${Date.now() - t}ms browser→phone→browser`;
});

await step('party: guest joins, may request but not skip, and is closed when it ends', async () => {
  dev.send({ type: 'party.start' });
  const { party } = await dev.next('party');
  const r = await fetch(`${RELAY}/v1/party/join`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId: device.id, secret: party.secret, name: 'smoke guest' }),
  });
  if (r.status !== 200) throw new Error(`join HTTP ${r.status} ${await r.text()}`);
  const joined = await r.json();

  const guest = open(device.id);
  await guest.opened;
  guest.send({ type: 'auth', role: 'guest', guestId: joined.guestId, token: joined.token });
  const ready = await guest.next('ready');
  if (ready.role !== 'guest' || 'secret' in ready.party) throw new Error(JSON.stringify(ready.party));

  guest.send({ type: 'cmd', id: 'g1', action: 'next' });
  const refused = await guest.next('result');
  if (refused.error !== 'forbidden') throw new Error(JSON.stringify(refused));

  guest.send({ type: 'cmd', id: 'g2', action: 'queue.add', args: { videoId: 'RvegizX3GqY' } });
  const cmd = await dev.next('cmd');
  if (cmd.from?.name !== 'smoke guest') throw new Error(JSON.stringify(cmd.from));

  dev.send({ type: 'party.end' });
  const code = await guest.closed;
  if (code !== 4004) throw new Error(`close code ${code}`);
});

await step('revocation disconnects the browser', async () => {
  dev.send({ type: 'clients.revoke', clientId: paired.clientId });
  const code = await client.closed;
  if (code !== 4003) throw new Error(`close code ${code}`);
});

dev.ws.close();
console.log(`smoke test against ${RELAY}\n${steps.join('\n')}\nall passed`);
process.exit(0);
