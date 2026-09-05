package com.nartodrama.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// ---- Domain layout (resilience per-purpose, NOT mirror-merging) ----
//   BROWSE (search/detail):        edge.narto-drama.com — the OPEN (no-Cloudflare) mirror,
//                                  fast, serves the native Arabic catalog (incl. all مدبلج).
//   PLAYBACK (refresh-source):     narto-drama.com MAIN is now the fast path (~1s, same payload,
//                                  and NOT Cloudflare-blocked on this API route), while edge's
//                                  own refresh-source became SLOW (12-41s — over the default client
//                                  timeout -> dead links). loadLinks fetches main first, edge fallback.
// edge endpoints:
//   GET /search?lang=ar-SA&q=<query>                                -> HTML ListItem JSON array
//   GET /detail/watch/{slug}                                        -> series detail
//   GET /e/rs/detail/watch/{slug}/{ep}/refresh-source?rs_ctx={JWT}  -> playback (either base)
// The web HTML frontend narto-drama.com itself has Cloudflare around the pages, but the
// refresh-source API responds to bare requests — that's what made it viable for playback.

// One JSON-LD ListItem entry from the search results page.
private data class SearchHit(
    @JsonProperty("@type") val type: String? = null,
    val url: String? = null,    // https://edge.narto-drama.com/detail/watch/{slug}?lang=...
    val name: String? = null,   // Arabic title (e.g. "[مدبلج] ...")
    val image: String? = null,  // /assets/poster/{id}.jpg
)

// ---- narto edge playback API ----
// Narto aggregates short-drama from MANY backends (shortmax, NetShort, StardustTV,
// mydramawave, Hakuna Matata, montagehub, TikTok, GoodShort, JoyReels, narto-native).
// Each work's direct_play_url/play_url/multi_resolutions therefore points to a DIFFERENT
// host and may be an HLS playlist OR a direct MP4 file — so loadLinks must detect the
// container type (M3U8 vs direct VIDEO) per link instead of assuming everything is HLS.
private data class EdgeResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val canonical: String? = null,          // full canonical URL hint on slug_mismatch
    @JsonProperty("retry_after_seconds") val retryAfterSeconds: Int? = null, // 429 cooldown: how long to wait
    @JsonProperty("direct_play_url") val directPlayUrl: String? = null, // may be HLS master OR direct MP4
    @JsonProperty("play_url") val playUrl: String? = null,
    @JsonProperty("multi_resolutions") val multiResolutions: List<EdgeResolution>? = null,
    @JsonProperty("multi_subtitles") val multiSubtitles: List<EdgeSub>? = null,
    // Single-track fields the API also returns (used when multi_* is empty / has exactly one entry).
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,           // single external subtitle
    @JsonProperty("direct_subtitle_url") val directSubtitleUrl: String? = null, // single direct subtitle
    @JsonProperty("selected_subtitle_language") val selectedSubtitleLanguage: String? = null,
)

private data class EdgeResolution(
    val resolution: Int? = null,
    val label: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
)

private data class EdgeSub(
    @JsonProperty("language_code") val languageCode: String? = null,
    val label: String? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,     // relative /e/s/{jwt}
)

// Minimal fake JWT the edge accepts (claims are not verified, slug/ep read from path).
private val fakeRsCtx = "eyJhbGciOiJub25lIn0.eyJ2IjoiMSJ9."
// The main narto domain serves refresh-source FAST (~1s) with the full payload and no Cloudflare
// challenge on that API route. edge still works but is now SLOW there (12-41s) — over a client
// timeout -> "فشل بالروابط". loadLinks uses this as the PRIMARY playback host.
private const val MAIN_HOST = "https://narto-drama.com"
private const val STREAM_HOST = "https://stream.narto-drama.com"

// Backend hosts that are dead (DNS NODATA / non-existent domain) and must NOT be emitted as
// playback links — the player would select them and fail (saw "UnknownHostException:
// sulao.montagehub.xyz" on device; nslookup via dns.google confirms the domain is gone).
private val DEAD_HOST_PATTERNS = listOf("montagehub")

// ---- SINGLE domain per provider (NO mirror merging) ----
// The plugin ships TWO Narto sources: "edge narto drama" (open edge mirror, DEFAULT for browsing)
// and "https://narto-drama.com" (main domain, FAST for playback refresh). Each provider class
// is a MainAPI with its own name + mainUrl. They share ALL logic via NartoBaseProvider.
// Previous versions auto-failed-over between the two via tryMirrors — that MERGING is what broke
// the interface display on device (v8–v11 lesson, fixed by v12 single-domain).
open class NartoBaseProvider : MainAPI() {
    override var name = "Narto Drama"
    // Default mainUrl (edge) is open & fast for browsing. The MAIN domain is used only for the
    // playback refresh-source fetch (fetchRefresh specificity below) because edge's own
    // refresh-source became slow (12-41s) while main answers in ~1s with no Cloudflare on that
    // API route.
    open override var mainUrl = "https://edge.narto-drama.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Main screen = one section per tab, each a distinct Arabic query (verified live: different
// queries return DIFFERENT feeds, 0 overlap — so these are real categories, not the
// same list repackaged). The empty query (q='') is the site's "recommendations" feed but it
// intermittently returns HTTP 502 from edge, so it is NOT used. Each tab lazily fetches ONE
// query only when opened. v25: capped to the 4 most useful tabs and each list to 12 items so the
// first paint is light (the search HTML is ~440KB — fetching 8 tabs & 24 items each made the
// home screen feel slow). The search itself prefers MAIN_HOST (measured faster than edge for
// keyword feeds: ~1-4s vs ~3-24s).
    override val mainPage = mainPageOf(
        "دراما" to "🎬 دراما",
        "مدبلج" to "🎙️ مدبلج",
        "رومانسي" to "💕 رومانسي",
        "أكشن" to "⚔️ أكشن",
    )

