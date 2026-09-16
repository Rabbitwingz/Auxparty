// Party guests: joining through the host's link, and remembering this browser's seat.
import { RELAY_URL } from './config';

const GUEST_KEY = 'auxparty.guest';
const NAME_KEY = 'auxparty.name';

export interface PartyLink {
  deviceId: string;
  secret: string;
}

export interface GuestSession {
  deviceId: string;
  guestId: string;
  token: string;
  name: string;
  joinedAt: number;
}

/**
 * `#party=<deviceId>.<secret>`. The secret lives in the fragment, which browsers
 * never send to a server, so it stays out of logs.
 */
export function parsePartyLink(hash: string): PartyLink | null {
  const match = /^#party=([a-z0-9]{20,40})\.([\w-]{20,100})$/.exec(hash);
  return match ? { deviceId: match[1], secret: match[2] } : null;
}

export function partyLinkUrl(deviceId: string, secret: string): string {
  return `${location.origin}/#party=${deviceId}.${secret}`;
}

export function loadGuest(): GuestSession | null {
  try {
    const g = JSON.parse(localStorage.getItem(GUEST_KEY) ?? 'null');
    return g?.deviceId && g?.guestId && g?.token ? (g as GuestSession) : null;
  } catch {
    return null;
  }
}

export function clearGuest(): void {
  try {
    localStorage.removeItem(GUEST_KEY);
  } catch {
    /* ignore */
  }
}

/** The name used last time, to pre-fill the next party. */
export function lastName(): string {
  try {
    return localStorage.getItem(NAME_KEY) ?? '';
  } catch {
    return '';
  }
}

export const NAME_MAX = 24;

export class JoinError extends Error {
  constructor(message: string, readonly linkDead = false) {
    super(message);
  }
}

export async function joinParty(link: PartyLink, name: string): Promise<GuestSession> {
  let res: Response;
  try {
    res = await fetch(`${RELAY_URL}/v1/party/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: link.deviceId, secret: link.secret, name }),
    });
  } catch {
    throw new JoinError("Couldn't reach Auxparty. Check your connection.");
  }
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    switch (body.error) {
      case 'invalid_link': throw new JoinError('This party link has expired, or the party is over. Ask the host for a new link.', true);
      case 'party_full': throw new JoinError('This party is full.');
      case 'rate_limited': throw new JoinError('Too many tries. Wait a few minutes and try again.');
      case 'name_required': throw new JoinError('Enter your name so people know who picked what.');
      default: throw new JoinError(`Joining failed (${res.status}).`);
    }
  }
  const session: GuestSession = {
    deviceId: body.deviceId,
    guestId: body.guestId,
    token: body.token,
    name: body.name,
    joinedAt: Date.now(),
  };
  try {
    localStorage.setItem(GUEST_KEY, JSON.stringify(session));
    localStorage.setItem(NAME_KEY, name);
  } catch {
    /* private mode: lasts this tab */
  }
  return session;
}
