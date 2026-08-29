package com.nartodrama.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

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
private data class EdgeResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val canonical: String? = null,          // full canonical URL hint on slug_mismatch
    @JsonProperty("direct_play_url") val directPlayUrl: String? = null, // master m3u8 (open)
    @JsonProperty("play_url") val playUrl: String? = null,
    @JsonProperty("multi_subtitles") val multiSubtitles: List<EdgeSub>? = null,
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

            // 2) the video — direct master playlist (open mydramawave host)
            val vUrl = edge.directPlayUrl?.takeIf { it.isNotBlank() }
                ?: edge.playUrl?.takeIf { it.isNotBlank() }
                ?: return false

            val streamText = try { app.get(vUrl, referer = nartoOrigin).text } catch (e: Exception) { "" }

            if (streamText.contains("#EXT-X-STREAM-INF")) {
                // master playlist => one link, player adapts across all available qualities + muxes audio
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Full HD (كل الجودات)",
                        url = vUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = nartoOrigin
                        quality = getQualityFromName("1080p")
                        headers = mapOf("Referer" to nartoOrigin)
                    }
                )
            } else {
                val q = when {
                    vUrl.contains("1080p") -> "1080p"
                    vUrl.contains("720p") -> "720p"
                    vUrl.contains("480p") -> "480p"
                    else -> "480p"
                }
                callback(
                    newExtractorLink(
                        source = name,
                        name = q,
                        url = vUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = nartoOrigin
                        quality = getQualityFromName(q)
                        headers = mapOf("Referer" to nartoOrigin)
                    }
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
