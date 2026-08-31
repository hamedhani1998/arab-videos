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

// ---- edge.narto-drama.com is the OPEN (no-Cloudflare) mirror of narto-drama.com ----
// It serves the native Arabic catalog (including ALL dubbed مدبلج content) at
//   GET /search?lang=ar-SA&q=<query>   -> HTML page embedding a ListItem JSON array
//   GET /detail/watch/{slug}           -> series detail (h1, poster, episode list)
//   GET /e/rs/detail/watch/{slug}/{ep}/refresh-source?rs_ctx={JWT} -> playback
// The web frontend narto-drama.com itself is Cloudflare-protected; edge is not.

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
    @JsonProperty("direct_play_url") val directPlayUrl: String? = null, // may be HLS master OR direct MP4
    @JsonProperty("play_url") val playUrl: String? = null,
    @JsonProperty("multi_resolutions") val multiResolutions: List<EdgeResolution>? = null,
    @JsonProperty("multi_subtitles") val multiSubtitles: List<EdgeSub>? = null,
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
private const val STREAM_HOST = "https://stream.narto-drama.com"

class NartoDramaProvider : MainAPI() {
    override var name = "Narto Drama"
    override var mainUrl = "https://edge.narto-drama.com"  // open backend (no Cloudflare)
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Curated seed queries mirroring the native Narto home: a generic feed, the all-dubbed
    // مدبلج feed, and a general drama feed. The empty query returns the default browse list.
    override val mainPage = mainPageOf(
        "ق" to "الأحدث والمقترحة",
        "مدبلج" to "مسلسلات مدبلجة 🎙️",
        "دراما" to "دراما",
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
    private suspend fun inferStreamType(url: String): ExtractorLinkType {
        val lower = url.lowercase()
        if (lower.contains("mime_type=video_mp4") || lower.endsWith(".mp4")) return ExtractorLinkType.VIDEO
        if (lower.contains(".m3u8") || lower.contains("/e/m/") || lower.contains("/e/s/")
            || lower.contains("/main.m3u8")) return ExtractorLinkType.M3U8
        // Ambiguous (e.g. token-style montagehub URLs with no extension) -> probe Content-Type.
        return try {
            val r = app.head(url, headers = mapOf("User-Agent" to UA), referer = nartoOrigin)
            val ct = r.headers["Content-Type"]?.lowercase() ?: ""
            if (ct.contains("mpegurl") || ct.contains("hls") || ct.contains("apple")) ExtractorLinkType.M3U8
            else ExtractorLinkType.VIDEO
        } catch (e: Exception) {
            ExtractorLinkType.M3U8
        }
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
        return try {
            val q = request.data.replace(" ", "+")
            val url = "$mainUrl/search?lang=ar-SA&q=$q"
            val doc = app.get(url, referer = nartoOrigin).text
            val items = parseSearchItems(doc)
            if (items.isEmpty()) return null
            val list = items.mapNotNull { it.toSearchResponse() }
            if (list.isEmpty()) null else newHomePageResponse(request.name, list)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val res = app.get("$mainUrl/search?lang=ar-SA&q=$q", referer = nartoOrigin).text
            val items = parseSearchItems(res)
            items.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
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
        return try {
            val slug = Regex("""/detail/watch/([^/?]+)""").find(url)?.groupValues?.get(1) ?: return null
            val doc = app.get("$mainUrl/detail/watch/$slug", referer = nartoOrigin).document

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")

            val eps = doc.select("div.episode-list a.episode-item")
                .mapNotNull { el ->
                    val href = el.attr("href") ?: return@mapNotNull null
                    val ep = Regex("""/detail/watch/[^/]+/(\d+)""").find(href)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: return@mapNotNull null
                    newEpisode("$mainUrl/detail/watch/$slug/$ep") {
                        episode = ep
                        name = "الحلقة $ep"
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                posterUrl = poster
                plot = description
            }
        } catch (e: Exception) {
            null
        }
    }

    // Fetch the narto edge refresh-source payload for a canonical slug+episode.
    private suspend fun edgeRefreshSource(slug: String, ep: String): EdgeResponse? {
        return try {
            val edgeUrl = "$mainUrl/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx"
            val res = app.get(edgeUrl, referer = nartoOrigin).text
            mapper.readValue(res, EdgeResponse::class.java)
        } catch (e: Exception) {
            null
        }
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

            var edge = edgeRefreshSource(slug, ep) ?: return false

            // If the source reports a stream error that is temporarily unavailable, surface a
            // readable failure rather than silently returning nothing.
            if (edge.ok != true && edge.message == "stream_temporarily_unavailable") return false

            // Canonical re-discovery: a requested slug may differ from the narto canonical one.
            if (edge.ok != true && edge.message == "slug_mismatch") {
                val canon = edge.canonical?.let { Regex("""/detail/watch/([^/?]+)/""").find(it)?.groupValues?.get(1) }
                if (canon != null && canon != slug) {
                    slug = canon
                    edge = edgeRefreshSource(slug, ep) ?: return false
                }
            }
            if (edge.ok != true) return false

            // 1) subtitles — resolved on stream.narto-drama.com (narto-drama.com itself is CF-blocked)
            edge.multiSubtitles?.forEach { s ->
                val rel = s.subtitleUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                val lang = s.label?.takeIf { it.isNotBlank() } ?: s.languageCode ?: "ترجمة"
                val subUrl = if (rel.startsWith("http")) rel else STREAM_HOST + rel
                try { subtitleCallback(newSubtitleFile(lang, subUrl)) } catch (e: Exception) {}
            }

            // 2) the video — Narto aggregates from many backends, so a work can carry:
            //      a) multi_resolutions -> the full 1080/720/480 quality set (per-resolution URLs)
            //      b) a single play_url / direct_play_url (HLS master/media, or a direct MP4 file)
            // We emit EVERY distinct URL with its correct container type so the right link plays.
            val emitted = LinkedHashSet<String>()
            var any = false

            suspend fun emit(u: String, label: String, q: String) {
                if (u.isBlank() || !emitted.add(u)) return
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

            val resolutions = edge.multiResolutions.orEmpty()
                .filter { !it.streamUrl.isNullOrBlank() }
            if (resolutions.isNotEmpty()) {
                // Several "resolutions" often share ONE master/proxy URL — group by actual URL so
                // we don't emit the same link 3 times, then surface each distinct source as its own
                // playable quality (this is the "more than one link" the user wants to see).
                val byUrl = linkedMapOf<String, MutableList<String>>()
                for (r in resolutions) {
                    val u = r.streamUrl!!
                    byUrl.getOrPut(u) { mutableListOf() }
                        .add(r.label ?: "${r.resolution ?: 480}p")
                }
                for ((u, labels) in byUrl) {
                    val label = if (labels.size == 1) labels[0] else labels.joinToString("/")
                    val res = resolutions.firstOrNull { it.streamUrl == u }?.resolution
                    val q = when (res) {
                        1080 -> "1080p"
                        720 -> "720p"
                        540 -> "540p"
                        else -> "480p"
                    }
                    emit(u, label, q)
                }
            } else {
                // Fallback: single link(s). Emit the raw source AND the local proxy variant
                // (deduped) for redundancy — one of the two is what the web player actually uses.
                for (u in listOfNotNull(edge.playUrl, edge.directPlayUrl)) emit(u, "كامل", "1080p")
            }

            any
        } catch (e: Exception) {
            false
        }
    }
}
