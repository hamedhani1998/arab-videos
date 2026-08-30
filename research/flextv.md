# FlexTV (flextv.cc/ar) — API Spec

Nuxt app. API responses are ENCRYPTED (base64-like blob, decrypted by client). BUT the master m3u8 URL is embedded directly in the episode page HTML.

## URL patterns
- Home: `https://www.flextv.cc/ar`
- Episode: `https://www.flextv.cc/ar/episodes/episode-{no}-{name}-{seriesId}` (e.g. `MPOxkPVZqX`)
- Cover CDN: `file-cdn.flextv.cc/image/...`
- Stream CDN: `resources-sgp-auth.flextv.cc/wz/m3u8/.../abr.m3u8?auth_key=...`

## Playback (WORKS — open master playlist)
- Master playlist URL embedded in episode HTML: `https://resources-sgp-auth.flextv.cc/wz/m3u8/{paths}/abr.m3u8?auth_key=...`
- HLS ABR with 3 qualities: 1080x1920, 720x1280, 480x854 (vertical video)
- `auth_key` is time-limited but regenerated per page load
- No separate subtitle files found in page; no separate audio tracks in master

## Data model (from __NUXT_DATA__ episode object, field indices)
- `name` (series title), `description`, `cover` (url), `episodeNumber`
- `lang`, `langs` (language list), `video_url`, `subtitle` (field refs)
- `series_id` (e.g. `MPOxkPVZqX`), `series_no` (episode number)

## API endpoints (from __NUXT_DATA__ once keys, all encrypted responses)
- `get-/webGetSeriesDetailContent-{"series_id":"..."}-ar-...`
- `get-/webGetPlayInfo-{"series_id":"...","series_no":N}-ar-...` → encrypted play data
- `get-/webGetSeriesLangName-{"series_id":"..."}-ar-...` → languages
- `get-/webGetSeriesSectionContentList-{"series_id":"...","source":1}-ar-...`
- `get-/webRecommendedForYou-{"series_id":"...","type":1,"number":14}-ar-...`

## Approach for provider
- Parse episode page HTML for the `abr.m3u8` URL (regex) → master playlist → qualities
- For languages: parse `langs` from the page, or call webGetSeriesLangName (if decryptable)
- Home/section content: parse HTML for episode links
