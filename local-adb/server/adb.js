import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const ADB = join(ROOT, 'tools', 'platform-tools', 'adb.exe');

// Stock package names. Patched builds (ReVanced, Morphe, …) rename themselves,
// so these are only defaults — the real package is detected per device.
export const YTM_PKG = 'com.google.android.apps.youtube.music';
export const PLEX_PKG = 'com.plexapp.android';

/**
 * Classify a package by name shape rather than an exact list, so a renamed
 * fork is recognised without needing to know its vendor. Lower ranks win.
 */
export function rankPackage(pkg = '') {
  if (/youtube\.music$/i.test(pkg)) return 0;
  if (/plex/i.test(pkg)) return 1;
  if (/youtube$/i.test(pkg)) return 2;
  if (/spotify/i.test(pkg)) return 3;
  return 9;
}

/** Friendly name for the UI, independent of who built the APK. */
export function labelFor(pkg) {
  if (!pkg) return '';
  switch (rankPackage(pkg)) {
    case 0: return 'YouTube Music';
    case 1: return 'Plex';
    case 2: return 'YouTube';
    case 3: return 'Spotify';
    default: return pkg.split('.').at(-1);
  }
}

let serial = null;     // currently bound device, e.g. "192.168.1.50:37251"
let ytmPackage = null; // detected YouTube Music package on this device

export function currentSerial() {
  return serial;
}

async function adb(args, { timeout = 10000 } = {}) {
  const { stdout } = await execFileAsync(ADB, args, {
    timeout,
    maxBuffer: 8 * 1024 * 1024,
    windowsHide: true,
  });
  return stdout;
}

// Run a shell command on the bound device.
async function shell(cmd, opts) {
  if (!serial) throw new Error('No device connected');
  return adb(['-s', serial, 'shell', ...cmd], opts);
}

/**
 * Find wireless-debugging devices advertising over mDNS. Android 14+ randomises
 * the port on every reboot, so discovery is the only stable way in.
 */
export async function discover() {
  let out = '';
  try {
    out = await adb(['mdns', 'services'], { timeout: 6000 });
  } catch {
    return [];
  }
  const found = [];
  for (const line of out.split(/\r?\n/)) {
    // adb-<serial>  _adb-tls-connect._tcp  192.168.1.50:37251
    const m = line.match(/^(\S+)\s+(_adb-tls-connect\._tcp)\.?\s+(\S+:\d+)\s*$/);
    if (m) found.push({ name: m[1], address: m[3] });
  }
  return found;
}

/** Devices adb already knows about and considers usable. */
export async function listDevices() {
  const out = await adb(['devices']);
  return out
    .split(/\r?\n/)
    .slice(1)
    .map((l) => l.trim().split(/\s+/))
    .filter((p) => p.length >= 2 && p[0])
    .map(([id, state]) => ({ id, state }));
}

export async function pair(address, code) {
  const out = await adb(['pair', address, code], { timeout: 30000 });
  if (!/Successfully paired/i.test(out)) throw new Error(out.trim() || 'Pairing failed');
  return out.trim();
}

export async function connect(address) {
  const out = await adb(['connect', address], { timeout: 15000 });
  if (/failed|cannot|unable|refused/i.test(out)) throw new Error(out.trim());
  serial = address;
  ytmPackage = null;
  return out.trim();
}

export async function disconnect() {
  if (serial) await adb(['disconnect', serial]).catch(() => {});
  serial = null;
  ytmPackage = null;
}

/**
 * Attach to a device: reuse an existing authorised connection if there is one,
 * otherwise try every mDNS-advertised endpoint.
 */
export async function autoConnect() {
  const existing = (await listDevices()).find((d) => d.state === 'device');
  if (existing) {
    if (serial !== existing.id) ytmPackage = null;
    serial = existing.id;
    return { address: serial, via: 'existing' };
  }
  for (const svc of await discover()) {
    try {
      await connect(svc.address);
      return { address: svc.address, via: 'mdns' };
    } catch {
      /* try the next advertised endpoint */
    }
  }
  serial = null;
  return null;
}

