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

// Standalone "Narto Drama" extension — pinned to https://narto-drama.com ONLY.
// Build from scratch (v1) as one concrete class; 100% independent from the separate
// "Edge Narto Drama" extension (own module, own cache, own refresh channel, own cooldown
// handling). No shared base class with the other source.
private const val NARTO_HOST = "https://narto-drama.com"
private const val STREAM_HOST = "https://stream.narto-drama.com"

// Backend hosts that are dead (DNS NODATA / non-existent domain) and must NOT be emitted as
// playback links — the player would select them and fail.
private val DEAD_HOST_PATTERNS = listOf("montagehub")

// One JSON-LD ListItem entry from the search results page.
private data class SearchHit(
    @JsonProperty("@type") val type: String? = null,
    val url: String? = null,    // https://narto-drama.com/detail/watch/{slug}?lang=...
    val name: String? = null,   // Arabic title (e.g. "[مدبلج] ...")
    val image: String? = null,  // /assets/poster/{id}.jpg
)

// Narto playback API — Narto aggregates short-drama from MANY backends (shortmax, NetShort,
// StardustTV, mydramawave, ...). Each work's direct_play_url/play_url/multi_resolutions may
// be an HLS playlist OR a direct MP4 file — so loadLinks detects the container per link.
private data class NartoResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val canonical: String? = null,          // full canonical URL hint on slug_mismatch
    @JsonProperty("retry_after_seconds") val retryAfterSeconds: Int? = null, // 429 cooldown
    @JsonProperty("direct_play_url") val directPlayUrl: String? = null,
    @JsonProperty("play_url") val playUrl: String? = null,
    @JsonProperty("multi_resolutions") val multiResolutions: List<NartoResolution>? = null,
    @JsonProperty("multi_subtitles") val multiSubtitles: List<NartoSub>? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,
    @JsonProperty("direct_subtitle_url") val directSubtitleUrl: String? = null,
    @JsonProperty("selected_subtitle_language") val selectedSubtitleLanguage: String? = null,
)

private data class NartoResolution(
    val resolution: Int? = null,
    val label: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
)

private data class NartoSub(
    @JsonProperty("language_code") val languageCode: String? = null,
    val label: String? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,     // relative /e/s/{jwt}
)

// Minimal fake JWT the API accepts (claims are not verified, slug/ep read from path).
private val fakeRsCtx = "eyJhbGciOiJub25lIn0.eyJ2IjoiMSJ9."

