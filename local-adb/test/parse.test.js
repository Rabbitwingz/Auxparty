// Run: node --test test/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseSessions, pickSession, rankPackage, labelFor, resolvePosition, YTM_PKG, PLEX_PKG } from '../server/adb.js';

// Shaped after real `adb shell dumpsys media_session` output.
const DUMP = `
MEDIA SESSION SERVICE (dumpsys media_session)

Global priority session is null

User Records:
Record for user=UserHandle{0}
  Volume key long-press listener: null
  Media button session is com.google.android.apps.youtube.music/MediaSessionCompat (userId=0)
  Sessions Stack - have 2 sessions:
    com.google.android.apps.youtube.music/MediaSessionCompat (userId=0)
      ownerPid=12345, ownerUid=10234, userId=0
      package=com.google.android.apps.youtube.music
      launchIntent=null
      active=true
      flags=3
      rating type=0
      controllers: 3
      state=PlaybackState {state=3, position=125430, buffered position=338000, speed=1.0, updated=98765, actions=822, custom actions=[], active item id=-1, error=null}
      audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x0
      volumeType=1, controlType=2, max=0, current=0
      metadata: size=9, description=Let It Happen, Tame Impala, Currents
      queueTitle=null, queue size=0, extras=null
    com.plexapp.android/PlexMediaSession (userId=0)
      ownerPid=23456, ownerUid=10555, userId=0
      package=com.plexapp.android
      active=false
      flags=3
      state=PlaybackState {state=2, position=4000, buffered position=0, speed=0.0, updated=555, actions=0, custom actions=[], active item id=-1, error=null}
      metadata: size=5, description=Some Track, Some Artist, null
      queueTitle=null, queue size=0, extras=null
`;

test('parses every session block', () => {
  const s = parseSessions(DUMP);
  const pkgs = s.map((x) => x.package);
  assert.ok(pkgs.includes(YTM_PKG), 'found YT Music session');
  assert.ok(pkgs.includes(PLEX_PKG), 'found Plex session');
});

test('extracts playback state, position and metadata', () => {
  const ytm = parseSessions(DUMP).find((x) => x.package === YTM_PKG);
  assert.equal(ytm.state, 'playing');
  assert.equal(ytm.stateCode, 3);
  assert.equal(ytm.position, 125430);
  assert.equal(ytm.title, 'Let It Happen');
  assert.equal(ytm.artist, 'Tame Impala');
  assert.equal(ytm.album, 'Currents');
  assert.equal(ytm.active, true);
});

test('treats a literal "null" field as absent', () => {
  const plex = parseSessions(DUMP).find((x) => x.package === PLEX_PKG);
  assert.equal(plex.state, 'paused');
  assert.equal(plex.album, null);
  assert.equal(plex.title, 'Some Track');
});

test('keeps the raw description for metadata lookup', () => {
  const ytm = parseSessions(DUMP).find((x) => x.package === YTM_PKG);
  assert.equal(ytm.descriptionRaw, 'Let It Happen, Tame Impala, Currents');
});

test('prefers the playing session over a paused one', () => {
  const picked = pickSession(parseSessions(DUMP));
  assert.equal(picked.package, YTM_PKG);
});

test('a comma in the title does not swallow the artist', () => {
  const dump = DUMP.replace(
    'description=Let It Happen, Tame Impala, Currents',
    'description=Hello, Goodbye, The Beatles, Magical Mystery Tour',
  );
  const ytm = parseSessions(dump).find((x) => x.package === YTM_PKG);
  // Album and artist are the last two fields; everything before is the title.
  assert.equal(ytm.title, 'Hello, Goodbye');
  assert.equal(ytm.artist, 'The Beatles');
  assert.equal(ytm.album, 'Magical Mystery Tour');
});

test('survives a session with no metadata at all', () => {
  const dump = `
    com.example.podcast/Session (userId=0)
      package=com.example.podcast
      active=true
      state=PlaybackState {state=0, position=0, buffered position=0, speed=0.0}
`;
  const s = parseSessions(dump);
  assert.equal(s.length, 1);
  assert.equal(s[0].title, null);
  assert.equal(s[0].state, 'none');
});

test('ignores the service header block', () => {
  assert.ok(!parseSessions(DUMP).some((s) => /MEDIA|Global|Record/i.test(s.package)));
});

test('recognises patched YouTube Music forks by package shape', () => {
  // Morphe / ReVanced builds rename the package; rank must still be 0.
  assert.equal(rankPackage('app.morphe.android.apps.youtube.music'), 0);
  assert.equal(rankPackage('app.revanced.android.apps.youtube.music'), 0);
  assert.equal(rankPackage(YTM_PKG), 0);
  assert.equal(rankPackage('app.morphe.android.youtube'), 2); // plain YouTube
  assert.equal(rankPackage(PLEX_PKG), 1);
  assert.equal(rankPackage('com.example.notes'), 9);
});

test('labels a patched build as YouTube Music', () => {
  assert.equal(labelFor('app.morphe.android.apps.youtube.music'), 'YouTube Music');
  assert.equal(labelFor('app.morphe.android.youtube'), 'YouTube');
  assert.equal(labelFor(PLEX_PKG), 'Plex');
  assert.equal(labelFor(null), '');
});

