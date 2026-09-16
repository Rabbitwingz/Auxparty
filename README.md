# Music Remote

Spotify-Connect-style remote control for YouTube Music (and Plex) on Android.
Pick and control music from any browser, anywhere, while the phone stays the
thing actually playing it: its Bluetooth, its EQ, its speakers.

YouTube Music has no remote-control feature of its own, and Google Cast only
works *from* a phone, never *to* one. So the phone runs a small companion app
that controls the music locally, and browsers talk to it through a relay.

```
 Android app ──outbound WebSocket──►  Relay (Cloudflare)  ◄── Browser, anywhere
 • reads + controls media sessions    • one room per phone      • UI on Vercel
 • YouTube Music search, on-device    • pairing-code tokens
```

## Repository

| Path | What | Status |
| --- | --- | --- |
| `android/` | Companion app (Kotlin; OkHttp is the only dependency) | Relay client, pairing, diagnostics |
| `relay/` | Cloudflare Worker + Durable Objects ([protocol](relay/PROTOCOL.md)) | Built, 17 integration tests |
| `web/` | Static browser UI for Vercel, no build step | Built, verified end to end locally |
| `local-adb/` | Original PC-only version driving the phone over ADB | Working; superseded |

## Design decisions

- **Search runs on the phone, not a server.** Each user searches from their own
  connection, so there's no shared data-centre IP for YouTube to rate-limit, and
  no server cost that grows with users.
- **Pairing codes, no accounts.** The phone shows a code; entering it in a
  browser grants that browser a token for that one phone, revocable from the app.
- **Packages are matched by name shape.** Patched builds (Morphe, ReVanced)
  rename YouTube Music, e.g. `app.morphe.android.apps.youtube.music`.
- **Position = `position + (now − lastPositionUpdateTime) × speed`.** YouTube
  Music publishes position once per track; reading `position` alone reports 0.

## Local development

Run the whole system without a phone. `relay/tools/fake-phone.mjs` speaks the
device side of the protocol, with real YouTube Music search results:

```
npm --prefix relay install
npm --prefix relay run dev -- --port 8787     # relay
node web/dev-server.mjs 5173                  # web UI
node relay/tools/fake-phone.mjs               # prints a pairing code
```

Then open `http://127.0.0.1:5173/?relay=http://127.0.0.1:8787` and enter the code.
Relay tests: `npm --prefix relay test`.

## Building the app

There is no local build. GitHub Actions builds every push to `main`, runs the
unit tests, and publishes the APK to a rolling release:

```
https://github.com/Rabbitwingz/YTM-Bridge/releases/download/android-latest/music-remote.apk
```

Builds are signed with a stable release key supplied through repository secrets,
so new builds install over old ones. The key is **never** committed. See
`signing/github-secrets.txt` on the machine that generated it.

## Installing on a phone

1. Download `music-remote.apk` from the link above and install it (allow your
   browser to install unknown apps when asked).
2. Open **Music Remote** and work through the checks:
   - **Notification access.** On Android 13+ sideloaded apps get this blocked.
     If the switch is greyed out, open **App info → ⋮ → Allow restricted
     settings**, then grant it.
   - **Display over other apps.** Required to start songs from the background.
   - **Unrestricted battery.** Essential on Samsung, which otherwise puts the
     app to sleep.
