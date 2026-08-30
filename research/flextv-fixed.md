# FlexTV — Verified Extraction (2026-08-30)

## Working
- Home page has episode links: `/ar/episodes/episode-{no}-{pctTitle}-{seriesId}` (trailing alnum seriesId).
- Detail page embeds master m3u8: `https://resources-sgp-auth.flextv.cc/wz/m3u8/{...}/abr.m3u8?auth_key=...`
- Master m3u8 is VALID multi-quality: 720x1280 / 480x854 / 1080 (3 variants incl RESOLUTION) — direct, no token beyond auth_key in URL.
- Provider fix: URL-decode Arabic title (was showing raw seriesId). Episode list filtered to same seriesId. Compiles. ✅

## Languages / subtitles (partial)
- The page embeds a language list `["en","de","fr","es","pt","tc","id","th","it","tr","ko","ja","vi","sc"]` (reads as dubbing/subtitle language codes) and each episode object has fields `{"video_url":56,"progressive":57,"subtitle":58,"is_trial":21,"cover":59,"unlock_type":60,"duration":61,"upload_date":62}`.
- Flattened JSON array (Nuxt-style) — resolving index 58 (subtitle content) requires decoding the flattened refs.
- No `.vtt`/`.srt` URLs found anywhere on the page; subtitle field likely an embedded language code or ID, not a file.
- Verdict: qualities ✅ via master; subtitles/dubbing languages NOT cleanly exposed as files → not surfaced.

## Decision
- Ship the master-based provider (multi-quality). Do NOT attempt flattened-array subtitle decode (low value, high fragility).
- Note: platform is essentially Arabic-dubbed short dramas; multi-language feature on page relates to UI language, not per-episode audio tracks exposed to us.