test('picks a patched YT Music session over plain YouTube', () => {
  const dump = `
    app.morphe.android.youtube/YouTube playerlib/13 (userId=0)
      package=app.morphe.android.youtube
      active=true
      state=PlaybackState {state=2, position=10, buffered position=0, speed=0.0}
      metadata: size=3, description=Some Video, Some Channel, null
    app.morphe.android.apps.youtube.music/MediaSessionCompat (userId=0)
      package=app.morphe.android.apps.youtube.music
      active=true
      state=PlaybackState {state=3, position=5000, buffered position=0, speed=1.0}
      metadata: size=9, description=Inner Light, Elderbrook, Inner Light
`;
  const picked = pickSession(parseSessions(dump));
  assert.equal(picked.package, 'app.morphe.android.apps.youtube.music');
  assert.equal(picked.title, 'Inner Light');
});

// Verbatim shape of real Android 15 output: a free-text tag before the package
// in the header, "PLAYING(3)" instead of "3", and commas inside custom actions.
const REAL_DUMP = `
Global priority session is com.android.server.telecom/HeadsetMediaButton/1 (userId=0)
  HeadsetMediaButton com.android.server.telecom/HeadsetMediaButton/1 (userId=0)
    ownerPid=2386, ownerUid=1000, userId=0
    package=com.android.server.telecom
    active=false
  Media button session is app.morphe.android.apps.youtube.music/YouTube playerlib/26 (userId=0)
  Sessions Stack - have 2 sessions:
    YouTube playerlib app.morphe.android.apps.youtube.music/YouTube playerlib/26 (userId=0)
      ownerPid=21651, ownerUid=10534, userId=0
      package=app.morphe.android.apps.youtube.music
      active=true
      state=PlaybackState {state=PLAYING(3), position=4200, buffered position=0, speed=1.0, updated=65762817, actions=2600887, custom actions=[Action:mName='Like, mIcon=2131233468, mExtras=null, Action:mName='Shuffle off, mIcon=2131232849, mExtras=null], active item id=1, error=null}
      metadata: size=9, description=Numb, Elderbrook, Why Do We Shake In The Cold?
    YouTube playerlib app.morphe.android.youtube/YouTube playerlib/13 (userId=0)
      ownerPid=11783, ownerUid=10533, userId=0
      package=app.morphe.android.youtube
      active=true
      state=PlaybackState {state=STOPPED(1), position=158847, buffered position=0, speed=1.0, updated=58898706, actions=8615, custom actions=[], active item id=-1, error=null}
      metadata: size=7, description=This is What Reinvesting Into a Proven IP Looks Like., Aztecross, Aztecross
`;

test('handles a tag-prefixed session header', () => {
  // The package is not at the start of the header line, so blocks must be
  // delimited by the "package=" line instead.
  const s = parseSessions(REAL_DUMP);
  assert.equal(s.length, 3);
  assert.deepEqual(s.map((x) => x.package), [
    'com.android.server.telecom',
    'app.morphe.android.apps.youtube.music',
    'app.morphe.android.youtube',
  ]);
});

test('reads the named "PLAYING(3)" state form', () => {
  const s = parseSessions(REAL_DUMP);
  assert.equal(s[1].state, 'playing');
  assert.equal(s[2].state, 'stopped');
});

test('takes position, not buffered position', () => {
  const s = parseSessions(REAL_DUMP);
  assert.equal(s[1].position, 4200);
  assert.equal(s[2].position, 158847);
});

test('commas inside custom actions do not corrupt metadata', () => {
  const ytm = parseSessions(REAL_DUMP)[1];
  assert.equal(ytm.title, 'Numb');
  assert.equal(ytm.artist, 'Elderbrook');
  assert.equal(ytm.album, 'Why Do We Shake In The Cold?');
});

test('does not attribute the music session to telecom', () => {
  const picked = pickSession(parseSessions(REAL_DUMP));
  assert.equal(picked.package, 'app.morphe.android.apps.youtube.music');
  assert.equal(picked.title, 'Numb');
});

test('resolvePosition advances a stale position using updated + speed', () => {
  const ytm = parseSessions(REAL_DUMP)[1]; // PLAYING, position=4200, updated=65762817
  assert.equal(ytm.updated, 65762817);
  assert.equal(ytm.speed, 1);
  // 30s of device uptime later, the track is 30s further in.
  assert.equal(resolvePosition(ytm, 65762817 + 30000), 34200);
});

test('resolvePosition does not advance a stopped session', () => {
  const yt = parseSessions(REAL_DUMP)[2]; // STOPPED, position=158847
  assert.equal(resolvePosition(yt, yt.updated + 60000), 158847);
});

test('resolvePosition falls back when the clock is unavailable', () => {
  const ytm = parseSessions(REAL_DUMP)[1];
  assert.equal(resolvePosition(ytm, null), 4200);
  assert.equal(resolvePosition(null, 123), null);
});
