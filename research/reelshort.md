# ReelShort (reelshort.com/ar) — API Spec

Next.js app by Crazy Maple Studio. **Heavily protected — NOT practically extractable.**

## What works
- Home page server-renders into `__NEXT_DATA__` with full show list
- API: `POST /api/ms/hall/webInfo` (hallId=6000150) → show list with `book_id`, `title`, `video_pic` (cover on v-mps.crazymaplestudios.com)
- Show fields: `book_id` (hex, e.g. `6945080a4a82e452640bed96`), `title`, `video_type`, `episode_index`, `chapter_id`

## What's blocked
- API requires auth: `uid` header ("CheckLoginMiddleware ParseHeaders err: field uid is not set")
- Playback (`play_info`) is encrypted (base64-like blob, client-decrypted)
- Web player routes all 404 (/ar/play, /ar/video, /ar/chapter/... etc.)
- Only web routes: /ar/episodes, /ar/shelf (both 404 or auth-gated)

## Verdict
Cannot build a working provider — needs login + decrypts playback. Recommend skipping or using only the home list (no playback).
