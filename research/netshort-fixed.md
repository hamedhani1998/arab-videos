# NetShort — Verified Extraction (2026-08-30)

## Page structure
- Episode page: `/ar/episode/{name}-{showId}` (name pct-encoded, showId numeric).
- Home `/ar/all-episodes`: 74 episode links, dedupe by showId.
- Show has up to 40 episodes; first 10 unlocked (isLock:false), rest locked.

## The playable URL ("playVoucher") IS embedded in the page (for episode 1 / the statically selected episode)
- RSC payload (`self.__next_f.push([1,"..."])`) contains `shortPlayDetailVo`.
- After decoding the RSC escape ONE level, there is exactly ONE `playVoucher`:
  `https://cfcdn.netshort.com/{hash}?a=0&auth_key=...` 
  and ONE `subtitleList` array: `[{"url":"https://cfcdn.netshort.com/{hash}?auth_key=...&mime_type=text_plain","format":"webvtt","subtitleLanguage":"vi_VN","expireTime":...}, ...]` (multi-language).
- Subtitle files are webvtt, on cfcdn.netshort.com, identified by `subtitleLanguage` (e.g. vi_VN, en_US, it_IT, ko_KR, fr_FR, tr_TR, id_ID).

## CRITICAL escaping discovery
The raw HTML is DOUBLE-JSON-escaped (RSC string inside the Next.js JSON payload).
- Raw bytes: `playVoucher\\\":\\\"https://...` (TWO backslashes then quote).
- The URL contains `\\u0026` in raw = `&` after ONE unescape.
- `?episode=1`, `?episode=2`, `?episodeId=...`, `?ep=...` query params on the show page did NOT change the embedded playVoucher (always episode 1's). So per-episode selection for episodes 2+ MUST go through the app's internal API (client-side fetch), which is what the reverse-engineering agent is finding.

## Extraction approach that works on raw HTML without full RSC decode
- PlayVoucher: `Regex("""playVoucher\\*"\s*:\s*\\*"(https?:[^"]+)""")` captures the full URL
  *through* the `\\u0026` (because `[^"]+` allows backslashes; stops at closing `"`).
  Then unescape: `.replace("\\u0026","&")` and `.replace("\\/","/")`.
- Subtitles: brace-match `subtitleList\\*":[{...}]` on raw HTML, then within it
  extract each `{...}` (brace matcher tolerant of `\\`), unescape one layer
  (replace `\\\"`→`"`, `\\u0026`→`&`, `\\/`→`/`), then `ObjectMapper.readValue` into
  `data class SubtitleItem(url, format, subtitleLanguage)`.
- Episode list for load(): from `videoEpisodeInfos` (episodeId, episodeNo, isLock).

## Status
- Episode 1 of every show is playable HTML-only (playVoucher in page).
- Episodes 2+ need the per-episode API (pending agent findings).
- Home/search: episode-link regex works; MUST URL-decode the show name.
- `extractJsonObjects` (existing Kotlin) is BROKEN on raw HTML because it can't
  parse the double-escaped structure — replaced by the targeted regex above.