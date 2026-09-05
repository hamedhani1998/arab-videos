package com.nartoedge.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// Standalone "Edge Narto Drama" extension — pinned to https://edge.narto-drama.com ONLY.
// This provider is 100% independent: its own cache, its own refresh channel, its own cooldown
// handling — it never talks to main.narto-drama.com (that belongs to the separate "Narto Drama"
// extension). Kept as one concrete class; no shared base with the other source.
private const val EDGE_HOST = "https://edge.narto-drama.com"
private const val STREAM_HOST = "https://stream.narto-drama.com"

// Backend hosts that are dead (DNS NODATA / non-existent domain) and must NOT be emitted as
// playback links — the player would select them and fail.
private val DEAD_HOST_PATTERNS = listOf("montagehub")

// One JSON-LD ListItem entry from the search results page.
private data class SearchHit(
    @JsonProperty("@type") val type: String? = null,
    val url: String? = null,    // https://edge.narto-drama.com/detail/watch/{slug}?lang=...
    val name: String? = null,   // Arabic title (e.g. "[مدبلج] ...")
    val image: String? = null,  // /assets/poster/{id}.jpg
)

// Narto edge playback API — Narto aggregates short-drama from MANY backends (shortmax, NetShort,
// StardustTV, mydramawave, ...). Each work's direct_play_url/play_url/multi_resolutions may be
// an HLS playlist OR a direct MP4 file — so loadLinks detects the container per link.
private data class EdgeResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val canonical: String? = null,          // full canonical URL hint on slug_mismatch
    @JsonProperty("retry_after_seconds") val retryAfterSeconds: Int? = null, // 429 cooldown
    @JsonProperty("direct_play_url") val directPlayUrl: String? = null,
    @JsonProperty("play_url") val playUrl: String? = null,
    @JsonProperty("multi_resolutions") val multiResolutions: List<EdgeResolution>? = null,
    @JsonProperty("multi_subtitles") val multiSubtitles: List<EdgeSub>? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,
    @JsonProperty("direct_subtitle_url") val directSubtitleUrl: String? = null,
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

class EdgeNartoProvider : MainAPI() {
    override var name = "Edge Narto Drama"
    override var mainUrl = EDGE_HOST
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

