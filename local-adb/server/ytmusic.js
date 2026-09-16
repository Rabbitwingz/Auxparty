import { Innertube, UniversalCache } from 'youtubei.js';

let clientPromise = null;

function client() {
  clientPromise ??= Innertube.create({
    cache: new UniversalCache(false),
    generate_session_locally: true,
  });
  return clientPromise;
}

/** Thumbnails come back at 120px; ask the CDN for something worth looking at. */
function upscale(url, size = 544) {
  if (!url) return null;
  return url.replace(/=w\d+-h\d+/, `=w${size}-h${size}`);
}

function normalise(item) {
  const thumbs = item.thumbnail?.contents ?? item.thumbnails ?? [];
  const best = thumbs.reduce((a, b) => (b.width > (a?.width ?? 0) ? b : a), null);
  return {
    videoId: item.id ?? null,
    playlistId: item.playlist_id ?? item.id ?? null,
    title: item.title ?? null,
    artist: item.artists?.map((a) => a.name).join(', ') ?? item.author?.name ?? null,
    album: item.album?.name ?? null,
    durationSeconds: item.duration?.seconds ?? null,
    durationText: item.duration?.text ?? null,
    thumbnail: upscale(best?.url),
    kind: item.item_type ?? null,
  };
}

/**
 * Pull items out of a Search response. The shelf layout varies by result type,
 * so check the typed accessors first and fall back to walking the sections.
 */
function extract(res, key) {
  const direct = res[key]?.contents;
  if (direct?.length) return direct;
  const sections = res.contents ?? [];
  const items = [];
  for (const section of sections) {
    for (const c of section.contents ?? []) {
      if (c.type === 'MusicResponsiveListItem' || c.type === 'MusicTwoRowItem') items.push(c);
    }
  }
  return items;
}

export async function search(query, type = 'song') {
  const yt = await client();
  const res = await yt.music.search(query, { type });
  const key = { song: 'songs', video: 'videos', album: 'albums', playlist: 'playlists', artist: 'artists' }[type];
  return extract(res, key)
    .map(normalise)
    .filter((r) => r.videoId || r.playlistId);
}

export async function suggestions(input) {
  if (!input?.trim()) return [];
  const yt = await client();
  const sections = await yt.music.getSearchSuggestions(input);
  const out = [];
  for (const section of sections ?? []) {
    for (const s of section.contents ?? []) {
      const text = s.suggestion?.text ?? s.title?.text ?? null;
      if (text) out.push(text);
    }
  }
  return [...new Set(out)].slice(0, 8);
}

// dumpsys gives a title and artist but no artwork and no duration, and its
// comma-joined description cannot be split back apart reliably. Searching YT
// Music for the whole raw string recovers canonical metadata as a side effect.
// Cached, so the poll loop only pays this on a track change.
const metaCache = new Map();
const MAX_CACHE = 200;

export async function resolveTrack(query, hintTitle) {
  if (!query) return null;
  if (metaCache.has(query)) return metaCache.get(query);

  let result = null;
  try {
    const hits = await search(query, 'song');
    const lower = hintTitle?.toLowerCase();
    // Prefer a title match over whatever happened to rank first.
    const hit = (lower && hits.find((h) => h.title?.toLowerCase() === lower))
      || (lower && hits.find((h) => h.title?.toLowerCase().includes(lower)))
      || hits[0];
    if (hit) {
      result = {
        title: hit.title,
        artist: hit.artist,
        album: hit.album,
        thumbnail: hit.thumbnail,
        durationSeconds: hit.durationSeconds,
        videoId: hit.videoId,
      };
    }
  } catch {
    result = null; // offline or rate-limited: fall back to the dumpsys fields
  }

  if (metaCache.size >= MAX_CACHE) metaCache.delete(metaCache.keys().next().value);
  metaCache.set(query, result);
  return result;
}