class NartoDramaProvider : MainAPI() {
    override var name = "Narto Drama"
    override var mainUrl = NARTO_HOST
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Main screen = one section per tab, each a distinct Arabic query (verified live: different
    // queries return DIFFERENT feeds, 0 overlap). Each tab fetches ONE query when opened.
    // Capped to 12 so first paint is light.
    override val mainPage = mainPageOf(
        "دراما" to "🎬 دراما",
        "مدبلج" to "🎙️ مدبلج",
        "رومانسي" to "💕 رومانسي",
        "أكشن" to "⚔️ أكشن",
    )

    // Referer for all requests/links. This is just an HTTP Referer header the narto stream/
    // subtitle servers expect; it does NOT merge this source with the Edge extension.
    private val nartoOrigin = "https://narto-drama.com"

    // Per-tab in-memory cache so re-entering / tab-hopping serves the list instantly instead of
    // re-fetching the heavy /search page.
    private val searchCache = HashMap<String, String>()

    // Detect whether a stream URL is HLS or a direct video file. URL-based, no network probe.
    private fun inferStreamType(url: String): ExtractorLinkType {
        val lower = url.lowercase()
        if (lower.contains("mime_type=video_mp4") || lower.contains(".mp4") || lower.endsWith(".m4v"))
            return ExtractorLinkType.VIDEO
        return ExtractorLinkType.M3U8
    }

    // ---- Parse the ListItem JSON array embedded in a /search HTML page ----
    private fun parseSearchItems(html: String): List<SearchHit> {
        return try {
            val marker = "\"@type\":\"ListItem\""
            val start = html.indexOf(marker)
            if (start < 0) return emptyList()
            var arrStart = start
            while (arrStart > 0 && html[arrStart] != '[') arrStart--
            if (html[arrStart] != '[') return emptyList()
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

    private suspend fun fetchSearch(q: String): String? {
        searchCache[q]?.let { return it }
        val urlEncQ = java.net.URLEncoder.encode(q, "UTF-8")
        try {
            val html = app.get("$mainUrl/search?lang=ar-SA&q=$urlEncQ&page=1&perPage=12", referer = nartoOrigin, headers = mapOf("User-Agent" to UA)).text
            if (html.contains("\"@type\":\"ListItem\"")) {
                searchCache[q] = html
                return html
            }
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "search fetch error", e)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
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
        return try {
            val html = fetchSearch(query)
            if (html == null) return null
            parseSearchItems(html).mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "search ERROR q=$query", e)
            null
        }
    }

    private fun SearchHit.toSearchResponse(): SearchResponse? {
        val u = url ?: return null
        val slug = Regex("""/detail/watch/([^/?]+)""").find(u)?.groupValues?.get(1) ?: return null
        val name = this.name ?: return null
        val poster = image?.let { if (it.startsWith("http")) it else mainUrl + it }
        return newTvSeriesSearchResponse(name, "$mainUrl/detail/watch/$slug", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val slug = Regex("""/detail/watch/([^/?]+)""").find(url)?.groupValues?.get(1) ?: return null
            val loadHost = Regex("""https://([^/]+)/detail/watch/""").find(url)?.groupValues?.get(1)
                ?.let { "https://$it" } ?: mainUrl
            var doc: org.jsoup.nodes.Document? = null
            var attempt = 0
            // v31 (perf): no retry-sleep punishment. The detail page is server-bound: the HTML
            // build takes 12-19s on slow slugs, so a generous first timeout gets a real 200 —
            // extra sleeps on failure only ADD perceived delay. One instant re-probe, then give up.
            while (attempt < 2 && doc == null) {
                attempt++
                try {
                    doc = app.get("$loadHost/detail/watch/$slug", referer = nartoOrigin, headers = mapOf("User-Agent" to UA), timeout = 30000L).document
                } catch (e: Exception) {
                    android.util.Log.e("NartoDrama", "load attempt=$attempt/2 slug=$slug error=${e.message?.take(80)}", e)
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

    // Fetch the refresh-source payload for this provider's OWN host only (narto-drama.com).
    // Per-episode cooldown handling: retryable gate that we wait out (bounded) then retry.
    private suspend fun fetchRefresh(slug: String, ep: String): NartoResponse? {
        var waited = false
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                val body = app.get(
                    "$mainUrl/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx",
                    referer = nartoOrigin,
                    timeout = 60000L
                ).text
                val edge = mapper.readValue(body, NartoResponse::class.java)
                if (edge.ok != true && (edge.message == "refresh_source_recently_failed" || edge.message == "refresh_source_cooldown_active")) {
                    if (waited) {
                        android.util.Log.e("NartoDrama", "fetchRefresh COOLDOWN persists slug=$slug ep=$ep retryAfter=${edge.retryAfterSeconds}")
                        return null
                    }
                    waited = true
                    val waitMs = ((edge.retryAfterSeconds ?: 15).coerceIn(4, 12)) * 1000L
                    android.util.Log.e("NartoDrama", "fetchRefresh COOLDOWN slug=$slug ep=$ep waiting=${waitMs}ms")
                    try { Thread.sleep(waitMs) } catch (e2: InterruptedException) { Thread.currentThread().interrupt() }
                    continue
                }
                return edge
            } catch (e: Exception) {
                android.util.Log.e("NartoDrama", "fetchRefresh ERROR attempt=$attempt/2 slug=$slug ep=$ep", e)
                if (attempt < 2) {
                    try { Thread.sleep(800) } catch (e2: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val m = Regex("""/detail/watch/([^/?]+)/(\d+)""").find(data) ?: return false
            val ep = m.groupValues[2]
            var slug = m.groupValues[1]
            val loadHost = Regex("""https://([^/]+)/detail/watch/""").find(data)?.groupValues?.get(1)
                ?.let { "https://$it" } ?: mainUrl

            var edge = fetchRefresh(slug, ep)
            if (edge == null) {
                android.util.Log.e("NartoDrama", "loadLinks NO EDGE slug=$slug ep=$ep")
                return false
            }

            if (edge.ok != true && setOf(
                    "stream_temporarily_unavailable",
                    "refresh_source_recently_failed",
                    "refresh_source_cooldown_active"
                ).contains(edge.message)
            ) return false

            if (edge.ok != true && edge.message == "slug_mismatch") {
                val canon = edge.canonical?.let { Regex("""/detail/watch/([^/?]+)/""").find(it)?.groupValues?.get(1) }
                if (canon != null && canon != slug) {
                    android.util.Log.e("NartoDrama", "loadLinks slug_mismatch $slug -> $canon ep=$ep")
                    slug = canon
                    edge = fetchRefresh(slug, ep)
                    if (edge == null) return false
                }
            }
            if (edge.ok != true) {
                android.util.Log.e("NartoDrama", "loadLinks edge.ok!=true (continuing anyway) slug=$slug ep=$ep msg=${edge.message} play=${edge.directPlayUrl?.take(60)} res=${edge.multiResolutions?.size}")
            }

            // 1) subtitles — every track the API returns (multi_subtitles + any single track).
            val seenSubs = LinkedHashSet<String>()
            val subTracks = buildList {
                edge.multiSubtitles.orEmpty().forEach { s ->
                    val rel = s.subtitleUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                    val lang = s.label?.takeIf { it.isNotBlank() } ?: s.languageCode ?: "ترجمة"
                    add(lang to rel)
                }
                edge.subtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                    add((edge.selectedSubtitleLanguage?.takeIf { l -> l.isNotBlank() } ?: "ترجمة") to it)
                }
                edge.directSubtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                    add("ترجمة مباشرة" to it)
                }
            }
            for ((lang, rel) in subTracks) {
                val subUrl = if (rel.startsWith("http")) rel else STREAM_HOST + rel
                if (!seenSubs.add(subUrl)) continue
                try { subtitleCallback(newSubtitleFile(lang, subUrl)) } catch (e: Exception) {}
            }

            val emitted = LinkedHashSet<String>()
            var any = false
            var skippedDead = 0

            suspend fun emit(u: String, label: String, q: String) {
                if (u.isBlank() || !emitted.add(u)) return
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

            // v33 (fix 410 'Source error'): the three real multi_resolutions tokens (1080/720/480) are
            // shortmax-stream signed URLs that EXPIRE to HTTP 410 minutes after refresh — that's
            // what state=ERROR(7)/Response 410 in logcat shows when the player auto-selects the
            // first one. So we must NOT emit the tokens as separate selectable links (v32 did that
            // and reintroduced the 410). The SAFE, always-alive source is the narto proxy
            // (direct_play_url / play_url — stream-e1/e/m/{jwt}) which proxies the HLS/MP4 directly
            // and never expires to 410. That is the primary "كامل" link.
            //
            // To satisfy "multiple qualities" where the proxy is single-rendition, ALSO emit the
            // single highest multi_resolutions stream_url as a fresh "جودة متعددة" token (it works
            // if tapped immediately after refresh) — but NEVER the full 1080/720/480 set (the
            // player would pick a dead one). One fresh master + the always-live proxy.
            val resolutions = edge.multiResolutions.orEmpty()
                .filter { !it.streamUrl.isNullOrBlank() }

            fun proxyQuality(u: String?): String {
                val s = u ?: return "480p"
                val seg = s.trim().trimEnd('=').substringAfterLast('.')
                val dec = try { java.net.URLDecoder.decode(seg, "UTF-8") } catch (e: Exception) { seg }
                val q = Regex("""_(\d{3,4})p""").find(dec)?.groupValues?.get(1)
                return if (q == null) "480p" else "${q}p"
            }

            // v34 (fix "720 لا تظهر"): show EVERY quality the API returns as its own selectable
            // link (1080p / 720p / 480p) — the user explicitly wants all of them, for real.
            // Ordering: the ALWAYS-ALIVE narto proxy ("كامل") FIRST so the default tap always
            // plays (this is the "الجودة الأكثر استجابة" — it never 410s), then the real
            // quality tokens highest-first so the player's selector lists the full set.
            //
            // The quality tokens are shortmax signed URLs that MAY expire to 410 minutes later —
            // that's inherent to the site, and having "كامل" first guarantees a working link.
            fun rendQ(r: com.nartodrama.plugin.NartoResolution): String =
                when (r.resolution) {
                    2160 -> "2160p"; 1440 -> "1440p"; 1080 -> "1080p"; 720 -> "720p"
                    540 -> "540p"; 480 -> "480p"; 360 -> "360p"; else -> (r.resolution?.toString() ?: "480") + "p"
                }

            // 1) Always-live proxy direct (single quality, always plays) — first & reliable.
            val proxyQ = proxyQuality(edge.directPlayUrl)
            for (u in listOfNotNull(edge.directPlayUrl, edge.playUrl).distinct()) {
                emit(u, "كامل", proxyQ)
            }

            // 2) Every real quality as its own labelled link (highest first).
            val orderedRends = resolutions.sortedByDescending { it.resolution ?: 0 }
            for (r in orderedRends) {
                val u = r.streamUrl ?: continue
                val q = rendQ(r)
                emit(u, q, q)
            }

            // Fallback: if even that yielded nothing (no proxy, top token dead), surface whatever
            // multi-res is left (shouldn't happen, but never hand back an empty list).
            if (emitted.isEmpty()) {
                android.util.Log.e("NartoDrama", "loadLinks no qualities emitted (all died?) slug=$slug")
            }

            android.util.Log.e("NartoDrama", "loadLinks DONE slug=$slug ep=$ep links=${emitted.size} subs=${subTracks.size} deadSkipped=$skippedDead any=$any")
            any
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "loadLinks FATAL", e)
            false
        }
    }
}