// Remote screen: now playing on the host's phone, and search to pick what's next.
// During a party it also shows the queue, and picks are requests instead of plays.
import { APK_URL, SOURCE_URL } from '../config';
import { clearPairing } from '../pairing';
import { clearGuest, partyLinkUrl } from '../party';
import {
  explain, Relay,
  type Artwork, type Ended, type Link, type PartyInfo, type PhoneState, type Queue, type QueueAddResult, type SearchResult,
} from '../relay';
import { setThemeMode, themeFromArtwork, themeMode, type ThemeMode } from '../theme';
import { MorphingArtwork } from '../ui/artwork';
import { h, icon } from '../ui/dom';
import { confirmDialog, loadingIndicator, snackbar } from '../ui/feedback';
import { Slider } from '../ui/slider';
import { WavyProgress } from '../ui/wavy';
import { brand } from './pair';
import { QueuePanel } from './queue';

/**
 * `owner` is a browser linked by the host with a code: full control. `guest` joined a
 * party through its link: sees everything, but can only search and request songs.
 */
export type Role = 'owner' | 'guest';

export interface RemoteOptions {
  relay: Relay;
  role: Role;
  /** This browser can't connect any more (unlinked, removed, party over), or left. */
  onEnded: (reason: Ended | 'left', message: string) => void;
}