    // The video-serve origin we must present to the edge/stream endpoints.
    private val nartoOrigin = "https://narto-drama.com"

    // SITE'S CHANGING LINK (native "setting"): every request is built from `mainUrl`, which is an
    // open OVERRIDE var. In CloudStream the user can change the base link from the provider
    // settings screen by cloning Narto Drama and editing its URL — that is how the site's
    // ever-changing mirror (currently edge.narto-drama.com, previously narto-drama.com) is kept
    // up to date without touching code. All playback links are emitted directly from the API so a
    // move to another mirror keeps working.

    // Detect whether a stream URL is an HLS playlist (M3U8) or a direct single video file (MP4).
    // Narto serves BOTH from different backends, and CloudStream must get the right link type or
    // it will refuse/garble playback (an MP4 handed as M3U8 -> parse error, the "doesn't play" bug).
    // NOTE: purely URL-based — NO network HEAD probe. Probing each link (some hosts are dead/slow)
    // was the "التشغيل بطيء" delay; classifying from the URL is instant.
    private fun inferStreamType(url: String): ExtractorLinkType {
        val lower = url.lowercase()
        // "contains" (NOT endsWith) so a signed direct-MP4 like `file.mp4?token=abc` — which ends in
        // the query string, not `.mp4` — still classifies as VIDEO and plays (was misread as M3U8).
        if (lower.contains("mime_type=video_mp4") || lower.contains(".mp4") || lower.endsWith(".m4v"))
            return ExtractorLinkType.VIDEO
        // M3U8 is the dominant container across narto's backends (shortmax/-stream, proxy /e/m/,
        // akamai) — ambiguous token URLs default to HLS rather than block on a network probe.
        return ExtractorLinkType.M3U8
    }

