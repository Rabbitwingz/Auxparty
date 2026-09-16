// Browser side of the relay protocol (relay/PROTOCOL.md): authenticate, keep the
// socket alive, route command results, reconnect with backoff.
import { RELAY_URL } from './config';
import type { Pairing } from './pairing';

export interface PhoneState {
  source?: string;
  packageName?: string | null;
  title?: string | null;
  artist?: string | null;
  album?: string | null;
  durationMs?: number | null;
  positionMs?: number | null;
  /** Phone wall clock (ms) when positionMs was true. */
  positionAt?: number;
  playback?: 'playing' | 'paused' | 'stopped' | 'buffering' | 'none' | 'error' | 'other';
  volume?: number;
  maxVolume?: number;
  artworkKey?: string;
}

export interface Artwork {
  key: string;
  mime: string;
  data: string | null;
}

export interface SearchResult {
  videoId: string;
  title: string;
  artist: string | null;
  album: string | null;
  duration: string | null;
  thumbnail: string | null;
}

export type Link = 'connecting' | 'ready' | 'reconnecting';

export interface RelayEvents {
  link: (link: Link) => void;
  presence: (deviceOnline: boolean) => void;
  state: (state: PhoneState | null) => void;
  artwork: (artwork: Artwork | null) => void;
  /** The phone revoked this browser, or its credentials stopped working. */
  unlinked: (reason: 'revoked' | 'invalid') => void;
}

export class RelayError extends Error {}

interface Pending {
  resolve: (data: unknown) => void;
  reject: (error: Error) => void;
  timer: number;
}

export class Relay {
  private socket: WebSocket | null = null;
  private reconnectTimer = 0;
  private keepAliveTimer = 0;
  private backoff = 1000;
  private nextId = 1;
  private readonly pending = new Map<string, Pending>();
  private ready = false;
  private stopped = false;
  private readonly listeners = new Map<keyof RelayEvents, Array<(...args: unknown[]) => void>>();

  deviceOnline = false;

  constructor(private readonly pairing: Pairing) {
    // Mobile browsers drop sockets in the background; come straight back.
    document.addEventListener('visibilitychange', this.onVisible);
  }

  on<K extends keyof RelayEvents>(event: K, fn: RelayEvents[K]): void {
    const list = this.listeners.get(event) ?? [];
    list.push(fn as unknown as (...args: unknown[]) => void);
    this.listeners.set(event, list);
  }

  private emit<K extends keyof RelayEvents>(event: K, ...args: Parameters<RelayEvents[K]>): void {
    for (const fn of this.listeners.get(event) ?? []) fn(...args);
  }

  connect(): void {
    window.clearTimeout(this.reconnectTimer);
    if (this.socket || this.stopped) return;

    const ws = new WebSocket(`${RELAY_URL.replace(/^http/, 'ws')}/v1/ws?device=${encodeURIComponent(this.pairing.deviceId)}`);
    this.socket = ws;
    this.emit('link', 'connecting');

    ws.addEventListener('open', () => {
      ws.send(JSON.stringify({ type: 'auth', role: 'client', clientId: this.pairing.clientId, token: this.pairing.token }));
    });

    ws.addEventListener('message', (event) => {
      if (event.data === 'pong') return;
      let msg: Record<string, unknown>;
      try {
        msg = JSON.parse(String(event.data));
      } catch {
        return;
      }
      this.handle(msg);
    });

    ws.addEventListener('close', (event) => {
      if (this.socket !== ws) return;
      this.socket = null;
      this.ready = false;
      window.clearInterval(this.keepAliveTimer);
      this.failPending('connection_lost');

      if (event.code === 4001 || event.code === 4003) {
        this.stopped = true;
        this.emit('unlinked', event.code === 4003 ? 'revoked' : 'invalid');
        return;
      }
      if (this.stopped) return;
      this.emit('link', 'reconnecting');
      this.reconnectTimer = window.setTimeout(() => this.connect(), this.backoff + Math.random() * 500);
      this.backoff = Math.min(this.backoff * 2, 30_000);
    });
  }

  disconnect(): void {
    this.stopped = true;
    window.clearTimeout(this.reconnectTimer);
    window.clearInterval(this.keepAliveTimer);
    document.removeEventListener('visibilitychange', this.onVisible);
    const ws = this.socket;
    this.socket = null;
    ws?.close(1000);
    this.failPending('connection_lost');
  }

  /** Sends a command to the phone and resolves with its reply. */
  command<T = unknown>(action: string, args: Record<string, unknown> = {}, timeoutMs = 10_000): Promise<T> {
    const ws = this.socket;
    if (!ws || ws.readyState !== WebSocket.OPEN || !this.ready) {
      return Promise.reject(new RelayError('device_offline'));
    }
    const id = String(this.nextId++);
    return new Promise<T>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.pending.delete(id);
        reject(new RelayError('timeout'));
      }, timeoutMs);
      this.pending.set(id, { resolve: resolve as (d: unknown) => void, reject, timer });
      ws.send(JSON.stringify({ type: 'cmd', id, action, args }));
    });
  }

  private handle(msg: Record<string, unknown>): void {
    switch (msg.type) {
      case 'ready':
        this.backoff = 1000;
        this.ready = true;
        this.deviceOnline = Boolean(msg.deviceOnline);
        window.clearInterval(this.keepAliveTimer);
        // Keeps proxies and NATs from silently dropping an idle socket.
        this.keepAliveTimer = window.setInterval(() => {
          if (this.socket?.readyState === WebSocket.OPEN) this.socket.send('ping');
        }, 30_000);
        this.emit('link', 'ready');
        this.emit('presence', this.deviceOnline);
        this.emit('state', (msg.state as PhoneState | null) ?? null);
        this.emit('artwork', (msg.artwork as Artwork | null) ?? null);
        return;
      case 'state':
        this.emit('state', (msg.state as PhoneState | null) ?? null);
        return;
      case 'artwork':
        this.emit('artwork', (msg.artwork as Artwork | null) ?? null);
        return;
      case 'presence':
        this.deviceOnline = Boolean(msg.deviceOnline);
        this.emit('presence', this.deviceOnline);
        return;
      case 'result': {
        const p = this.pending.get(String(msg.id));
        if (!p) return;
        this.pending.delete(String(msg.id));
        window.clearTimeout(p.timer);
        if (msg.ok) p.resolve(msg.data);
        else p.reject(new RelayError(String(msg.error ?? 'failed')));
        return;
      }
    }
  }

  private failPending(reason: string): void {
    for (const [id, p] of this.pending) {
      window.clearTimeout(p.timer);
      p.reject(new RelayError(reason));
      this.pending.delete(id);
    }
  }

  private onVisible = () => {
    if (!document.hidden && !this.socket && !this.stopped) {
      this.backoff = 1000;
      this.connect();
    }
  };
}

const MESSAGES: Record<string, string> = {
  device_offline: 'The phone is offline.',
  connection_lost: 'Lost the connection. Reconnecting…',
  nothing_playing: 'Nothing is playing on the phone yet. Pick a song.',
  overlay_permission_missing: 'On the phone, allow Auxparty to "display over other apps" so it can start songs.',
  rate_limited: 'Slow down a little.',
  timeout: "The phone didn't answer in time.",
};

/** A sentence for the user from a relay or phone error code. */
export function explain(error: unknown): string {
  const code = error instanceof Error ? error.message : String(error);
  if (code.startsWith('search_failed')) return 'Search failed on the phone. Try again.';
  return MESSAGES[code] ?? code;
}