    // Referer for all requests/links — the main domain. This is the ONLY "shared" value and it's
    // just an HTTP Referer header the narto stream/subtitle servers expect; it does NOT merge the
    // two sources (each still browses and fetches playback from its OWN pinned domain above).
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
            android.util.Log.e("EdgeNarto", "search fetch error", e)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val q = request.data.trim()
            val html = fetchSearch(q)
            if (html == null) {
                android.util.Log.e("EdgeNarto", "getMainPage fetch failed q=$q")
                return null
            }
            android.util.Log.e("EdgeNarto", "getMainPage q=$q fetchMs=${System.currentTimeMillis() - t0} len=${html.length}")
            val items = parseSearchItems(html)
            if (items.isEmpty()) return null
            val list = items.take(12).mapNotNull { it.toSearchResponse() }
            if (list.isEmpty()) null else newHomePageResponse(request.name, list)
        } catch (e: Exception) {
            android.util.Log.e("EdgeNarto", "getMainPage ERROR", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val html = fetchSearch(query)
            if (html == null) return null
            parseSearchItems(html).mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            android.util.Log.e("EdgeNarto", "search ERROR q=$query", e)
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
            // v2 (perf): no retry-sleep punishment. The detail page is server-bound: the HTML
            // build takes 12-19s on slow slugs, so a generous first timeout gets a real 200 —
            // extra sleeps on failure only ADD perceived delay. One instant re-probe, then give up.
            while (attempt < 2 && doc == null) {
                attempt++
                try {
                    doc = app.get("$loadHost/detail/watch/$slug", referer = nartoOrigin, headers = mapOf("User-Agent" to UA), timeout = 30000L).document
                } catch (e: Exception) {
                    android.util.Log.e("EdgeNarto", "load attempt=$attempt/2 slug=$slug error=${e.message?.take(80)}", e)
                }
            }
            if (doc == null) return null
            android.util.Log.e("EdgeNarto", "load slug=$slug host=$loadHost fetchMs=${System.currentTimeMillis() - t0} eps=" + doc.select("div.episode-list a.episode-item").size)

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
                android.util.Log.e("EdgeNarto", "load slug=$slug no static episodes -> fallback single ep=1")
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

    // Fetch the refresh-source payload for this provider's OWN host only (edge). v23 cooldown
    // handling: retryable per-episode gate that we wait out (bounded) then retry.
    private suspend fun fetchRefresh(slug: String, ep: String): EdgeResponse? {
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
                val edge = mapper.readValue(body, EdgeResponse::class.java)
                if (edge.ok != true && (edge.message == "refresh_source_recently_failed" || edge.message == "refresh_source_cooldown_active")) {
                    if (waited) {
                        android.util.Log.e("EdgeNarto", "fetchRefresh COOLDOWN persists slug=$slug ep=$ep retryAfter=${edge.retryAfterSeconds}")
                        return null
                    }
                    waited = true
                    val waitMs = ((edge.retryAfterSeconds ?: 15).coerceIn(4, 12)) * 1000L
                    android.util.Log.e("EdgeNarto", "fetchRefresh COOLDOWN slug=$slug ep=$ep waiting=${waitMs}ms")
                    try { Thread.sleep(waitMs) } catch (e2: InterruptedException) { Thread.currentThread().interrupt() }
                    continue
                }
                return edge
            } catch (e: Exception) {
                android.util.Log.e("EdgeNarto", "fetchRefresh ERROR attempt=$attempt/2 slug=$slug ep=$ep", e)
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
                android.util.Log.e("EdgeNarto", "loadLinks NO EDGE slug=$slug ep=$ep")
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
                    android.util.Log.e("EdgeNarto", "loadLinks slug_mismatch $slug -> $canon ep=$ep")
                    slug = canon
                    edge = fetchRefresh(slug, ep)
                    if (edge == null) return false
                }
            }
            if (edge.ok != true) {
                android.util.Log.e("EdgeNarto", "loadLinks edge.ok!=true (continuing anyway) slug=$slug ep=$ep msg=${edge.message} play=${edge.directPlayUrl?.take(60)} res=${edge.multiResolutions?.size}")
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
                    android.util.Log.e("EdgeNarto", "emit SKIP dead host $host ($label)")
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

            // ONE combined multi-quality link (the user's verified preference): the highest
            // multi_resolutions master. ExoPlayer's in-player switcher handles 1080/720/480 (or
            // plays if single-quality) — never the separate expiring 410 tokens.
            val resolutions = edge.multiResolutions.orEmpty()
                .filter { !it.streamUrl.isNullOrBlank() }
            val liveOnes = mutableListOf<Triple<String, String, String>>()
            if (resolutions.isNotEmpty()) {
                val byUrl = linkedMapOf<String, MutableList<String>>()
                for (r in resolutions) {
                    byUrl.getOrPut(r.streamUrl!!) { mutableListOf() }
                        .add(r.label ?: "${r.resolution ?: 480}p")
                }
                for ((u, labels) in byUrl) {
                    val res = resolutions.firstOrNull { it.streamUrl == u }?.resolution
                    val q = when (res) {
                        1080 -> "1080p"; 720 -> "720p"; 540 -> "540p"; else -> "480p"
                    }
                    val label = if (labels.size == 1) labels[0] else labels.joinToString("/")
                    liveOnes.add(Triple(u, label, q))
                }
            }

            fun proxyQuality(u: String?): String {
                val s = u ?: return "480p"
                val seg = s.trim().trimEnd('=').substringAfterLast('.')
                val dec = try { java.net.URLDecoder.decode(seg, "UTF-8") } catch (e: Exception) { seg }
                val q = Regex("""_(\d{3,4})p""").find(dec)?.groupValues?.get(1)
                return if (q == null) "480p" else "${q}p"
            }
            val proxyQ = proxyQuality(edge.directPlayUrl)

            val mres = edge.multiResolutions.orEmpty().filter { !it.streamUrl.isNullOrBlank() }
            val best = mres.maxByOrNull { (it.resolution ?: 0) } ?: mres.firstOrNull()
            val masterUrl = best?.streamUrl
            if (!masterUrl.isNullOrBlank()) {
                val labels = mres.filter { it.streamUrl == masterUrl }.mapNotNull { it.label }
                val label = if (labels.isEmpty()) "جودة متعددة" else "جودة متعددة · " + labels.joinToString("/")
                emit(masterUrl, label, "1080p")
            }

            var first = true
            for (u in listOfNotNull(edge.directPlayUrl, edge.playUrl).distinct()) {
                emit(u, if (first) "كامل" else "رابط مباشر", proxyQ)
                first = false
            }

            if (emitted.isEmpty()) {
                for ((u, label, q) in liveOnes) emit(u, label, q)
                android.util.Log.e("EdgeNarto", "loadLinks only refresh multi-res available, surfacing anyway slug=$slug")
            }

            android.util.Log.e("EdgeNarto", "loadLinks DONE slug=$slug ep=$ep links=${emitted.size} subs=${subTracks.size} deadSkipped=$skippedDead any=$any")
            any
        } catch (e: Exception) {
            android.util.Log.e("EdgeNarto", "loadLinks FATAL", e)
            false
        }
    }
}