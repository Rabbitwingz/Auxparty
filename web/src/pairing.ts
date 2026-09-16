// Browser ↔ phone pairing. Storage key predates the Auxparty name and is kept, so
// browsers that were linked before the redesign stay linked.
import { RELAY_URL } from './config';

const PAIRING_KEY = 'ytm-bridge.pairing';

export interface Pairing {
  deviceId: string;
  clientId: string;
  token: string;
  pairedAt: number;
}

export function loadPairing(): Pairing | null {
  try {
    const p = JSON.parse(localStorage.getItem(PAIRING_KEY) ?? 'null');
    return p?.deviceId && p?.clientId && p?.token ? (p as Pairing) : null;
  } catch {
    return null;
  }
}

function savePairing(p: Pairing): void {
  try {
    localStorage.setItem(PAIRING_KEY, JSON.stringify(p));
  } catch {
    /* private mode: lasts this tab */
  }
}

export function clearPairing(): void {
  try {
    localStorage.removeItem(PAIRING_KEY);
  } catch {
    /* ignore */
  }
}

/** "Chrome on Windows": shown on the phone's list of linked browsers. */
export function deviceName(ua = navigator.userAgent): string {
  const browser = /Edg\//.test(ua) ? 'Edge'
    : /OPR\//.test(ua) ? 'Opera'
    : /Firefox\//.test(ua) ? 'Firefox'
    : /Chrome\//.test(ua) ? 'Chrome'
    : /Safari\//.test(ua) ? 'Safari'
    : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows'
    : /Android/.test(ua) ? 'Android'
    : /iPhone|iPad/.test(ua) ? 'iOS'
    : /Mac OS X/.test(ua) ? 'macOS'
    : /Linux/.test(ua) ? 'Linux'
    : '';
  return os ? `${browser} on ${os}` : browser;
}

/** Codes use an alphabet without 0/O, 1/I/L; anything else is a typo. */
export const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';

export function normalizeCode(input: string): string {
  return input.toUpperCase().replace(/[^0-9A-Z]/g, '').slice(0, 8);
}

export class PairingError extends Error {}

export async function pair(code: string): Promise<Pairing> {
  let res: Response;
  try {
    res = await fetch(`${RELAY_URL}/v1/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, name: deviceName() }),
    });
  } catch {
    throw new PairingError("Couldn't reach Auxparty. Check your connection.");
  }
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new PairingError(
      body.error === 'rate_limited' ? 'Too many tries. Wait a few minutes, then use a fresh code.'
        : body.error === 'invalid_code' ? 'That code is wrong or has expired. Ask for a fresh one.'
        : `Linking failed (${res.status}).`,
    );
  }
  const pairing: Pairing = { deviceId: body.deviceId, clientId: body.clientId, token: body.token, pairedAt: Date.now() };
  savePairing(pairing);
  return pairing;
}