export function renderRemote(root: HTMLElement, { relay, role, onEnded }: RemoteOptions): void {
  const view = {
    link: 'connecting' as Link,
    online: false,
    state: null as PhoneState | null,
    artwork: null as Artwork | null,
    receivedAt: 0,
    queue: null as Queue | null,
    party: { active: false } as PartyInfo,
  };
  const canControl = role === 'owner';
  const partyOn = () => view.party.active;

  // ------------------------------------------------------------- top bar
  const chip = h('span', { class: 'chip label-large', role: 'status' });
  const menuButton = h('button', { class: 'icon-btn state', type: 'button', 'aria-label': 'Menu', 'aria-haspopup': 'true' }, icon('more_vert'));
  const menu = buildMenu(menuButton, role, {
    leave: async () => {
      const ok = await confirmDialog(canControl
        ? { title: 'Unlink this browser?', body: "You'll need a new code from the host to control the music from here again.", confirm: 'Unlink' }
        : { title: 'Leave the party?', body: 'Your songs stay in the queue. Open the party link again to rejoin.', confirm: 'Leave' });
      if (!ok) return;
      relay.disconnect();
      if (canControl) clearPairing();
      else clearGuest();
      onEnded('left', canControl ? 'This browser is unlinked.' : 'You left the party.');
    },
    copyLink: async () => {
      const secret = view.party.secret;
      if (!view.party.active || !secret) return snackbar('Start a party in the Auxparty app first.');
      const url = partyLinkUrl(relay.credentials.deviceId, secret);
      try {
        await navigator.clipboard.writeText(url);
        snackbar('Party link copied. Anyone with it can add songs.');
      } catch {
        snackbar(url);
      }
    },
  });

  // --------------------------------------------------------------- player
  const artwork = new MorphingArtwork('player-art');
  const source = h('div', { class: 'label-large-emphasized source' });
  const title = h('h1', { class: 'headline-medium-emphasized title' });
  const artist = h('div', { class: 'title-medium artist' });
  const requested = h('div', { class: 'requested label-large', hidden: true });
  const elapsed = h('span', { class: 'label-medium' });
  const total = h('span', { class: 'label-medium' });

  const progress = new WavyProgress({
    label: canControl ? 'Seek' : 'Song progress',
    onSeek: canControl ? (fraction) => {
      const s = view.state;
      if (!s?.durationMs || !view.online) return;
      const positionMs = Math.round(fraction * s.durationMs);
      patchState({ positionMs, positionAt: Date.now() });
      relay.command('seek', { positionMs }).catch((e) => snackbar(explain(e)));
    } : undefined,
  });

  const control = (name: 'skip_previous' | 'skip_next', label: string, action: string, cls: string) => {
    const b = h('button', { class: `icon-btn tonal size-l ${cls} state`, type: 'button', 'aria-label': label }, icon(name));
    b.addEventListener('click', () => relay.command(action).catch((e) => snackbar(explain(e))));
    return b;
  };
  const prev = control('skip_previous', 'Previous', 'previous', 'narrow');
  const next = control('skip_next', 'Next', 'next', 'narrow');
  const playButton = h('button', { class: 'icon-btn filled size-xl wide state', type: 'button' });
  playButton.addEventListener('click', togglePlay);

  const volume = new Slider({
    label: 'Volume',
    min: 0,
    max: 15,
    value: 0,
    onChange: (value) => relay.command('volume', { value }).catch((e) => snackbar(explain(e))),
  });

  const offlineBanner = h('div', { class: 'banner body-medium', role: 'status', hidden: true },
    icon('cloud_off'),
    h('span', {}, "The host's phone is offline. Open Auxparty on it and check it has a connection."));

  const transport = h('div', { class: 'transport' }, prev, playButton, next);
  const volumeRow = h('div', { class: 'volume' }, icon('volume_down'), volume.el, icon('volume_up'));
  const player = h('section', { class: 'player', 'aria-label': 'Now playing' },
    artwork.el,
    h('div', { class: 'meta' }, source, title, artist, requested),
    h('div', { class: 'progress' }, progress.el, h('div', { class: 'times' }, elapsed, total)),
    canControl ? transport : null,
    canControl ? volumeRow : null,
  );

  // ------------------------------------------------------ mini player
  const miniArt = h('img', { class: 'mini-art', alt: '' });
  const miniTitle = h('div', { class: 'title-small mini-title' });
  const miniArtist = h('div', { class: 'body-small mini-artist' });
  const miniPlay = h('button', { class: 'icon-btn filled state', type: 'button' });
  miniPlay.addEventListener('click', togglePlay);
  const miniBar = h('div', { class: 'mini-progress' });
  const mini = h('div', { class: 'mini-player', hidden: true },
    miniBar, miniArt, h('div', { class: 'mini-text' }, miniTitle, miniArtist), canControl ? miniPlay : null);
  mini.addEventListener('click', (e) => {
    if (e.target !== miniPlay && !miniPlay.contains(e.target as Node)) player.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });

  // ---------------------------------------------------------------- browse
  const query = h('input', { type: 'search', placeholder: 'Search YouTube Music', 'aria-label': 'Search YouTube Music', enterkeyhint: 'search' });
  const clear = h('button', { class: 'icon-btn state', type: 'button', 'aria-label': 'Clear search', hidden: true }, icon('close'));
  const results = h('div', { class: 'results', 'aria-live': 'polite' });
  const browseHeading = h('h2', { class: 'headline-small-emphasized' }, 'Pick a song');
  const queuePanel = new QueuePanel(relay, canControl);
  const browse = h('section', { class: 'browse', 'aria-label': 'Search' },
    queuePanel.el,
    browseHeading,
    h('div', { class: 'search-bar' }, icon('search'), query, clear),
    results,
  );

  let searchTimer = 0;
  let searchSeq = 0;
  query.addEventListener('input', () => {
    clear.hidden = !query.value;
    window.clearTimeout(searchTimer);
    searchTimer = window.setTimeout(search, 400);
  });
  query.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      window.clearTimeout(searchTimer);
      void search();
    }
  });
  clear.addEventListener('click', () => {
    query.value = '';
    clear.hidden = true;
    searchSeq++;
    showSearchHint();
    query.focus();
  });

  async function search() {
    const q = query.value.trim();
    if (!q) return showSearchHint();
    const seq = ++searchSeq;
    results.replaceChildren(h('div', { class: 'results-status' }, loadingIndicator(48, 'Searching'), h('span', { class: 'body-medium' }, "Searching on the host's phone…")));
    try {
      const items = await relay.command<SearchResult[]>('search', { query: q }, 25_000);
      if (seq !== searchSeq) return;
      results.replaceChildren(
        items.length
          ? h('div', { class: 'list' }, h('div', { class: 'label-large list-label' }, 'Songs'), ...items.map(resultRow))
          : emptyResults(`Nothing found for “${q}”.`),
      );
    } catch (e) {
      if (seq !== searchSeq) return;
      results.replaceChildren(emptyResults(explain(e)));
    }
  }

  function resultRow(r: SearchResult): HTMLElement {
    const img = h('img', { alt: '', loading: 'lazy', referrerpolicy: 'no-referrer' });
    if (r.thumbnail) img.src = r.thumbnail.replace(/=w\d+-h\d+/, '=w120-h120');
    const party = partyOn();
    const row = h('button', { class: 'list-item state', type: 'button' },
      img,
      h('div', { class: 'text' },
        h('div', { class: 'headline body-large' }, r.title),
        h('div', { class: 'supporting body-medium' }, [r.artist, r.album].filter(Boolean).join(' · '))),
      party ? icon('playlist_add', 'Add to queue') : h('span', { class: 'trailing label-medium' }, r.duration ?? ''),
    );

    const busyWhile = async (el: HTMLElement, run: () => Promise<void>) => {
      el.classList.add('busy');
      try {
        await run();
      } catch (e) {
        snackbar(explain(e));
      } finally {
        el.classList.remove('busy');
      }
    };

    // Title and artist let the phone verify the right track started, and fall back to
    // a search if YouTube Music ignores the id. The first pick after an app update may
    // try several methods, hence the longer timeout.
    const playNow = () => busyWhile(row, async () => {
      await relay.command('playVideo', { videoId: r.videoId, title: r.title, artist: r.artist }, 30_000);
      snackbar(`Playing “${r.title}”`);
    });

    const request = () => busyWhile(row, async () => {
      const added = await relay.command<QueueAddResult>('queue.add', {
        videoId: r.videoId, title: r.title, artist: r.artist, album: r.album, duration: r.duration, thumbnail: r.thumbnail,
      }, 30_000);
      snackbar(added.position === 0
        ? (added.duplicate ? `“${r.title}” is playing now` : `Playing “${r.title}” now`)
        : added.duplicate
          ? `“${r.title}” is already coming up (#${added.position}). Added your name.`
          : `Added “${r.title}” · #${added.position} in line`);
    });

    row.addEventListener('click', () => void (party ? request() : playNow()));
    if (!party || !canControl) return row;

    // Remotes can still skip the line.
    const now = h('button', { class: 'icon-btn state', type: 'button', 'aria-label': `Play “${r.title}” now`, title: 'Play now' }, icon('play_arrow'));
    now.addEventListener('click', () => void playNow());
    return h('div', { class: 'result-row' }, row, now);
  }

  function emptyResults(message: string): HTMLElement {
    return h('div', { class: 'results-status' }, h('span', { class: 'body-medium' }, message));
  }

  function showSearchHint() {
    results.replaceChildren(h('div', { class: 'search-hint' },
      h('span', { class: 'icon-tile large' }, icon(partyOn() ? 'queue_music' : 'graphic_eq')),
      h('p', { class: 'body-large' }, partyOn()
        ? 'Search YouTube Music and add songs to the queue. They play in order on the host’s speakers.'
        : "Search for anything on YouTube Music. It plays on the host's phone, through their speakers."),
    ));
  }

  /** Party on or off changes what a pick does, so redraw the hint and any results. */
  let renderedParty: boolean | null = null;
  function syncPartyMode() {
    const on = partyOn();
    browseHeading.textContent = on ? 'Request a song' : 'Pick a song';
    query.placeholder = on ? 'Search for a song to add' : 'Search YouTube Music';
    if (renderedParty === on) return;
    const first = renderedParty === null;
    renderedParty = on;
    if (first) return;
    if (query.value.trim()) void search();
    else showSearchHint();
  }

  // ---------------------------------------------------------------- layout
  root.replaceChildren(
    h('header', { class: 'top-bar' }, brand(), chip, menuButton),
    h('main', { class: 'remote' }, h('div', { class: 'player-column' }, offlineBanner, player), browse),
    mini,
    menu,
  );
  showSearchHint();

  // Phones: when the player scrolls away, a mini player takes over at the bottom.
  const compact = matchMedia('(max-width: 899px)');
  let playerMostlyHidden = false;
  // isIntersecting stays true while any sliver is visible, so compare the ratio,
  // and observe at 0 too so fully scrolling past still reports.
  new IntersectionObserver(([entry]) => {
    playerMostlyHidden = entry.intersectionRatio < 0.15;
    mini.hidden = !compact.matches || !playerMostlyHidden || !view.state?.title;
  }, { threshold: [0, 0.15] }).observe(player);

  // ------------------------------------------------------------ behaviour
  function togglePlay() {
    const s = view.state;
    if (!s) return;
    // Flip straight away; the phone's next state confirms or corrects it.
    patchState({ playback: s.playback === 'playing' ? 'paused' : 'playing', positionMs: currentPosition(), positionAt: Date.now() });
    relay.command('playPause').catch((e) => snackbar(explain(e)));
  }

  function patchState(patch: Partial<PhoneState>) {
    if (!view.state) return;
    view.state = { ...view.state, ...patch };
    view.receivedAt = Date.now();
    render();
  }

  /** Extrapolates between the phone's (sparse) updates; freezes while it's offline. */
  function currentPosition(): number | null {
    const s = view.state;
    if (s?.positionMs == null) return null;
    let pos = s.positionMs;
    if (s.playback === 'playing' && view.online) {
      // positionAt is the phone's clock. It can legitimately be long ago (the relay
      // replays its cached state on connect), but never meaningfully in the future;
      // if it is, the clocks disagree, so count from when the state arrived instead.
      const since = s.positionAt && s.positionAt <= Date.now() + 5_000 ? s.positionAt : view.receivedAt;
      pos += Date.now() - since;
    }
    return s.durationMs ? Math.min(Math.max(pos, 0), s.durationMs) : Math.max(pos, 0);
  }

  const fmt = (ms: number) => `${Math.floor(ms / 60_000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;

  function tick() {
    const s = view.state;
    const pos = currentPosition();
    elapsed.textContent = pos == null ? '0:00' : fmt(pos);
    total.textContent = s?.durationMs ? fmt(s.durationMs) : '--:--';
    const fraction = pos != null && s?.durationMs ? pos / s.durationMs : 0;
    progress.setProgress(fraction);
    miniBar.style.transform = `scaleX(${fraction})`;
  }

  function render() {
    // Connection chip
    const ready = view.link === 'ready';
    chip.className = `chip label-large ${ready ? (view.online ? '' : 'error') : 'pending'}`;
    chip.replaceChildren(
      ready ? icon(view.online ? 'cloud_done' : 'cloud_off') : loadingIndicator(18, 'Connecting'),
      ready ? (view.online ? 'Phone connected' : 'Phone offline') : view.link === 'connecting' ? 'Connecting…' : 'Reconnecting…',
    );
    offlineBanner.hidden = !ready || view.online;

    const s = view.state;
    const hasTrack = Boolean(s?.title);
    const playing = hasTrack && s!.playback === 'playing' && view.online;

    source.textContent = hasTrack ? (s!.source ?? '').toUpperCase() : '';
    title.textContent = hasTrack ? s!.title! : 'Nothing playing';
    artist.textContent = hasTrack
      ? [s!.artist, s!.album].filter(Boolean).join(' · ')
      : partyOn() ? 'Request a song below to get the party going.' : 'Pick a song below to start the music.';

    // Who asked for this one, while a requested song is what's actually playing.
    const current = partyOn() ? view.queue?.current ?? null : null;
    const line = current && hasTrack && sameSong(current.title, s!.title!) ? queuePanel.requesterLine(current) : null;
    requested.hidden = !line;
    requested.replaceChildren(...(line ? [icon('person'), line] : []));

    syncPartyMode();
    queuePanel.render(view.queue, partyOn());
    miniTitle.textContent = s?.title ?? '';
    miniArtist.textContent = s?.artist ?? '';

    const art = view.artwork;
    const artMatches = art?.data && (!s?.artworkKey || art.key === s.artworkKey);
    const src = hasTrack && artMatches ? `data:${art!.mime || 'image/jpeg'};base64,${art!.data}` : null;
    artwork.setImage(src, s?.title ? `Artwork for ${s.title}` : '');
    artwork.setPlaying(playing);
    themeFromArtwork(src ? art!.key : null, src);
    if (src) miniArt.src = src;
    miniArt.hidden = !src;

    progress.setPlaying(playing);
    playButton.classList.toggle('square', playing);
    playButton.replaceChildren(icon(playing ? 'pause' : 'play_arrow'));
    playButton.setAttribute('aria-label', playing ? 'Pause' : 'Play');
    miniPlay.replaceChildren(icon(playing ? 'pause' : 'play_arrow'));
    miniPlay.setAttribute('aria-label', playing ? 'Pause' : 'Play');

    const controllable = view.online && hasTrack;
    for (const b of [prev, next, playButton, miniPlay]) b.disabled = !controllable;
    volume.el.classList.toggle('disabled', !view.online);
    if (s?.maxVolume) {
      volume.setMax(s.maxVolume);
      volume.set(s.volume ?? 0);
    }
    // Also re-evaluated when a track starts while the player is scrolled away.
    mini.hidden = !hasTrack || !compact.matches || !playerMostlyHidden;
    tick();
  }

  window.setInterval(tick, 500);

  // ------------------------------------------------------------------ wire
  relay.on('link', (link) => {
    view.link = link;
    render();
  });
  relay.on('presence', (online) => {
    view.online = online;
    render();
  });
  relay.on('state', (state) => {
    view.state = state;
    view.receivedAt = Date.now();
    render();
  });
  relay.on('artwork', (art) => {
    view.artwork = art;
    render();
  });
  relay.on('queue', (queue) => {
    view.queue = queue;
    render();
  });
  relay.on('party', (party) => {
    view.party = party;
    render();
  });
  relay.on('unlinked', (reason) => {
    if (canControl) {
      clearPairing();
      onEnded(reason, reason === 'revoked'
        ? 'The host removed this browser. Ask for a new code to join again.'
        : 'This browser is no longer linked. Enter a new code to join.');
    } else {
      clearGuest();
      onEnded(reason, reason === 'revoked' ? 'The host removed you from the party.' : 'The party has ended.');
    }
  });

  render();
  relay.connect();
}

/** Loose title match: YouTube Music's session title vs. the search result's. */
function sameSong(a: string, b: string): boolean {
  const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
  return norm(a) === norm(b);
}

/** Overflow menu: theme, party link, unlink or leave, links. Uses the Popover API for light dismiss. */
function buildMenu(anchor: HTMLButtonElement, role: Role, actions: { leave: () => void; copyLink: () => void }): HTMLElement {
  const themeButtons: [ThemeMode, string][] = [['system', 'System'], ['light', 'Light'], ['dark', 'Dark']];
  const segmented = h('div', { class: 'segmented', role: 'group', 'aria-label': 'Theme' });
  const syncTheme = () => {
    for (const b of segmented.querySelectorAll('button')) b.setAttribute('aria-pressed', String(b.dataset.mode === themeMode()));
  };
  for (const [mode, label] of themeButtons) {
    const b = h('button', { type: 'button', class: 'state', 'data-mode': mode }, label);
    b.addEventListener('click', () => {
      setThemeMode(mode);
      syncTheme();
    });
    segmented.append(b);
  }
  syncTheme();

  const owner = role === 'owner';
  const leave = h('button', { class: 'menu-item state', type: 'button' },
    icon(owner ? 'link_off' : 'logout'), owner ? 'Unlink this browser' : 'Leave the party');
  const copyLink = h('button', { class: 'menu-item state', type: 'button' }, icon('link'), 'Copy party link');
  const menu = h('div', { class: 'menu', id: 'app-menu', popover: 'auto' },
    h('div', { class: 'menu-section label-large' }, 'Theme'),
    segmented,
    h('hr', {}),
    owner ? copyLink : null,
    leave,
    h('a', { class: 'menu-item state', href: APK_URL, rel: 'noopener' }, icon('smartphone'), 'Host with the Android app'),
    h('a', { class: 'menu-item state', href: SOURCE_URL, rel: 'noopener', target: '_blank' }, icon('open_in_new'), 'Source code'),
  );
  leave.addEventListener('click', () => {
    menu.hidePopover();
    actions.leave();
  });
  copyLink.addEventListener('click', () => {
    menu.hidePopover();
    actions.copyLink();
  });

  anchor.setAttribute('popovertarget', 'app-menu');
  menu.addEventListener('toggle', (e) => {
    const open = (e as ToggleEvent).newState === 'open';
    anchor.setAttribute('aria-expanded', String(open));
    if (!open) return;
    const r = anchor.getBoundingClientRect();
    menu.style.top = `${r.bottom + 4}px`;
    menu.style.right = `${Math.max(8, innerWidth - r.right)}px`;
  });
  return menu;
}
