// Where the relay lives. For local development, open the site once with
// ?relay=http://127.0.0.1:8787; the override is remembered in this browser
// until ?relay= (empty) clears it.
const DEFAULT_RELAY = 'https://ytm-bridge-relay.ytmbridge.workers.dev';
const RELAY_OVERRIDE_KEY = 'ytm-bridge.relay';

export const APP_NAME = 'Auxparty';
export const APK_URL = 'https://github.com/Rabbitwingz/Auxparty/releases/download/android-latest/music-remote.apk';
export const SOURCE_URL = 'https://github.com/Rabbitwingz/Auxparty';

function resolveRelay(): string {
  const params = new URLSearchParams(location.search);
  if (params.has('relay')) {
    const value = params.get('relay');
    try {
      if (value) localStorage.setItem(RELAY_OVERRIDE_KEY, value);
      else localStorage.removeItem(RELAY_OVERRIDE_KEY);
    } catch {
      /* storage unavailable */
    }
    params.delete('relay');
    const rest = params.toString();
    // Keep the fragment: it may carry a party link.
    history.replaceState(null, '', location.pathname + (rest ? `?${rest}` : '') + location.hash);
  }
  let override: string | null = null;
  try {
    override = localStorage.getItem(RELAY_OVERRIDE_KEY);
  } catch {
    /* ignore */
  }
  return (override || DEFAULT_RELAY).replace(/\/+$/, '');
}

export const RELAY_URL = resolveRelay();
