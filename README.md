# Auxparty

Let friends pick the music playing on your Android phone, from any browser, anywhere.
The phone stays the thing actually playing: its Bluetooth, its EQ, its speakers.

Open **https://auxparty.vercel.app** to join. YouTube Music has no remote-control
feature of its own, and Google Cast only works *from* a phone, never *to* one. So the
phone runs a small companion app that controls the music locally, and browsers talk to
it through a relay.

```
 Android app ──outbound WebSocket──►  Relay (Cloudflare)  ◄── Browser, anywhere
 • controls what's playing            • one room per phone      • UI on Vercel
 • starts songs in YouTube Music      • pairing codes, party links
 • YouTube Music search, on-device
 • runs the party queue
```

## Two modes

Switch between them on the app's Home screen.

- **Remote.** Browsers linked with a pairing code control the phone: play, pause, skip,
  seek, volume, and search. A song picked in a browser or in the app plays straight away.
- **Party.** Share a link or QR code. Friends open it, enter a name, and request songs into
  a queue everyone can see. The phone plays requests in order, starting the next one as
  each song ends. Guests can take back their own requests. The host, in the app or a
  linked browser, can add, remove, reorder or skip any song, and remove guests.

Playback controls work on whatever is playing on the phone. Starting a song (search,
requests, the queue) always uses **YouTube Music**, including patched builds like Morphe
and ReVanced.

## Repository

| Path | What |
| --- | --- |
| `android/` | The app: Kotlin, Jetpack Compose, Material 3 Expressive |
| `relay/` | Cloudflare Worker + Durable Objects ([protocol](relay/PROTOCOL.md)), with integration tests |
| `web/` | Browser UI on Vercel: Vite + TypeScript, no framework |
| `design/` | Shared icons and brand assets, generated into the app and the site |

## How it works

- **Starting songs without opening YouTube Music.** YouTube Music's media session accepts
  `playFromUri`, the entry point Android Auto and assistants use, so a song starts in the
  background. Each start is verified against the session's metadata; opening the app with
  a deep link is only the last resort.
- **The party queue lives on the phone.** YouTube Music has no API for adding to its queue,
  so Auxparty keeps its own (saved across restarts) and starts the next request just before
  a song ends. If autoplay jumps in first, the waiting request cuts in; a song the host
  picked mid-song is left to finish. The relay only caches the queue for browsers.
- **Search runs on the phone, not a server.** Each host searches from their own
  connection, so there's no shared data-centre IP for YouTube to rate-limit, and no server
  cost that grows with users.
- **No accounts.** A pairing code grants one browser a token for one phone, revocable
  from the app. Party links carry their secret in the URL fragment, which is never sent to
  a server. Guests can only search and request; the relay enforces this.
- **Position = `position + (now − lastPositionUpdateTime) × speed`.** YouTube Music
  publishes position once per track; reading `position` alone reports 0.

## Installing on a phone

1. Download the app:
   `https://github.com/Rabbitwingz/YTM-Bridge/releases/download/android-latest/music-remote.apk`
2. Install it. If **Google Play Protect** blocks the install, open the Play Store, tap your
   profile picture → **Play Protect** → ⚙ → pause **Scan apps with Play Protect**, install,
   then turn scanning back on.
3. Open **Auxparty** and follow setup:
   - **Notification access**, to see and control what's playing. If the switch is greyed
     out, open **App info → ⋮ → Allow restricted settings**, then try again.
   - **Display over other apps**, the fallback for starting songs from the background.
   - **Unrestricted battery**, essential on Samsung, which otherwise puts the app to sleep.
4. Tap **Invite** to link a browser, or switch to **Party** and share the link.

## Local development

Run everything without a phone. `relay/tools/fake-phone.mjs` speaks the phone's side of
the protocol, with real YouTube Music search results and a working party queue:

```
npm --prefix relay install && npm --prefix web install
npm --prefix relay run dev -- --port 8787     # relay
npm --prefix web run dev                      # web UI on :5173
node relay/tools/fake-phone.mjs --party --song-seconds=60   # prints a pairing code and a party link
```

Open `http://127.0.0.1:5173/?relay=http://127.0.0.1:8787` once so the site uses the
local relay, then enter the code (remote) or open the party link (guest). Use a
different origin, e.g. `localhost` vs `127.0.0.1`, to be a remote and a guest at once.

- Relay tests: `npm --prefix relay test`
- Deploy the relay: `npx wrangler deploy` in `relay/`, then `node relay/tools/smoke.mjs <relay url>`

## Builds and releases

There is no local Android build. GitHub Actions builds every push, runs the unit tests,
and renders UI screenshots to the `ui-screenshots` release:

- **`main`:** the signed APK goes to the `android-latest` release, and Vercel deploys the
  site to production.
- **Other branches:** the APK goes to `android-preview` (`auxparty-preview.apk`), and
  Vercel makes a preview deployment.

Builds are signed with a stable release key from repository secrets, so new builds
install over old ones. The key is **never** committed.
