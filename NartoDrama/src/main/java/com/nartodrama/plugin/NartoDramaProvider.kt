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
// queries return DIFFERENT 24-item feeds, 0 overlap — so these are real categories, not the
// same list repackaged). The empty query (q='') is the site's "recommendations" feed but it
// intermittently returns HTTP 502 from edge, so it is NOT used. Each tab lazily fetches ONE
// query only when opened (keeps browsing light — one ~400KB fetch per section).
    override val mainPage = mainPageOf(
        "دراما" to "🎬 دراما",
        "مدبلج" to "🎙️ مدبلج",
        "رومانسي" to "💕 رومانسي",
        "أكشن" to "⚔️ أكشن",
        "كوميدي" to "😄 كوميدي",
        "جريمة" to "🕵️ جريمة",
        "تركي" to "🇹🇷 تركي",
        "كوري" to "🇰🇷 كوري",
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Browse hits the OPEN mirror (edge) DIRECTLY — NOT through tryMirrors. The CF-protected
        // narto-drama.com fallback in tryMirrors made the whole page come back EMPTY on device when
        // edge was slow/blank (the fallback then also got challenged -> null). This exact direct form
        // is what showed the main page reliably in v6/v7. request.data is the tab's query string.
        val t0 = System.currentTimeMillis()
        return try {
            val q = request.data.trim()
            val html = app.get("$mainUrl/search?lang=ar-SA&q=$q", referer = nartoOrigin, headers = mapOf("User-Agent" to UA)).text
            val t1 = System.currentTimeMillis()
            android.util.Log.e("NartoDrama", "getMainPage q=$q fetchMs=${t1 - t0} len=${html.length} item=" + html.contains("\"@type\":\"ListItem\""))
            val items = parseSearchItems(html)
            if (items.isEmpty()) return null
            val list = items.mapNotNull { it.toSearchResponse() }
            if (list.isEmpty()) null else newHomePageResponse(request.name, list)
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "getMainPage ERROR", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Direct to the OPEN mirror (edge), like getMainPage — not via the CF-prone tryMirrors
        // fallback that could empty results on device. URL-encode the user's free-text query.
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val html = app.get("$mainUrl/search?lang=ar-SA&q=$q", referer = nartoOrigin, headers = mapOf("User-Agent" to UA)).text
            val items = parseSearchItems(html)
            items.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "search ERROR q=$query", e)
            null
        }
    }

    private fun SearchHit.toSearchResponse(): SearchResponse? {
        val u = url ?: return null
        val slug = Regex("""/detail/watch/([^/?]+)""").find(u)?.groupValues?.get(1) ?: return null
        val name = this.name ?: return null
        // Keep the "[مدبلج] ..." prefix so dubbed entries are obviously marked.
        val poster = image?.let { if (it.startsWith("http")) it else mainUrl + it }
        return newTvSeriesSearchResponse(name, "$mainUrl/detail/watch/$slug", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val slug = Regex("""/detail/watch/([^/?]+)""").find(url)?.groupValues?.get(1) ?: return null
            // Use app.get(...).document (the SDK's own Jsoup-backed accessor) — importing Jsoup
            // directly to re-parse a text body is NOT reliably on the plugin classpath and made the
            // detail page fail after v8. Detail is a low-CF path; keep it on the direct mainUrl
            // form like v7. (Single-domain now — no failover anywhere.)
            // edge occasionally returns TRANSIENT 502 on a detail page (verified live: same slug
            // 502 then 200 on retry). Retry ONCE so an intermittent bad-gateway doesn't show the
            // user a dead detail page — the retry is on the SAME domain, ~1s apart.
            var doc: org.jsoup.nodes.Document? = null
            var attempt = 0
            while (attempt < 3 && doc == null) {
                attempt++
                try {
                    doc = app.get("$mainUrl/detail/watch/$slug", referer = nartoOrigin, headers = mapOf("User-Agent" to UA), timeout = 20000L).document
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
            android.util.Log.e("NartoDrama", "load slug=$slug fetchMs=${System.currentTimeMillis() - t0} eps=" + doc.select("div.episode-list a.episode-item").size)

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
                    newEpisode("$mainUrl/detail/watch/$slug/$ep") {
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
                    newEpisode("$mainUrl/detail/watch/$slug/1") {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // data = $mainUrl/detail/watch/{slug}/{ep}
            val m = Regex("""/detail/watch/([^/?]+)/(\d+)""").find(data) ?: return false
            val ep = m.groupValues[2]
            var slug = m.groupValues[1]

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

            // Quick liveness probe: a backend token link may be dead on arrival (HTTP 410/403
            // expired), and listing it just makes the player pick a link that errors. Do a fast
            // HEAD (falling back to a range GET) with a tight timeout; treat 2xx/3xx as live.
            fun isLive(u: String): Boolean {
                return try {
                    var code = -1
                    var conn: java.net.HttpURLConnection? = null
                    try {
                        conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 2500
                        conn.readTimeout = 2500
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", UA)
                        conn.setRequestProperty("Referer", nartoOrigin)
                        // Many token servers reject HEAD; send a range GET and just read the status
                        // + first byte, then abort — cheapest way to distinguish live vs expired.
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("Range", "bytes=0-1")
                        code = conn.responseCode
                        if (code in 200..399) {
                            try { conn.inputStream.read() } catch (e: Exception) {}
                        }
                    } finally {
                        conn?.disconnect()
                    }
                    code in 200..399
                } catch (e: Exception) { false }
            }

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

            // Group the per-quality token links, dedupe by URL (one master may back several labels).
            val resolutions = edge.multiResolutions.orEmpty()
                .filter { !it.streamUrl.isNullOrBlank() }
            val distinctUrls = resolutions.map { it.streamUrl!! }.distinct()

            // Probe every distinct quality link concurrently (parallel threads, ~2.5s timeout each)
            // so the WORKING qualities surface FIRST and the dead 410/403 tokens are dropped —
            // matching "اجعل الجودات المتعدده الذي تعمل اول خيار تشغيلي". This adds at most ~2.5s
            // (single wall-clock wait) because the probes run in parallel, not serially.
            val liveFlags = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            if (distinctUrls.isNotEmpty()) {
                val threads = distinctUrls.map { u ->
                    Thread { liveFlags[u] = isLive(u) }
                }
                threads.forEach { it.start() }
                threads.forEach { it.join(3500) }
            }

            // WORKING quality links FIRST, at their true quality.
            val liveOnes = mutableListOf<Triple<String, String, String>>() // url, label, quality
            val deadOnes = mutableListOf<Triple<String, String, String>>()
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
                    (if (liveFlags[u] == true) liveOnes else deadOnes).add(Triple(u, label, q))
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

            // Emit order = what the user asked: the WORKING multi-quality links FIRST. The
            // RECONSTRUCTED 3-quality set (1080/720/480 from the JWT src+auth_key — the same real
            // CDN masters the site's player uses) is the primary source, then the working
            // multi-res tokens, then the local proxy (كامل/رابط مباشر) as fallback.
            // Dead 410/403 tokens are NOT emitted (they'd just be dead first taps).
            val rebuilt = allQualities(edge.directPlayUrl)
            for ((u, label, q) in rebuilt) emit(u, label, q)
            for ((u, label, q) in liveOnes.filter { t -> rebuilt.none { it.first == t.first } }) emit(u, label, q)
            var first = true
            for (u in listOfNotNull(edge.playUrl, edge.directPlayUrl).distinct()) {
                emit(u, if (first) "كامل" else "رابط مباشر", proxyQ)
                first = false
            }
            // Last resort only: if every quality is dead AND there's no rebuilt set AND no proxy, still
            // surface the multi-res links so the user can try a manually-refreshed token.
            if (rebuilt.isEmpty() && liveOnes.isEmpty() && edge.playUrl.isNullOrBlank() && edge.directPlayUrl.isNullOrBlank()) {
                for ((u, label, q) in deadOnes) emit(u, label, q)
                android.util.Log.e("NartoDrama", "loadLinks only dead multi-res available (all 410/403), surfacing anyway slug=$slug")
            } else if (deadOnes.isNotEmpty()) {
                android.util.Log.e("NartoDrama", "loadLinks dropped ${deadOnes.size} dead quality links slug=$slug")
            }

            // 3) MULTI-AUDIO as TRACKS, not links. The user wants the site's multiple audio tracks to
            //    surface as AUDIO TRACKS in the player (مسارات الصوت) — NOT as separate audio links.
            //    The video links above ARE the HLS masters; their #EXT-X-MEDIA:TYPE=AUDIO groups make
            //    ExoPlayer populate the audio-track selector automatically. So we emit NO standalone
            //    "صوت منفصل" link and NO separate "صوت:" group links — the player's native audio-track
            //    switcher lists every audio (ar-SA / stream_1 / ...) when the chosen master is played.

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
    override var name = "narto drama"
    override var mainUrl = initialMainUrl
}