/** True once the bound serial actually answers. */
export async function isAlive() {
  if (!serial) return false;
  try {
    const out = await adb(['-s', serial, 'get-state'], { timeout: 4000 });
    return out.trim() === 'device';
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------- transport

const DISPATCH = new Set([
  'play', 'pause', 'play-pause', 'stop', 'next', 'previous', 'fast-forward', 'rewind', 'mute',
]);

export async function dispatch(key) {
  if (!DISPATCH.has(key)) throw new Error(`Unsupported media key: ${key}`);
  await shell(['cmd', 'media_session', 'dispatch', key]);
}

/**
 * Find the YouTube Music package actually installed on this device. Patched
 * builds rename the package (app.morphe.…, app.revanced.…), so matching on
 * shape is the only thing that works across devices.
 */
export async function detectYtmPackage({ force = false } = {}) {
  if (ytmPackage && !force) return ytmPackage;
  let out = '';
  try {
    out = await shell(['pm', 'list', 'packages']);
  } catch (err) {
    // `pm list packages` can exit non-zero over a profile it cannot read while
    // still printing the packages we care about.
    out = err.stdout ?? '';
  }
  const pkgs = out.split(/\r?\n/)
    .map((l) => l.replace(/^package:/, '').trim())
    .filter(Boolean);

  ytmPackage = pkgs.find((p) => p === YTM_PKG)         // stock build wins
    ?? pkgs.find((p) => /youtube\.music$/i.test(p))    // then any fork
    ?? null;
  return ytmPackage;
}

export function ytmPackageName() {
  return ytmPackage;
}

async function openUrl(url) {
  const pkg = await detectYtmPackage();
  if (!pkg) {
    throw new Error('No YouTube Music app found on the device (checked every installed package).');
  }
  const out = await shell([
    'am', 'start', '-a', 'android.intent.action.VIEW', '-d', url, '-p', pkg,
  ]);
  // `am` reports resolution failures on stdout with a zero exit code.
  if (/Error:/i.test(out)) throw new Error(out.trim());
  return out;
}

/** Open a specific track in the YouTube Music app, which starts playback. */
export async function playVideo(videoId) {
  if (!/^[\w-]{6,20}$/.test(videoId)) throw new Error(`Bad videoId: ${videoId}`);
  return openUrl(`https://music.youtube.com/watch?v=${videoId}`);
}

/** Open a playlist or album by its browse id. */
export async function playList(playlistId) {
  if (!/^[\w-]{6,60}$/.test(playlistId)) throw new Error(`Bad playlistId: ${playlistId}`);
  return openUrl(`https://music.youtube.com/playlist?list=${playlistId}`);
}

// STREAM_MUSIC is stream 3. On a Bluetooth sink this drives absolute volume.
export async function setVolume(index) {
  await shell(['cmd', 'media_session', 'volume', '--stream', '3', '--set', String(index)]);
}

export async function adjustVolume(direction) {
  await shell(['cmd', 'media_session', 'volume', '--stream', '3', '--adj', direction]);
}

export async function getVolume() {
  const out = await shell(['cmd', 'media_session', 'volume', '--stream', '3', '--get']).catch(() => '');
  const cur = out.match(/volume is (\d+)/i);
  const max = out.match(/max (?:volume )?is (\d+)/i);
  if (!cur) return null;
  return { current: Number(cur[1]), max: max ? Number(max[1]) : 25 };
}

// ------------------------------------------------------------- now playing

const STATES = {
  0: 'none', 1: 'stopped', 2: 'paused', 3: 'playing',
  4: 'fast_forwarding', 5: 'rewinding', 6: 'buffering',
  7: 'error', 8: 'connecting', 9: 'skipping_next', 10: 'skipping_prev',
};

/**
 * Parse `dumpsys media_session`. The format drifts between Android releases, so
 * this stays deliberately loose: split into per-session blocks, then pull out
 * whatever each block happens to expose.
 */
export function parseSessions(dump) {
  const lines = dump.split(/\r?\n/);

  // Session header lines carry a free-text tag before the package
  // ("YouTube playerlib app.morphe…/YouTube playerlib/26 (userId=0)"), so they
  // are not reliably parseable. The "package=" line always is, and every field
  // we want follows it — so treat each "package=" as the start of a block.
  const starts = [];
  lines.forEach((line, i) => {
    const m = line.match(/^\s*package=([\w.]+)\s*$/);
    if (m) starts.push({ pkg: m[1], i });
  });

  const sessions = [];
  for (let n = 0; n < starts.length; n++) {
    const pkg = starts[n].pkg;
    const end = n + 1 < starts.length ? starts[n + 1].i : lines.length;
    const block = lines.slice(starts[n].i, end).join('\n');

    // Older builds print "state=3"; newer ones "state=PLAYING(3)".
    const stateM = block.match(/state=PlaybackState\s*\{\s*state=(?:\w+\()?(\d+)/);
    // Lazy, so it takes "position=" and not "buffered position=".
    const posM = block.match(/state=PlaybackState\s*\{[^}]*?[^\w]position=(-?\d+)/);
    // "updated" is the device uptime at which "position" was sampled, and
    // "speed" the rate since. Media apps are only required to publish a new
    // PlaybackState on change, so these are what make position meaningful.
    const updM = block.match(/state=PlaybackState\s*\{[^}]*?updated=(\d+)/);
    const spdM = block.match(/state=PlaybackState\s*\{[^}]*?speed=([\d.]+)/);
    // "metadata: size=N, description=Title, Artist, Album"
    const descM = block.match(/description=(.*?)(?:\r?\n|$)/);

    const descriptionRaw = descM ? descM[1].trim() : null;
    let title = null, artist = null, album = null;
    if (descriptionRaw) {
      const parts = descriptionRaw.split(',').map((s) => s.trim());
      // dumpsys joins title/artist/album with ", " and escapes nothing, so a
      // comma inside the title looks identical to a field separator. Artist and
      // album are the last two fields; treat everything before them as title.
      const [t, a, al] = parts.length > 3
        ? [parts.slice(0, -2).join(', '), parts.at(-2), parts.at(-1)]
        : parts;
      const clean = (v) => (v == null || v === 'null' || v === '' ? null : v);
      [title, artist, album] = [clean(t), clean(a), clean(al)];
    }

    sessions.push({
      package: pkg,
      state: STATES[Number(stateM?.[1])] ?? 'none',
      stateCode: Number(stateM?.[1] ?? 0),
      position: posM ? Number(posM[1]) : null,
      updated: updM ? Number(updM[1]) : null,
      speed: spdM ? Number(spdM[1]) : 1,
      title, artist, album, descriptionRaw,
      active: /active=true/.test(block),
    });
  }
  return sessions;
}

/**
 * The session we should show. Prefer an actually-playing known app, then any
 * known app, then whatever else is making noise.
 */
export function pickSession(sessions) {
  const score = (s) => {
    let n = 0;
    if (s.stateCode === 3) n += 100;                     // playing
    if (s.stateCode === 2 || s.stateCode === 6) n += 50; // paused / buffering
    n += Math.max(0, 20 - rankPackage(s.package) * 4);   // favour music apps
    if (s.active) n += 5;
    if (s.title) n += 3;
    return n;
  };
  return sessions
    .filter((s) => s.title || s.stateCode > 0)
    .sort((a, b) => score(b) - score(a))[0] ?? null;
}

const MARK = '__UPTIME__';

/**
 * Resolve a session's true playback position.
 *
 * `position` is only valid as of `updated` (device uptime in ms) and advances at
 * `speed` from there. Apps needn't republish while a track plays — YouTube Music
 * publishes once per track — so reading `position` alone can report 0 for a song
 * that is minutes in. Both numbers share the same clock, so they must be sampled
 * together; that's why the uptime comes back in the same round trip.
 */
export function resolvePosition(session, deviceUptimeMs) {
  if (!session || session.position == null) return null;
  if (session.updated == null || deviceUptimeMs == null) return session.position;
  const speed = session.stateCode === 3 ? (session.speed || 1) : 0;
  const elapsed = Math.max(0, deviceUptimeMs - session.updated);
  return Math.max(0, Math.round(session.position + elapsed * speed));
}

export async function nowPlaying() {
  // One round trip: the media sessions and the clock their positions refer to.
  const out = await shell(
    [`dumpsys media_session; echo ${MARK}; cat /proc/uptime`],
    { timeout: 8000 },
  );
  const [dump, uptimeRaw = ''] = out.split(MARK);
  const secs = Number.parseFloat(uptimeRaw.trim().split(/\s+/)[0]);
  const deviceUptimeMs = Number.isFinite(secs) ? Math.round(secs * 1000) : null;

  const sessions = parseSessions(dump);
  const current = pickSession(sessions);
  return {
    sessions,
    current: current && { ...current, position: resolvePosition(current, deviceUptimeMs) },
  };
}
