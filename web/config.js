// Where the relay lives. Replaced with the real Worker URL at deploy time.
// For local development, open the site with ?relay=http://127.0.0.1:8787 once;
// the override is remembered in this browser until ?relay= (empty) clears it.
window.YTM_BRIDGE_CONFIG = {
  relayUrl: 'https://ytm-bridge-relay.example.workers.dev',
};
