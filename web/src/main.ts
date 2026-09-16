// Auxparty web: pick the music playing on a friend's phone.
import './ui/base.css';
import './app.css';
import { loadPairing, type Pairing } from './pairing';
import { Relay } from './relay';
import { renderPair } from './screens/pair';
import { renderRemote } from './screens/remote';
import { initTheme, themeFromArtwork } from './theme';

initTheme();
const root = document.getElementById('app')!;
let relay: Relay | null = null;

function showPair(opts: { code?: string; notice?: string } = {}) {
  relay?.disconnect();
  relay = null;
  themeFromArtwork(null, null);
  document.title = 'Join the party · Auxparty';
  renderPair(root, { ...opts, onPaired: showRemote });
}

function showRemote(pairing: Pairing) {
  relay?.disconnect();
  relay = new Relay(pairing);
  document.title = 'Auxparty';
  renderRemote(root, {
    relay,
    role: 'owner',
    onUnlinked: (notice) => showPair({ notice }),
  });
}

// An invite link (?code=K7QM-3XPD) always starts pairing, even if this browser
// is already linked: the person clearly wants to join that party.
const params = new URLSearchParams(location.search);
const code = params.get('code');
if (code) {
  params.delete('code');
  const rest = params.toString();
  history.replaceState(null, '', location.pathname + (rest ? `?${rest}` : ''));
  showPair({ code });
} else {
  const pairing = loadPairing();
  if (pairing) showRemote(pairing);
  else showPair();
}