    // ---- Parse the ListItem JSON array embedded in a /search HTML page ----
    private fun parseSearchItems(html: String): List<SearchHit> {
        return try {
            val marker = "\"@type\":\"ListItem\""
            val start = html.indexOf(marker)
            if (start < 0) return emptyList()
            // Walk back to the opening '[' of the array that holds the list items.
            var arrStart = start
            while (arrStart > 0 && html[arrStart] != '[') arrStart--
            if (html[arrStart] != '[') return emptyList()
            // Walk forward to the matching ']'.
            var depth = 0
            var arrEnd = -1
            for (k in arrStart until html.length) {
                when (html[k]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { arrEnd = k; break } }
                }
            }
            if (arrEnd < 0) return emptyList()
            mapper.readValue(
                html.substring(arrStart, arrEnd + 1),
                object : com.fasterxml.jackson.core.type.TypeReference<List<SearchHit>>() {}
            ).filter { !it.url.isNullOrBlank() && !it.name.isNullOrBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // v25: fetch a /search page. Prefer MAIN_HOST for keyword feeds (measured faster for this
    // route), falling back to this provider's own mainUrl if MAIN_HOST fails — so a 502 or a
    // slow edge upstream never blanks the home screen.
    private suspend fun fetchSearch(q: String): String? {
        val urlEncQ = java.net.URLEncoder.encode(q, "UTF-8")
        val hosts = if (mainUrl == MAIN_HOST) listOf(MAIN_HOST) else listOf(MAIN_HOST, mainUrl)
        for (h in hosts) {
            try {
                val html = app.get("$h/search?lang=ar-SA&q=$urlEncQ", referer = nartoOrigin, headers = mapOf("User-Agent" to UA)).text
                if (html.contains("\"@type\":\"ListItem\"")) {
                    searchBase = h
                    return html
                }
            } catch (e: Exception) {
                android.util.Log.e("NartoDrama", "search fetch $h error", e)
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // request.data is the tab's query string. v25: capped list to 12 so first paint is light
        // (the /search HTML is ~440KB; a full 24-item grid isn't needed for the home rows).
        val t0 = System.currentTimeMillis()
        return try {
            val q = request.data.trim()
            val html = fetchSearch(q)
            if (html == null) {
                android.util.Log.e("NartoDrama", "getMainPage fetch failed q=$q")
                return null
            }
            android.util.Log.e("NartoDrama", "getMainPage q=$q fetchMs=${System.currentTimeMillis() - t0} len=${html.length}")
            val items = parseSearchItems(html)
            if (items.isEmpty()) return null
            val list = items.take(12).mapNotNull { it.toSearchResponse() }
            if (list.isEmpty()) null else newHomePageResponse(request.name, list)
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "getMainPage ERROR", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Same direct search — MAIN_HOST first, fallback to mainUrl. URL-encode the free text.
        return try {
            val html = fetchSearch(query)
            if (html == null) return null
            parseSearchItems(html).mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "search ERROR q=$query", e)
            null
        }
    }

    // The base used to build result links = the host that actually served the last search,
    // so a NartoEdgeProvider whose search fell back to MAIN_HOST still produces main-host links
    // (same slug, faster route) instead of forcing the slow edge mirror.
    private var searchBase: String = MAIN_HOST

    private fun SearchHit.toSearchResponse(): SearchResponse? {
        val u = url ?: return null
        val slug = Regex("""/detail/watch/([^/?]+)""").find(u)?.groupValues?.get(1) ?: return null
        val name = this.name ?: return null
        // Keep the "[مدبلج] ..." prefix so dubbed entries are obviously marked.
        val poster = image?.let { if (it.startsWith("http")) it else searchBase + it }
        return newTvSeriesSearchResponse(name, "$searchBase/detail/watch/$slug", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val slug = Regex("""/detail/watch/([^/?]+)""").find(url)?.groupValues?.get(1) ?: return null
            // The host the user actually opened (from the url we built) — use it for the detail page
            // AND episode links, so a NartoEdgeProvider item whose search fell back to MAIN_HOST
            // opens/stays on main (same slug, faster route) instead of bouncing to the slow mirror.
            val loadHost = Regex("""https://([^/]+)/detail/watch/""").find(url)?.groupValues?.get(1)
                ?.let { "https://$it" } ?: mainUrl
            // Use app.get(...).document (the SDK's own Jsoup-backed accessor) — importing Jsoup
            // directly to re-parse a text body is NOT reliably on the plugin classpath and made the
            // detail page fail after v8. Detail is a low-CF path — keep on the host the user chose.
            // edge occasionally returns TRANSIENT 502 on a detail page (verified live: same slug
            // 502 then 200 on retry). Retry a couple times so an intermittent bad-gateway doesn't
            // show the user a dead detail page — the retries stay on the SAME host.
            var doc: org.jsoup.nodes.Document? = null
            var attempt = 0
            while (attempt < 3 && doc == null) {
                attempt++
                try {
                    doc = app.get("$loadHost/detail/watch/$slug", referer = nartoOrigin, headers = mapOf("User-Agent" to UA), timeout = 20000L).document
                } catch (e: Exception) {
                    android.util.Log.e("NartoDrama", "load attempt=$attempt/3 slug=$slug error=${e.message?.take(80)}", e)
                    // Some slow slugs take 12-19s to build; wait a bit longer between retries so an
                    // intermittent 502/timeout (edge upstream) doesn't blank the whole detail page.
                    if (attempt < 3) {
                        try { Thread.sleep(1500) } catch (ie: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                }
            }
            if (doc == null) return null
            android.util.Log.e("NartoDrama", "load slug=$slug host=$loadHost fetchMs=${System.currentTimeMillis() - t0} eps=" + doc.select("div.episode-list a.episode-item").size)

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")

            var eps = doc.select("div.episode-list a.episode-item")
                .mapNotNull { el ->
                    val href = el.attr("href") ?: return@mapNotNull null
                    val ep = Regex("""/detail/watch/[^/]+/(\d+)""").find(href)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: return@mapNotNull null
                    newEpisode("$loadHost/detail/watch/$slug/$ep") {
                        episode = ep
                        name = "الحلقة $ep"
                    }
                }

            // Some works render their episode list client-side (JS) — especially single-"full episode"
            // properties rendered as one entry with no static a.episode-item anchors (e.g.
            // "نادي القلوب المكسورة: رومانسي كوميدي" showed eps=0). For those, the refresh-source API
            // still serves ep=1 with a playable source + all subtitles, so emit a single ep=1 rather
            // than an empty detail the player can't act on. (Multi-episode works keep their real list.)
            if (eps.isEmpty()) {
                android.util.Log.e("NartoDrama", "load slug=$slug no static episodes -> fallback single ep=1")
                eps = listOf(
                    newEpisode("$loadHost/detail/watch/$slug/1") {
                        episode = 1
                        name = "الحلقة"
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                posterUrl = poster
                plot = description
            }
        } catch (e: Exception) {
            null
        }
    }

    // Fetch the narto refresh-source payload for a canonical slug+episode.
    // Since the mirror split, MAIN (narto-drama.com) is the FAST and reliable playback host
    // (~1s, full payload, no CF on the API route), while edge's own refresh-source became slow
    // (12-41s — longer than the default client timeout, which surfaced as dead links). So the
    // playback fetch tries MAIN first (with a couple of resilience retries), then falls back to
    // EDGE with an extended 60s timeout so it still works when edge is merely slow, not down.
    // Whichever host answers, we parse the SAME EdgeResponse shape.
    //
    // v23: the endpoint ALSO returns a transient per-episode cooldown (both on MAIN and edge —
    //   they share the same server-side gate keyed by episode). Two message variants appear after a
    //   refresh just ran for the SAME episode:
    //     refresh_source_recently_failed   retry_after_seconds=45   after a failed refresh attempt
    //     refresh_source_cooldown_active   retry_after_seconds=20   on further taps while cooling
    //   Verified live: first tap of a fresh episode -> 200; a 2nd tap seconds later -> 429 cooldown.
    //   In v22 this was the "BOTH sources broken" bug: opening the same episode in the two sources
    //   (or tapping twice) hit the cooldown, and the provider treated the ok:false body as a
    //   SUCCESS, hard-bailed in loadLinks, and surfaced no links. Now both cooldown messages are
    //   RETRYABLE failures: we wait the endpoint's own retry_after_seconds (capped so a single
    //   tap never blocks >~12s) and try again; only a persistent gate falls through to the other host.
    private suspend fun fetchRefresh(slug: String, ep: String, base: String): EdgeResponse? {
        var waited = false
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                val body = app.get(
                    "$base/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx",
                    referer = nartoOrigin,
                    timeout = 60000L
                ).text
                val edge = mapper.readValue(body, EdgeResponse::class.java)
                // A cooldown (either variant) is NOT a usable response — it has no play_url and is
                // keyed per-episode, so wait the endpoint's own window (bounded) and retry it once.
                if (edge.ok != true && (edge.message == "refresh_source_recently_failed" || edge.message == "refresh_source_cooldown_active")) {
                    if (waited) {
                        android.util.Log.e("NartoDrama", "fetchRefresh COOLDOWN persists host=$base slug=$slug ep=$ep retryAfter=${edge.retryAfterSeconds}")
                        return null
                    }
                    waited = true
                    val waitMs = ((edge.retryAfterSeconds ?: 15).coerceIn(4, 12)) * 1000L
                    android.util.Log.e("NartoDrama", "fetchRefresh COOLDOWN host=$base slug=$slug ep=$ep waiting=${waitMs}ms")
                    try { Thread.sleep(waitMs) } catch (e2: InterruptedException) { Thread.currentThread().interrupt() }
                    continue
                }
                // Otherwise a successful read (including ok!=true with other non-contagious
                // messages) is final — the caller decides whether it's usable.
                return edge
            } catch (e: Exception) {
                android.util.Log.e("NartoDrama", "fetchRefresh ERROR attempt=$attempt/2 host=$base slug=$slug ep=$ep", e)
                if (attempt < 2) {
                    try { Thread.sleep(800) } catch (e2: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
        }
        return null
    }

    private suspend fun loadRefresh(slug: String, ep: String): EdgeResponse? {
        // Try this provider's OWN mainUrl first (each source knows its fast host), then the other.
        val self = fetchRefresh(slug, ep, mainUrl)
        if (self != null) return self
        val other = if (mainUrl.contains("edge")) MAIN_HOST else "https://edge.narto-drama.com"
        android.util.Log.e("NartoDrama", "fetchRefresh $mainUrl dead -> fallback $other slug=$slug ep=$ep")
        val otherEdge = fetchRefresh(slug, ep, other)
        if (otherEdge != null) return otherEdge
        // Both hosts exhausted (each waited its own bounded cooldown once) — a final short sweep
        // of the fast host only, so a just-cleared per-episode gate still yields links without
        // blocking much longer.
        android.util.Log.e("NartoDrama", "fetchRefresh both hosts exhausted -> final sweep slug=$slug ep=$ep")
        try { Thread.sleep(1200) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        return fetchRefresh(slug, ep, mainUrl)
    }

    // v25: The SITE's own player feeds from /detail/watch/{slug}/{ep} -> `episodeItemsRaw`, which is
    // the ONLY place that carries EVERY quality you see on the site FOR THE CURRENT EPISODE:
    //   - direct_play_url   = the LIVE source the site actually plays (stream-e1.narto-drama.com
    //                          /e/m/{jwt} -> its src is the signed shortmax-stream token that returns
    //                          200 + real HLS segments — verified live; NOT the dead stale token that
    //                          refresh-source returns).
    //   - multi_resolutions = the labelled per-quality set (1080p/720p/480p for shortmax, 1080p/720p
    //                          for moboreels) — the SAME list the site's quality selector shows.
    // refresh-source, by contrast, returns stale/expired tokens (410) and multi_resolutions=0, which is
    // why only ONE quality showed. So v25 reads the player page and emits ALL of the site's qualities.
    // v26: the player page also carries the FULL subtitle & audio set (multi_subtitles,
    // direct_subtitle_url, direct_audio_url) which refresh-source sometimes omits — collect it too so
    // nothing the site offers is dropped.
    private data class EpisodeItemData(
        val liveDu: String?,
        val qualities: List<Triple<String, String, String>>, // url, label, quality
        val subs: List<Pair<String, String>>,   // lang -> resolved URL
        val audio: List<Pair<String, String>>,  // lang -> resolved URL
    )

    private suspend fun episodeItemLinks(slug: String, ep: String, pageBase: String): EpisodeItemData {
        val empty = EpisodeItemData(null, emptyList(), emptyList(), emptyList())
        return try {
            // Player page is stable on the MAIN host (~200, ~1MB). Intermittent 502 (edge upstream)
            // — retry with a short backoff like load() does so a bad-gateway doesn't blank qualities.
            var body: String? = null
            var attempt = 0
            while (attempt < 3 && body == null) {
                attempt++
                try {
                    body = app.get(
                        "$pageBase/detail/watch/$slug/$ep",
                        referer = nartoOrigin,
                        headers = mapOf("User-Agent" to UA),
                        timeout = 30000L
                    ).text
                } catch (e: Exception) {
                    if (attempt < 3) {
                        try { Thread.sleep(1200) } catch (ie: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                }
            }
            if (body == null) { android.util.Log.e("NartoDrama", "episodeItemLinks page failed slug=$slug ep=$ep"); return empty }
            val marker = "episodeItemsRaw"
            val idx = body.indexOf(marker)
            if (idx < 0) return empty
            val arrStart = body.indexOf('[', idx)
            if (arrStart < 0) return empty
            // Balance braces/[ ] respecting strings so escaped \/ and nested quotes don't break it.
            var depth = 0
            var arrEnd = -1
            var inStr = false
            for (k in arrStart until body.length) {
                val c = body[k]
                if (inStr) {
                    if (c == '\\') continue
                    if (c == '"') inStr = false
                } else when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { arrEnd = k; break } }
                }
            }
            if (arrEnd < 0) return empty
            val arrJson = body.substring(arrStart, arrEnd + 1).replace("\\/", "/")
            val arr = mapper.readValue(arrJson, List::class.java)
            val entries = (arr as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: return empty
            val entry = entries.firstOrNull { it["route_episode_number"]?.toString() == ep || it["number"]?.toString() == ep }
                ?: entries.firstOrNull()
                ?: return empty

            // 1) the LIVE play URL (stream-e1/e/m/{jwt} whose src is a real signed master).
            var liveDu: String? = null
            val du = entry["direct_play_url"] as? String
            if (!du.isNullOrBlank() && du.contains("/e/m/")) liveDu = du

            // 2) every labelled quality straight from multi_resolutions (exactly what the site shows).
            val out = mutableListOf<Triple<String, String, String>>()
            val mrs = entry["multi_resolutions"] as? List<*>
            for (mr in mrs.orEmpty()) {
                val m = mr as? Map<*, *> ?: continue
                val url = m["stream_url"] as? String ?: continue
                if (url.isBlank()) continue
                val label = m["label"] as? String
                val res = m["resolution"]?.toString()
                val q = when (res) { "1080" -> "1080p"; "720" -> "720p"; "540" -> "540p"; else -> (label ?: res ?: "480p") }
                if (out.none { it.first == url }) out.add(Triple(url, label ?: q, q))
            }
            // 3) EVERY subtitle/dub the page offers (multi_subtitles + direct_subtitle_url +
            //    direct_audio_url) — resolve relative /e/s/{jwt} & /e/a/{jwt} against stream host.
            val subs = mutableListOf<Pair<String, String>>()
            val audio = mutableListOf<Pair<String, String>>()
            (entry["multi_subtitles"] as? List<*>)?.forEach { ms ->
                val mm = ms as? Map<*, *> ?: return@forEach
                val url = mm["subtitle_url"] as? String ?: return@forEach
                if (url.isBlank()) return@forEach
                val lang = (mm["label"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (mm["language_code"] as? String)?.takeIf { it.isNotBlank() }
                    ?: "ترجمة"
                subs.add(lang to (if (url.startsWith("http")) url else STREAM_HOST + url))
            }
            val dSub = entry["direct_subtitle_url"] as? String
            if (!dSub.isNullOrBlank() && !dSub.contains("undefined"))
                subs.add("ترجمة مباشرة" to (if (dSub.startsWith("http")) dSub else STREAM_HOST + dSub))
            val dAud = entry["direct_audio_url"] as? String
            if (!dAud.isNullOrBlank() && !dAud.contains("undefined"))
                audio.add("صوت" to (if (dAud.startsWith("http")) dAud else STREAM_HOST + dAud))

            android.util.Log.e("NartoDrama", "episodeItemLinks slug=$slug ep=$ep liveDu=${liveDu?.isNotBlank() == true} mrs=${out.size} subs=${subs.size} aud=${audio.size}")
            EpisodeItemData(liveDu, out, subs, audio)
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "episodeItemLinks ERROR slug=$slug ep=$ep", e)
            empty
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // data = $loadHost/detail/watch/{slug}/{ep} — derive the host the user opened so the player page
            // fetch (episodeItemLinks) and any emitted links stay on that same host's route.
            val m = Regex("""/detail/watch/([^/?]+)/(\d+)""").find(data) ?: return false
            val ep = m.groupValues[2]
            var slug = m.groupValues[1]
            val loadHost = Regex("""https://([^/]+)/detail/watch/""").find(data)?.groupValues?.get(1)
                ?.let { "https://$it" } ?: mainUrl

            var edge = loadRefresh(slug, ep)
            if (edge == null) {
                android.util.Log.e("NartoDrama", "loadLinks NO EDGE (all mirrors failed or JSON parse error) slug=$slug ep=$ep")
                return false
            }

            // Explicit transient outages — surface a readable failure instead of a useless empty list.
            // (Driven by the API's own message string, not by guessing.) stream_temporarily_unavailable
            // is a real upstream outage; the two per-episode cooldown variants are handled here too
            // as an extra safety net (loadRefresh already spins on them, but if one ever slips through
            // with no links, bail cleanly rather than emit garbage).
            if (edge.ok != true && setOf(
                    "stream_temporarily_unavailable",
                    "refresh_source_recently_failed",
                    "refresh_source_cooldown_active"
                ).contains(edge.message)
            ) return false

            // Canonical re-discovery: a requested slug may differ from the narto canonical one.
            if (edge.ok != true && edge.message == "slug_mismatch") {
                val canon = edge.canonical?.let { Regex("""/detail/watch/([^/?]+)/""").find(it)?.groupValues?.get(1) }
                if (canon != null && canon != slug) {
                    android.util.Log.e("NartoDrama", "loadLinks slug_mismatch $slug -> $canon ep=$ep")
                    slug = canon
                    edge = loadRefresh(slug, ep)
                    if (edge == null) return false
                }
            }
            // Do NOT hard-bail on `ok != true` here — it is often ABSENT or typed as an int in the
            // JSON, and Jackson leaves the Kotlin `Boolean?` null in that case (`null != true` would
            // have dropped EVERY work -> "لم يتم العثور على روابط"). Only the two explicit messages
            // above abort; otherwise we parse whatever play_url/direct_play_url/multi_resolutions the
            // API returned. Log the actual edge state so real failures are visible in logcat.
            if (edge.ok != true) {
                android.util.Log.e("NartoDrama", "loadLinks edge.ok!=true (continuing anyway) slug=$slug ep=$ep msg=${edge.message} play=${edge.directPlayUrl?.take(60)} res=${edge.multiResolutions?.size}")
            }

            // 1) subtitles — resolve relative /e/s/{jwt} paths on stream.narto-drama.com
            //    (narto-drama.com itself is CF-blocked). Emit EVERY subtitle the API offers:
            //    the full multi_subtitles set plus any single track (subtitle_url /
            //    direct_subtitle_url) that appears alone instead. Nothing the site returns is dropped.
            val seenSubs = LinkedHashSet<String>()
            val subTracks = buildList {
                edge.multiSubtitles.orEmpty().forEach { s ->
                    val rel = s.subtitleUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                    val lang = s.label?.takeIf { it.isNotBlank() } ?: s.languageCode ?: "ترجمة"
                    add(lang to rel)
                }
                // Single external subtitle (may be absolute http or relative /e/s/...).
                edge.subtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                    add((edge.selectedSubtitleLanguage?.takeIf { l -> l.isNotBlank() } ?: "ترجمة") to it)
                }
                // Single direct subtitle (absolute or relative).
                edge.directSubtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                    add("ترجمة مباشرة" to it)
                }
            }
            for ((lang, rel) in subTracks) {
                val subUrl = if (rel.startsWith("http")) rel else STREAM_HOST + rel
                if (!seenSubs.add(subUrl)) continue
                try { subtitleCallback(newSubtitleFile(lang, subUrl)) } catch (e: Exception) {}
            }

            // 2) the video — Narto aggregates from many backends, so a work can carry:
            //      a) a single play_url / direct_play_url (HLS master/media, or a direct MP4 file)
            //      b) multi_resolutions -> the per-resolution stream_url set (1080/720/480)
            // The RELIABLE links are the narto-LOCAL proxy URLs (play_url / direct_play_url — the
            // stream-e1 /e/m/{jwt} or cdn.narto-drama.com MP4 proxies the site's own player uses).
            // The multi_resolutions URLs are direct backend token links (shortmax-stream, hakuna,
            // netshort...) whose signed tokens expire in MINUTES — by the time the user taps play
            // they return HTTP 410 (verified live on 2 dubbed slugs: ALL 3 stream_urls are 410
            // while play+direct are 200). So they MUST NOT be the first thing the player selects.
            // Strategy (matches "نفس الموقع"): emit the working proxy links FIRST as the primary
            // source, then offer multi_resolutions only as labeled fallbacks. If every proxy fails
            // the user can still try a fresh multi_resolutions token — one that was just refreshed.
            val emitted = LinkedHashSet<String>()
            var any = false
            var skippedDead = 0

            suspend fun emit(u: String, label: String, q: String) {
                if (u.isBlank() || !emitted.add(u)) return
                // Skip playback links from DEAD backend hosts (DNS NODATA) — emitting them just
                // makes the player pick a host that doesn't resolve and fail playback.
                val host = u.substringAfter("//").substringBefore("/").substringBefore(":").lowercase()
                if (DEAD_HOST_PATTERNS.any { host.contains(it) }) {
                    skippedDead++
                    android.util.Log.e("NartoDrama", "emit SKIP dead host $host ($label)")
                    return
                }
                val type = inferStreamType(u)
                callback(
                    newExtractorLink(source = name, name = label, url = u, type = type) {
                        referer = nartoOrigin
                        quality = getQualityFromName(q)
                        headers = mapOf("Referer" to nartoOrigin)
                    }
                )
                any = true
            }

            // v25: removed the per-token liveness probe (2.5-3.5s delay, and tokens often 403/410
            // anyway). The player page's quality list is authoritative and its direct_play_url is the
            // live source; probing was the "جودات بطيئة" delay.

            // ---- Reconstruct ALL qualities from the play_url JWT ----
            // The stream-e1 /e/m/{jwt} proxy is single-quality (_480), and the multi_resolutions
            // tokens from this API are already-expired 410s. BUT the JWT payload's `src` field
            // points at the real signed CDN master (e.g. https://volcengine-forward.shorttv.live/
            // hls/{id}_480/main.m3u8?auth_key=...) whose auth_key is quality-agnostic — verified
            // LIVE that {id}_480, {id}_720 AND {id}_1080 all return a working HLS (200, with
            // real deliverable segments). So we rebuild a fresh quality set from src+auth_key.
            fun allQualities(u: String?): List<Triple<String, String, String>> {
                // url, label, quality
                val seg = u ?: return emptyList()
                return try {
                    val jwt = seg.substringAfter("/e/m/").substringBefore("?")
                    val parts = jwt.split(".")
                    if (parts.size < 2) return emptyList()
                    val b64 = parts[0].filter { it != '=' }
                    val pad = "=".repeat((4 - b64.length % 4) % 4)
                    val raw = java.util.Base64.getUrlDecoder().decode(b64 + pad)
                    val map = mapper.readValue(String(raw, Charsets.UTF_8), Map::class.java)
                    val src = map["src"] as? String ?: return emptyList()
                    // src = https://{cdn}/hls/{id}_480/main.m3u8?auth_key=...
                    val m = Regex("""https://[^/]+(/.+?)_\d{3,4}(/main\.m3u8\?auth_key=[^"]*)""").find(src) ?: return emptyList()
                    val cdn = Regex("""https://([^/]+)/""").find(src)?.groupValues?.get(1) ?: return emptyList()
                    val out = mutableListOf<Triple<String, String, String>>()
                    // Prefer the top quality first so the player picks the best.
                    for ((suffix, label) in listOf("_1080" to "1080p", "_720" to "720p", "_480" to "480p")) {
                        out.add(Triple("https://$cdn${m.groupValues[1]}$suffix${m.groupValues[2]}", label, label))
                    }
                    out
                } catch (e: Exception) {
                    emptyList()
                }
            }

            // v25: NO network probing here. The player page (episodeItemLinks) already gives us the
            // SITE's per-quality list with their real URLs — probing each token (2.5-3.5s, and tokens
            // often 403/410 anyway) was the "يتاخر بعرض الجودات" delay. We keep a lightweight,
            // non-probing fallback set from refresh's multi_resolutions for when the page fetch fails.
            val resolutions = edge.multiResolutions.orEmpty()
                .filter { !it.streamUrl.isNullOrBlank() }
            val liveOnes = mutableListOf<Triple<String, String, String>>() // url, label, quality
            if (resolutions.isNotEmpty()) {
                val byUrl = linkedMapOf<String, MutableList<String>>()
                for (r in resolutions) {
                    byUrl.getOrPut(r.streamUrl!!) { mutableListOf() }
                        .add(r.label ?: "${r.resolution ?: 480}p")
                }
                for ((u, labels) in byUrl) {
                    val res = resolutions.firstOrNull { it.streamUrl == u }?.resolution
                    val q = when (res) {
                        1080 -> "1080p"
                        720 -> "720p"
                        540 -> "540p"
                        else -> "480p"
                    }
                    val label = if (labels.size == 1) labels[0] else labels.joinToString("/")
                    liveOnes.add(Triple(u, label, q))
                }
            }
            // True resolution of the stream-e1 proxy URI, decoded from the JWT src field it wraps.
            // A signed URL keeps its quality as a `_1080/_720/_480` folder under .m3u8 — so the
            // label can show the REAL quality instead of a made-up "1080p" (the whole episode is
            // often a single 480p master — pretending higher causes a "no such quality" tap).
            fun proxyQuality(u: String?): String {
                val s = u ?: return "480p"
                val seg = s.trim().trimEnd('=').substringAfterLast('.')
                val dec = try {
                    java.net.URLDecoder.decode(seg, "UTF-8")
                } catch (e: Exception) { seg }
                val q = Regex("""_(\d{3,4})p""").find(dec)?.groupValues?.get(1)
                return if (q == null) "480p" else "${q}p"
            }
            val proxyQ = proxyQuality(edge.directPlayUrl)

            // v26 emit order (matches what the user verified PLAYING on device): the site's real
            // quality set from the player page — multi_resolutions FIRST as the DEFAULT (1080p/720p/
            // 480p; user-confirmed these play on device), then the live "كامل" direct_play_url as a
            // fallback. In v25 the live du was emitted FIRST and Source-errored on device while the
            // multi-res links played — hence the swap. multi_resolutions tokens can expire minutes
            // after the page fetch (exactly like on the site), so the live du fallback sits behind.
            // The player page fetch is the ONLY slow-ish step (~1-6s) — required to match the site's
            // qualities. It runs on every episode tap; no probing delay is added.
            val page = episodeItemLinks(slug, ep, loadHost)

            // 1) DEFAULT — EVERY labelled quality the site's selector shows (multi_resolutions).
            for ((u, label, q) in page.qualities) emit(u, label, q)

            // 2) The LIVE direct_play_url (stream-e1/e/m/{jwt} — the exact source the site's player
            //    runs, fresh at fetch time) as the fallback behind the device-verified qualities.
            if (page.liveDu != null) {
                emit(page.liveDu, "كامل", proxyQ)
            }

            // 3) Merge the player page's OWN subtitle set (multi_subtitles + direct_subtitle_url) —
            //    the page sometimes lists tracks refresh-source misses ("ملفات الترجمة بالكامل").
            //    lang -> URL is already resolved; dedupe against refresh's seenSubs by URL.
            for ((lang, rel) in page.subs) {
                val subUrl = if (rel.startsWith("http")) rel else STREAM_HOST + rel
                if (!seenSubs.add(subUrl)) continue
                try { subtitleCallback(newSubtitleFile(lang, subUrl)) } catch (e: Exception) {}
            }

            // Fallback: the refresh-source proxy links (in case the page fetch failed), deduped.
            if (page.qualities.isEmpty() && page.liveDu == null) {
                val rebuilt = allQualities(edge.directPlayUrl)
                for ((u, label, q) in rebuilt) emit(u, label, q)
                for ((u, label, q) in liveOnes) emit(u, label, q)
                var first = true
                for (u in listOfNotNull(edge.playUrl, edge.directPlayUrl).distinct()) {
                    emit(u, if (first) "كامل" else "رابط مباشر", proxyQ)
                    first = false
                }
            } else {
                // Also keep a single "رابط مباشر" refresh proxy as a manual fallback for when the
                // primary token expires before the user taps (fresh refresh-source token may serve).
                var first = true
                for (u in listOfNotNull(edge.playUrl, edge.directPlayUrl).distinct()) {
                    emit(u, if (first) "كامل" else "رابط مباشر", proxyQ)
                    first = false
                }
            }
            // Last resort: if the player page offered nothing AND refresh had no proxy, surface
            // the refresh multi-res links raw so the user can at least try a fresh token.
            if (emitted.isEmpty() && liveOnes.isEmpty() && edge.playUrl.isNullOrBlank() && edge.directPlayUrl.isNullOrBlank()) {
                for ((u, label, q) in liveOnes) emit(u, label, q)
                android.util.Log.e("NartoDrama", "loadLinks only refresh multi-res available, surfacing anyway slug=$slug")
            }

            // 3) AUDIO via TRACKS, not links (the user asked for an audio switcher "مثل الترجمة" and
            //    explicitly rejected any standalone audio that plays without video). The video links
            //    above ARE the HLS masters; when a master carries #EXT-X-MEDIA:TYPE=AUDIO groups,
            //    ExoPlayer populates the native audio-track switcher (مسارات الصوت) — picking a
            //    language there keeps the video rolling, exactly what the user wants. That is the ONLY
            //    correct path here: probing this backend (shortmax) shows no separate audio — the
            //    tracks live inside the master. So we emit NO standalone "صوت منفصل" link and NO
            //    "صوت:" group link; the player's audio selector lists them when the chosen master plays.

            android.util.Log.e("NartoDrama", "loadLinks DONE slug=$slug ep=$ep links=${emitted.size} subs=${subTracks.size} deadSkipped=$skippedDead any=$any")
            any
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "loadLinks FATAL", e)
            false
        }
    }
}

// ---- The two Narto sources the user asked for ----
//   1) "Edge Narto Drama" — the open (no-CF) mirror, DEFAULT for browsing the native Arabic catalog.
//   2) "Narto Drama" — the main domain, the reliable host for playback refresh.
// Both are separate MainAPI providers registered from NartoDramaPlugin.load(); both share the
// logic in NartoBaseProvider. Each keeps a fixed mainUrl — no auto-failover between them.
class NartoEdgeProvider(
    initialMainUrl: String = "https://edge.narto-drama.com"
) : NartoBaseProvider() {
    override var name = "Edge Narto Drama"
    override var mainUrl = initialMainUrl
}

class NartoMainProvider(
    initialMainUrl: String = "https://narto-drama.com"
) : NartoBaseProvider() {
    override var name = "Narto Drama"
    override var mainUrl = initialMainUrl
}
