# NetShort (netshort.com/ar) — API Spec

Next.js app, ByteDance VOD infra. Covers: `awscover.netshort.com/tos-vod-mya-v-...`. Streams: `cfcdn.netshort.com` signed MP4.

## Home / Show list
- URL: `https://netshort.com/ar/all-episodes`
- Data: Next.js RSC flight → `videoList[]`
- Fields: `shortPlayLibraryId`, `shortPlayId`, `shortPlayName`, `totalEpisode`, `shortPlayCover`
- Also genre pages: `/ar/drama/{name}-{labelId}` (from `shortPlayLabels`)

## Search
- Likely `/ar/search?keyword=` or similar (not yet confirmed — check `/ar/search`)

## Detail (show + episodes)
- URL: `https://netshort.com/ar/episode/{name}-{shortPlayId}`
- Data: `shortPlayDetailVo`
  - `shortPlayId`, `shortPlayName`, `shortPlayCover`, `shotIntroduce`, `shortPlayLabels` (genre→url map), `labelIds`
- Episodes: `videoEpisodeInfos[]` → `episodeId`, `episodeNo`, `isLock`, `episodeCover`
- Full episodes page: `/ar/full-episodes/{name}-{id}`

## Languages
- `initialComputedLanguages[]`: `{value, label, iso, url}`
- Languages: ar (default), en, zh, ja, ko, it, tr, id, hi + more
- Each language has its OWN localized episode URL (e.g. `/en/episode/...`, `/ja/episode/...`)
- `languageDetail.list` maps locale→url

## Playback
- `playVoucher` = signed direct MP4 URL: `https://cfcdn.netshort.com/{path}?a=0&auth_key=...&br=750&bt=750&...&mime_type=video_mp4`
- Single quality direct MP4 (no master playlist, no separate subtitle files found)
- For CloudStream: surface each language as a separate ExtractorLink (fetch that language's episode page → its playVoucher)

## Playback (playVoucher — direct signed MP4)
- `playVoucher` is a signed MP4 on `cfcdn.netshort.com`: `https://cfcdn.netshort.com/{hash}?a=0&auth_key={key}-{expiry}&br=750&bt=750&...&mime_type=video_mp4`
- `auth_key` has expiry (~5 days); URL embedded in page at render time (in `shortPlayDetailVo`)
- My server-side curl gets HTTP 403 (likely datacenter-IP blocked by CDN), but user confirms it works in app
- The player uses playVoucher directly as `<video src>`: `playerControllerRef` + `playVoucher` → video source
- For CloudStream: extract playVoucher from episode page RSC data → use as direct MP4 ExtractorLink

## Subtitles (full support!)
- `subtitleList[]`: `{url, format:"webvtt", subtitleLanguage, expireTime}`
- Languages: it_IT, ko_KR, fr_FR, tr_TR, id_ID, en_US (and more) — one subtitle URL per language
- Subtitle URLs also on cfcdn.netshort.com (same signed format)
- Player picks subtitle by `subtitleLanguage === (currentLanguage || "en_US")`

## Subtitles
- No separate subtitle files found in page data
