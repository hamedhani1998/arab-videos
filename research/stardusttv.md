# StardustTV (stardusttv.net/ar) — API Spec

Nuxt 3 app (RTL). API responses encrypted, BUT m3u8 URLs are embedded directly in HTML — fully open.

## Stream CDN
`https://v.stardust-tv.com/` — open, no auth needed

## m3u8 URL patterns (both work, HTTP 200)
1. By name: `https://v.stardust-tv.com/{lang}/{seriesName}_{AR_DUB|AR}/h264/{episodeName}_{ep}/{hash}.m3u8`
   - lang folder: `阿拉伯语` (Arabic)
   - dubbed variants: `_AR_DUB` (dubbed) and `_AR` (subtitled)
2. By ID: `https://v.stardust-tv.com/prod/{seriesId}/{ep}/{hash}.m3u8`

Both are single-quality media playlists (segments .ts). No master playlist with multiple qualities found — each is one quality.

## Home page structure
- Show cards in HTML with fields: `video_name`, `id`, `videos` (episode list), `video_trial`
- Series have numeric IDs; episodes numbered 001, 002...
- API base (encrypted, needs app auth): `us-prod-api.stardust-tv.com` (requires APPID)

## Languages
- At least Arabic (العربية) with AR_DUB/AR variants
- Other languages likely available (API is encrypted so hard to enumerate from page)

## Approach for provider
- Parse home HTML for show cards → extract m3u8 URLs directly (regex)
- Each show → its episode m3u8 URLs are in the HTML
- Surface AR_DUB vs AR as separate language tracks
- No separate subtitle files; single quality per m3u8
