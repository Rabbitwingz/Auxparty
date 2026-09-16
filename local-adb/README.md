# YT Music Remote

Spotify-Connect-style control of **YouTube Music** (and Plex) running on your Android
phone, driven from a web UI on your PC.

The phone keeps playing to your home theatre over Bluetooth — untouched EQ, untouched
audio path. The PC is purely a remote control.

## Why it works this way

YouTube Music has no server-side "active device" concept the way Spotify Connect does,
and Google Cast is one-directional: a phone can cast *to* a speaker, but can never be a
Cast *receiver*. So there is no way to make the web player target your phone.

Instead this drives the phone's own **MediaSession** over ADB across Wi-Fi:

| Need | Mechanism |
| --- | --- |
| Play / pause / next / previous | `cmd media_session dispatch` |
| Now playing | `dumpsys media_session` |
| Track position | `position` + `updated` + `speed`, against the device clock |
| Play a specific track | `am start` with a `music.youtube.com/watch?v=…` deep link |
| Volume | `cmd media_session volume --stream 3` |
| Search, artwork, duration | [`youtubei.js`](https://github.com/LuanRT/YouTube.js), unauthenticated |

Because `media_session` is app-agnostic, transport control works for **Plex, Spotify and
podcast apps too** — whatever owns the active session. Only "play this track" is YT
Music specific.

No root, no sideloaded app, no Android SDK, no Java.

## One-time setup

On the phone:

1. **Settings → About phone → Build number**, tap 7 times to unlock Developer options.
2. **Settings → Developer options → Wireless debugging** → turn on.
3. Tap **Pair device with pairing code**. Leave the screen open.

On the PC:

```bash
npm start
```

Open <http://localhost:8781>, enter the `IP:port` and 6-digit code from the phone, and
press **Pair**. Pairing is permanent — from then on it reconnects by itself.

## Daily use

```bash
npm start
```

Search, click a result, and it starts playing on the phone. Transport controls and the
now-playing display reflect whatever the phone is doing, including when you change
tracks on the phone itself.

## Tests

```bash
node --test ./test/parse.test.js
```

The `dumpsys` output format drifts between Android releases and OEMs, so the parser is
deliberately loose and covered by tests built from real device output. If now-playing
ever goes blank after an OS update, dump the raw text and compare:

```bash
tools/platform-tools/adb.exe shell dumpsys media_session
```

## Notes from the device this was built against

Three things only surfaced against real hardware. All are handled in code, but they are
the first places to look if behaviour changes.

**The app package is not `com.google.android.apps.youtube.music`.** Patched builds
(Morphe, ReVanced) rename themselves — on this phone it is
`app.morphe.android.apps.youtube.music`. Deep links fail with *"unable to resolve
Intent"* against the stock name. The package is therefore detected at runtime by
matching any package ending in `youtube.music`, and sessions are ranked by name shape
rather than an exact allow-list, so a rename keeps working.

**`position` on its own is meaningless.** YouTube Music publishes a PlaybackState once
per track and never refreshes it, so a song two minutes in still reports `position=0`
with a frozen `updated`. True elapsed time is:

```
position + (deviceUptime - updated) × speed
```

`updated` is measured on the device's uptime clock, so it has to be compared against the
*phone's* clock rather than the PC's. `nowPlaying()` fetches `dumpsys media_session` and
`/proc/uptime` in a single round trip so the two stay consistent. This is the correct
general reading of MediaSession, not a workaround for one app.

**Session blocks are delimited by the `package=` line**, not by the header. Real headers
carry a free-text tag before the package name:

```
YouTube playerlib app.morphe.android.apps.youtube.music/YouTube playerlib/26 (userId=0)
```

and playback states print as `PLAYING(3)` rather than `3`. Parsing the header instead
silently attributes the music to whichever session came first — usually
`com.android.server.telecom`.

## Known limits

- **Queue editing isn't possible.** YT Music's MediaSession doesn't expose
  `addQueueItem`, so there's no "add to queue" — only play-now and playlist/album deep
  links.
- **Android 14+ randomises the wireless-debugging port** after each reboot. Handled by
  mDNS discovery, but the phone must be on the same LAN, and some builds turn wireless
  debugging off on reboot — if the status pill goes red after a restart, re-toggle it.
- **`dumpsys` metadata is comma-joined and unescaped**, so a title containing commas is
  ambiguous. Worked around by searching YT Music for the raw description and preferring
  its canonical metadata — which is also where artwork and duration come from, since
  `dumpsys` exposes neither.
- **Non-YT-Music sources show no artwork.** Metadata is only trusted when the title
  matches a catalogue hit, so Plex tracks display as text rather than risk a wrong cover.
- **Seeking is display-only.** There's no reliable MediaSession seek verb over ADB.

## Layout

```
server/adb.js          ADB transport: discovery, pairing, dispatch, dumpsys parsing
server/ytmusic.js      YouTube Music search + metadata resolution (youtubei.js)
server/index.js        HTTP + WebSocket server, state poll loop
public/                Single-page UI
test/parse.test.js     Parser tests, built from real device output
tools/platform-tools/  Bundled adb
```
