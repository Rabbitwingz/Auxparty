// Auxparty web: pick the music playing on a friend's phone.
import './ui/base.css';
import './app.css';
import { loadPairing, type Pairing } from './pairing';
import { loadGuest, parsePartyLink, type GuestSession, type PartyLink } from './party';
import { Relay } from './relay';
import { renderJoin } from './screens/join';
import { renderPair } from './screens/pair';
import { renderRemote } from './screens/remote';
import { initTheme, themeFromArtwork } from './theme';

initTheme();
const root = document.getElementById('app')!;
let relay: Relay | null = null;

function reset(title: string) {
  relay?.disconnect();
  relay = null;
  themeFromArtwork(null, null);
  document.title = title;
}

function showPair(opts: { code?: string; notice?: string } = {}) {
  reset('Join the party · Auxparty');
  renderPair(root, { ...opts, onPaired: showRemote });
}

function showRemote(pairing: Pairing) {
  reset('Auxparty');
  relay = new Relay(pairing);
  renderRemote(root, { relay, role: 'owner', onEnded: (_reason, notice) => showPair({ notice }) });
}

function showJoin(link: PartyLink | null, notice?: string) {
  reset(link ? 'You’re invited · Auxparty' : 'Auxparty');
  renderJoin(root, { link, notice, onJoined: (session) => showGuest(session, link) });
}

/** [link] is the one this guest arrived with, to rejoin if their seat stops working. */
function showGuest(session: GuestSession, link: PartyLink | null) {
  reset('Party · Auxparty');
  relay = new Relay(session);
  renderRemote(root, {
    relay,
    role: 'guest',
    onEnded: (reason, notice) => {
      // Left: they can rejoin with the same link. A seat that stopped working while a
      // link is at hand (e.g. an old seat, opened with a fresh link): offer that link.
      // Party over or removed: the link is dead for them, so just say what happened.
      if (reason === 'left' || (reason === 'invalid' && link)) showJoin(link, reason === 'left' ? notice : undefined);
      else showJoin(null, notice);
    },
  });
}

function route() {
  // A party link (#party=…) joins that party, unless this browser already controls
  // that phone or already has a seat at it.
  const link = parsePartyLink(location.hash);
  if (link) {
    history.replaceState(null, '', location.pathname + location.search);
    const pairing = loadPairing();
    if (pairing?.deviceId === link.deviceId) return showRemote(pairing);
    const guest = loadGuest();
    if (guest?.deviceId === link.deviceId) return showGuest(guest, link);
    return showJoin(link);
  }

  // An invite code link (?code=K7QM-3XPD) always starts pairing, even if this browser
  // is already linked: the person clearly wants to join that phone.
  const params = new URLSearchParams(location.search);
  const code = params.get('code');
  if (code) {
    params.delete('code');
    const rest = params.toString();
    history.replaceState(null, '', location.pathname + (rest ? `?${rest}` : ''));
    return showPair({ code });
  }

  // A browser can be both a linked remote and a party guest; open the newer one.
  const pairing = loadPairing();
  const guest = loadGuest();
  if (guest && (!pairing || guest.joinedAt > (pairing.pairedAt ?? 0))) return showGuest(guest, null);
  if (pairing) return showRemote(pairing);
  showPair();
}

route();
// Opening another party link in the same tab.
window.addEventListener('hashchange', () => {
  if (parsePartyLink(location.hash)) route();
});
