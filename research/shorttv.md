# ShortTV / ShortMax (shorttv.live/ar) — API Spec

SPA. Episode pages embed open HLS URLs directly — no auth. 3 qualities per episode.

## URL patterns
- Home: `https://www.shorttv.live/ar`
- Episode: `https://www.shorttv.live/ar/episode/{name}-{showId}-{ep}` (e.g. `سيف-العدالة-مدبلج-8121-1`)
- Stream CDN: `akamai-static.shorttv.live/hls-encrypted/{hash}_{quality}/main.m3u8`

## Playback (WORKS — open, 3 qualities)
Episode page HTML contains per-episode quality URLs:
- `video_1080`: `https://akamai-static.shorttv.live/hls-encrypted/{hash}_1080/main.m3u8`
- `video_720`: `..._{hash}_720/main.m3u8`
- `video_480`: `..._{hash}_480/main.m3u8`
All return HTTP 200 media playlists (segments). No auth needed.

## Episode page data (HTML, field-indexed)
- `title`/`name` (series), `description`, `coverId`
- `episodeList` (list of episodes), `episodeNum`, `playNum`
- Each episode has `video_1080`/`video_720`/`video_480`
- Dubbed marker: `[مدبلج]` in title

## Subtitles
- No separate subtitle files found

## Approach for provider
- Parse home HTML for `/ar/episode/` links
- Fetch episode page, regex-extract video_1080/720/480 URLs
- Surface all 3 qualities as ExtractorLink (or master-style single link)
