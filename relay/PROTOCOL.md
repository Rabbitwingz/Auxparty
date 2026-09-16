# Relay protocol (v1)

Three parties: the **device** (Android app), browsers, and the **relay** (a Cloudflare
Worker). Browsers come in two kinds: **clients**, linked with a pairing code, are full
remotes; **guests**, who joined a party through its link, can only search and request
songs. Each phone gets its own Durable Object, called a *room*. The relay never
interprets playback: it authenticates, routes, and caches the latest state.

All WebSocket messages are JSON text frames, except keep-alives: send the literal
text `ping` and the relay answers `pong` without waking the room.

## Identity

| Secret | Held by | Relay stores |
| --- | --- | --- |
| `deviceId` — 26-char random id | device; shared with clients and guests | nothing (it names the room) |
| `deviceSecret` — 256-bit random | device only | SHA-256 hash, captured on first connect |
| `clientToken` — 256-bit random | one browser | SHA-256 hash + name + timestamps |
| party `secret` — 256-bit random | the phone, clients, anyone with the link | the secret itself, so remotes can re-share the link |
| `guestToken` — 256-bit random | one guest's browser | SHA-256 hash + name + timestamps; deleted when the party ends |

Secrets are never sent to a server in a URL. The party link carries its secret in the
fragment, which browsers don't send: `https://<web>/#party=<deviceId>.<secret>`.
Sockets authenticate with their first message.

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

### `POST /v1/party/join`

Join a running party as a guest. Names are stripped of control characters, whitespace
is collapsed, and they're cut to 24 characters. Each IP gets 10 join attempts per
10 minutes per party, and a party holds at most 50 guests.

```json
→ { "deviceId": "…", "secret": "…", "name": "Sam" }
← 200 { "deviceId": "…", "guestId": "g…", "token": "…", "name": "Sam" }
← 400 { "error": "name_required" }
← 404 { "error": "invalid_link" }      // no party, it ended, or the link was replaced
← 409 { "error": "party_full" }
← 429 { "error": "rate_limited" }
```

### `GET /health` → `200 ok`

## WebSocket: `GET /v1/ws?device=<deviceId>`

The first message must be `auth` within 10 seconds, or the socket closes with 4001.

```json
{ "type": "auth", "role": "device", "secret": "…", "features": ["queue"] }
{ "type": "auth", "role": "client", "clientId": "…", "token": "…" }
{ "type": "auth", "role": "guest", "guestId": "…", "token": "…" }
```

`features` lists what this build of the app supports (`queue`: party mode). Browsers
receive it, so they can ask the host to update the app instead of failing silently.

→ `{ "type": "ready", ... }` on success. Close codes: **4001** bad credentials,
**4003** a client was revoked or a guest removed, **4004** the guest's party has ended,
**4000** a newer device connection replaced this one.

### Browser → relay

| Message | Meaning |
| --- | --- |
| `{ "type": "cmd", "id": "7", "action": "…", "args": {…} }` | Forwarded to the device |

| Action | Client | Guest |
| --- | --- | --- |
| `play`, `pause`, `playPause`, `next`, `previous` | ✓ | |
| `seek {positionMs}`, `volume {value}` | ✓ | |
| `playVideo {videoId, title, artist}` | ✓ | |
| `search {query}` | ✓ | ✓ |
| `queue.add {videoId, title, artist, album, duration, thumbnail}` | ✓ | ✓ |
| `queue.remove {itemId}` | ✓ any song | ✓ own songs (the phone enforces this) |
| `queue.move {itemId, toIndex}`, `queue.clear` | ✓ | |

An action the role may not use fails with `forbidden`; an unknown one fails with
`unknown_action`. Neither reaches the phone.

### Relay → browser

| Message | When |
| --- | --- |
| `{ "type": "ready", "role": "client"\|"guest", "deviceOnline": bool, "features": [...], "state": {…}\|null, "artwork": {…}\|null, "queue": {…}\|null, "party": {…} }` | After auth. Guests also get `guestId` and `name`. |
| `{ "type": "state", "state": {…} }` | Device published new state |
| `{ "type": "artwork", "artwork": { "key": "…", "mime": "image/jpeg", "data": "<base64>" } }` | Track artwork changed |
| `{ "type": "presence", "deviceOnline": bool, "features": [...] }` | Device connected (with its features) / disconnected |
| `{ "type": "queue", "queue": {…}\|null }` | The phone changed the queue; `null` when there's no party |
| `{ "type": "party", "party": { "active": true, "startedAt", "guestCount", "secret" } }` | Party started, link replaced, guest joined or removed. `secret` goes to clients only. `{ "active": false }` when it ends. |
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
| `{ "type": "queue", "queue": {…}\|null }` | Latest queue; cached and broadcast (max 128 KB, else `error: queue_too_large`) |
| `{ "type": "party.start" }` | Start a party, or re-send the running one |
| `{ "type": "party.newLink" }` | Replace the link secret; guests already in stay |
| `{ "type": "party.end" }` | Disconnect all guests (4004) and forget them and the cached queue |
| `{ "type": "guests.list" }` | Request the guests |
| `{ "type": "guests.remove", "guestId": "…" }` | Remove a guest and disconnect them (4003) |

### Relay → device

| Message | When |
| --- | --- |
| `{ "type": "ready", "clients": [...], "party": {…}, "guests": [...] }` | After auth |
| `{ "type": "cmd", "id": "<opaque>", "action": "…", "args": {…}, "from": { "id", "name", "role" } }` | From a browser. Reply with the same `id`. The relay sets `from`; the browser can't. |
| `{ "type": "pair.code", "code": "K7QM-3XPD", "expiresAt": 1789… }` | |
| `{ "type": "clients", "clients": [{ "clientId", "name", "createdAt", "lastSeenAt" }] }` | On request, pairing, revoke |
| `{ "type": "party", "party": {…} }` | Party started, link replaced, guest joined or removed, ended |
| `{ "type": "guests", "guests": [{ "guestId", "name", "joinedAt", "lastSeenAt" }] }` | On request, join, remove, start, end |

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

### `queue` shape (owned by the phone)

```json
{
  "current": { "…": "an item, as below" },
  "items": [{
    "itemId": "5f2a91c0d3e4", "videoId": "RvegizX3GqY",
    "title": "Numb", "artist": "Elderbrook", "album": "…", "durationMs": 231000,
    "thumbnail": "https://lh3.googleusercontent.com/…",
    "requestedBy": [{ "id": "g…", "name": "Sam", "role": "guest" }],
    "addedAt": 1789544343049
  }],
  "limitPerGuest": 3
}
```

`current` is the requested song playing now, or `null` while something else plays
(YouTube Music's autoplay, or the host's own pick). `items` are up next, in order.
`requestedBy[0]` added the song; later entries asked for it again.

| Action | `data` on success | Errors |
| --- | --- | --- |
| `queue.add` | `{ itemId, position, duplicate }` (`position` 0 means it started playing) | `party_off`, `limit_reached`, `bad_request` |
| `queue.remove` | `null` | `party_off`, `not_found`, `not_yours` |
| `queue.move`, `queue.clear` | `null` | `party_off`, `not_found` |

During a party, `next` skips to the next request when there is one.

## Limits

Messages over 256 KB are rejected. Each client or guest may send 20 commands per 10 seconds.
