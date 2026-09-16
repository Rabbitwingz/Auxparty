# Relay protocol (v1)

Three parties: the **device** (Android app), **clients** (browsers), and the **relay**
(a Cloudflare Worker). Each phone gets its own Durable Object, called a *room*. The
relay never interprets playback: it authenticates, routes, and caches the latest state.

All WebSocket messages are JSON text frames, except keep-alives: send the literal
text `ping` and the relay answers `pong` without waking the room.

## Identity

| Secret | Held by | Relay stores |
| --- | --- | --- |
| `deviceId` — 26-char random id | device; shared with paired clients | nothing (it names the room) |
| `deviceSecret` — 256-bit random | device only | SHA-256 hash, captured on first connect |
| `clientToken` — 256-bit random | one browser | SHA-256 hash + name + timestamps |

Secrets never appear in URLs. Sockets authenticate with their first message.

## HTTP

### `POST /v1/pair`

Exchange a pairing code shown on the phone for a client token. Codes are
`XXXX-XXXX` (dashes, spaces and case are ignored), last 5 minutes, and work once.
Failed attempts are rate-limited per IP.

```json
→ { "code": "K7QM-3XPD", "name": "Chrome on Windows" }
← 200 { "deviceId": "…", "clientId": "…", "token": "…" }
← 404 { "error": "invalid_code" }      // unknown, expired, or already used
← 429 { "error": "rate_limited" }
```

### `GET /health` → `200 ok`

## WebSocket: `GET /v1/ws?device=<deviceId>`

The first message must be `auth` within 10 seconds, or the socket closes with 4001.

```json
{ "type": "auth", "role": "device", "secret": "…" }
{ "type": "auth", "role": "client", "clientId": "…", "token": "…" }
```

→ `{ "type": "ready", ... }` on success; close code **4001** on bad credentials,
**4003** when a client is revoked, **4000** when a newer device connection replaces this one.

### Client → relay

| Message | Meaning |
| --- | --- |
| `{ "type": "cmd", "id": "7", "action": "…", "args": {…} }` | Forwarded to the device |

Actions: `play`, `pause`, `playPause`, `next`, `previous`,
`seek {positionMs}`, `volume {value}`, `playVideo {videoId}`,
`playPlaylist {playlistId}`, `search {query}`.

### Relay → client

| Message | When |
| --- | --- |
| `{ "type": "ready", "deviceOnline": bool, "state": {…}\|null, "artwork": {…}\|null }` | After auth |
| `{ "type": "state", "state": {…} }` | Device published new state |
| `{ "type": "artwork", "artwork": { "key": "…", "mime": "image/jpeg", "data": "<base64>" } }` | Track artwork changed |
| `{ "type": "presence", "deviceOnline": bool }` | Device connected / disconnected |
| `{ "type": "result", "id": "7", "ok": true, "data": … }` | Reply to a `cmd` |
| `{ "type": "result", "id": "7", "ok": false, "error": "device_offline" }` | |

### Device → relay

| Message | Meaning |
| --- | --- |
| `{ "type": "state", "state": {…} }` | Latest now-playing; cached and broadcast |
| `{ "type": "artwork", "artwork": {…} }` | Only when the track changes; cached |
| `{ "type": "result", "id": "…", "ok": …, "data"/"error": … }` | Reply to a forwarded `cmd`; `id` echoed verbatim |
| `{ "type": "pair.create" }` | Request a pairing code |
| `{ "type": "clients.list" }` | Request the paired browsers |
| `{ "type": "clients.revoke", "clientId": "…" }` | Unpair a browser and disconnect it |

### Relay → device

| Message | When |
| --- | --- |
| `{ "type": "ready", "clients": [...] }` | After auth |
| `{ "type": "cmd", "id": "<opaque>", "action": "…", "args": {…} }` | From a client. Reply with the same `id`. |
| `{ "type": "pair.code", "code": "K7QM-3XPD", "expiresAt": 1789… }` | |
| `{ "type": "clients", "clients": [{ "clientId", "name", "createdAt", "lastSeenAt" }] }` | On request, pairing, revoke |

### `state` shape

```json
{
  "source": "YouTube Music", "packageName": "app.morphe.android.apps.youtube.music",
  "title": "Numb", "artist": "Elderbrook", "album": "Why Do We Shake In The Cold?",
  "durationMs": 231000, "positionMs": 42000, "positionAt": 1789544343049,
  "playback": "playing", "volume": 5, "maxVolume": 25, "artworkKey": "…"
}
```

`positionAt` is the device's wall clock (ms) when `positionMs` was true. Clients
extrapolate while `playback` is `playing`.

## Limits

Messages over 256 KB are rejected. Each client may send 20 commands per 10 seconds.
