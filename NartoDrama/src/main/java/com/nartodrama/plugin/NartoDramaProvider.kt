package com.nartodrama.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// ---- Sharing drama4all.com (open backend) for INDEX/SEARCH/DETAILS ----
// Same /api/local_search item shape as Drama4All provider.
private data class SearchItem(
    val cover: String? = null,
    val description: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int = 0,
    val slug: String? = null,               // nt_ prefixed on drama4all
    val title: String? = null,
)

// ---- narto-drama edge API (no Cloudflare) for PLAYBACK ----
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
private const val EDGE_HOST = "https://edge.narto-drama.com"
private const val STREAM_HOST = "https://stream.narto-drama.com"

class NartoDramaProvider : MainAPI() {
    override var name = "Narto Drama"
    override var mainUrl = "https://drama4all.com"   // open indexing backend (shares narto nt_ content)
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "list/trending" to "الأكثر مشاهدة",
        "list/recent" to "أضيف حديثاً",
        "list/all" to "المكتبة",
    )

    // The video-serve origin we must present to the edge/stream endpoints.
    private val nartoOrigin = "https://narto-drama.com"

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val s = slug ?: return null
        // Only narto-drama (nt_) content belongs to this source.
        if (!s.startsWith("nt_")) return null
        val t = title ?: return null
        return newTvSeriesSearchResponse(t, "$mainUrl/series/$s", TvType.TvSeries) {
            posterUrl = cover
            episodes = totalEpisodes.coerceAtLeast(1)
        }
    }

    // Every narto playback starts from a drama4all /series/nt_<slug> (or /watch/nt_<slug>/<ep>)
    // url. We strip the nt_ prefix because the narto edge expects the canonical slug.
    private fun canonicalSlug(slug: String): String = slug.removePrefix("nt_")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("script").filter { el -> el.html().contains("const LIBRARY") }
                .flatMap { el ->
                    val html = el.html()
                    val start = html.indexOf('[')
                    val end = html.lastIndexOf(']')
                    if (start < 0 || end <= start) return@flatMap emptyList()
                    try {
                        val list = mapper.readValue(
                            html.substring(start, end + 1),
                            object : com.fasterxml.jackson.core.type.TypeReference<List<SearchItem>>() {}
                        )
                        list.mapNotNull { it.toSearchResponse() }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val res = app.get("$mainUrl/api/local_search?q=${java.net.URLEncoder.encode(query, "UTF-8")}", referer = mainUrl).text
            val items: List<SearchItem> = try {
                mapper.readValue(
                    res,
                    object : com.fasterxml.jackson.core.type.TypeReference<List<SearchItem>>() {}
                )
            } catch (e: Exception) {
                return null
            }
            items.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("p.synopsis")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("div.stage-tags a.tag").mapNotNull { it.text()?.trim()?.takeIf(String::isNotEmpty) }

            val slug = doc.select("script").mapNotNull { el ->
                val html = el.html()
                val m = Regex("""const SERIES\s*=\s*\{[^}]*slug:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(html)
                m?.groupValues?.get(1)
            }.firstOrNull()

            val eps = doc.select("div.eps a[data-ep]").mapNotNull { el ->
                val ep = el.text()?.trim()?.toIntOrNull() ?: return@mapNotNull null
                newEpisode("/watch/$slug/$ep") {
                    episode = ep
                    name = "الحلقة $ep"
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                posterUrl = poster
                plot = description
                this.tags = tags
            }
        } catch (e: Exception) {
            null
        }
    }

    // Fetch the narto edge refresh-source payload for a canonical slug+episode.
    private suspend fun edgeRefreshSource(slug: String, ep: String): EdgeResponse? {
        return try {
            val edgeUrl = "$EDGE_HOST/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx"
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
            // data = /watch/nt_<slug>/<ep>
            val m = Regex("""/watch/([\w\-]+)/(\d+)""").find(data) ?: return false
            val ep = m.groupValues[2]

            // Try with the drama4all slug (nt_ stripped). If the narto edge reports a
            // slug_mismatch it returns the canonical slug in `canonical` — retry with it.
            var slug = canonicalSlug(m.groupValues[1])
            var edge = edgeRefreshSource(slug, ep) ?: return false

            // Canonical re-discovery: drama4all slugs (e.g. -bl-r-by-at) may differ from the
            // narto canonical (e.g. -bl-rby-at). The edge tells us the right one.
            if (edge.ok != true && edge.message == "slug_mismatch") {
                val canon = edge.canonical?.let { Regex("""/watch/([^/]+)/""").find(it)?.groupValues?.get(1) }
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
