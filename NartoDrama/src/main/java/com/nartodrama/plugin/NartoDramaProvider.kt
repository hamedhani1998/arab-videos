package com.nartodrama.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// Search API: GET /search?q=&limit=&lang=ar-SA  (requires X-Requested-With: XMLHttpRequest)
private data class SearchResult(
    val ok: Boolean? = null,
    val items: List<SearchItem>? = null,
)

private data class SearchItem(
    val id: Long? = null,
    val title: String? = null,
    val url: String? = null,             // canonical  /detail/watch/<slug>
    val poster_url: String? = null,
    val description: String? = null,
    val source_type: String? = null,
    @JsonProperty("matched_tokens") val matchedTokens: List<String>? = null,
)

// parse canonical slug out of a /detail/watch/<slug> url
private fun slugFrom(url: String): String? =
    Regex("""/detail/watch/([\w\-]+)""").find(url)?.groupValues?.get(1)

class NartoDramaProvider : MainAPI() {
    override var name = "Narto Drama"
    override var mainUrl = "https://narto-drama.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "الرئيسية",
    )

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val t = title ?: return null
        val link = url ?: return null
        if (!link.startsWith("/detail/watch/")) return null
        return newTvSeriesSearchResponse(t, mainUrl + link, TvType.TvSeries) {
            posterUrl = poster_url?.let { if (it.startsWith("http")) it else mainUrl + it }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = if (page <= 1) "$mainUrl/?lang=ar-SA" else "$mainUrl/?page=$page&lang=ar-SA"
            val doc = app.get(url, referer = mainUrl).document

            val items = doc.select("article.card[data-watch-url]").mapNotNull { el ->
                val link = el.attr("data-watch-url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!link.startsWith("/detail/watch/")) return@mapNotNull null
                val title = el.attr("data-movie-title").takeIf { it.isNotBlank() }
                    ?: el.selectFirst("h3.title")?.text()?.trim()
                    ?: el.selectFirst("h3")?.text()?.trim()
                    ?: return@mapNotNull null
                val poster = el.selectFirst("img.poster")?.attr("src")?.takeIf { it.isNotBlank() }
                newTvSeriesSearchResponse(title, mainUrl + link, TvType.TvSeries) {
                    this.posterUrl = poster?.let { if (it.startsWith("http")) it else mainUrl + it }
                }
            }

            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val res = app.get(
                "$mainUrl/search?q=$q&limit=50&lang=ar-SA",
                referer = "$mainUrl/?lang=ar-SA",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
            val parsed = try {
                mapper.readValue(res, SearchResult::class.java)
            } catch (e: Exception) {
                return null
            }
            parsed.items?.mapNotNull { it.toSearchResponse() }.orEmpty()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document

            val title = doc.selectFirst("h1.movie-title")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.let { if (it.startsWith("http")) it else mainUrl + it }
            val description = doc.selectFirst(".movie-desc")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("a.movie-tag-pill").mapNotNull { it.text()?.trim()?.takeIf(String::isNotEmpty) }

            // captured from the passed /detail/watch/<slug> page url
            val slug = slugFrom(url) ?: return null

            val eps = doc.select("a.episode-item[href]").mapNotNull { el ->
                val href = el.attr("href") ?: return@mapNotNull null
                val ep = Regex("""/(\d+)(?:[?]|$)""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val link = if (href.startsWith("http")) href else mainUrl + href
                newEpisode(link) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // data = /detail/watch/<slug>/<ep> (maybe with ?query). We look for an m3u8 in the episode page.
            val doc = app.get(data, referer = mainUrl).document

            // 1) subtitles referenced in the page
            doc.select("track[src], video track[src]").forEach { tr ->
                val src = tr.attr("src")?.takeIf { it.isNotBlank() } ?: return@forEach
                val lang = tr.attr("srclang")?.takeIf { it.isNotBlank() } ?: "ترجمة"
                val subUrl = if (src.startsWith("http")) src else mainUrl + src
                try { subtitleCallback(newSubtitleFile(lang, subUrl)) } catch (e: Exception) {}
            }

            // 2) any m3u8 url in the page (video element src, data attributes, or embedded JSON)
            var vUrl: String? = doc.selectFirst("video[src]")?.attr("src")?.takeIf { it.endsWith(".m3u8") }

            if (vUrl == null) {
                val html = doc.select("script").joinToString("\n") { it.html() } + "\n" +
                        doc.toString()
                vUrl = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(html)?.groupValues?.get(0)
            }

            if (vUrl == null) {
                // last resort: a relative m3u8 path
                val rel = Regex("""["']([^"']+\.m3u8(?:\?[^"']*)?)["']""").find(doc.toString())?.groupValues?.get(1)
                vUrl = rel?.let { if (it.startsWith("http")) it else mainUrl + it }
            }

            val finalUrl = vUrl ?: return false

            val streamText = try { app.get(finalUrl, referer = mainUrl).text } catch (e: Exception) { "" }

            if (streamText.contains("#EXT-X-STREAM-INF")) {
                // master playlist => one full link with every available quality + audio (muxed) handled by player
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Full HD (كل الجودات)",
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = mainUrl
                        quality = getQualityFromName("1080p")
                        headers = mapOf("Referer" to mainUrl)
                    }
                )
            } else {
                val q = when {
                    finalUrl.contains("1080p") -> "1080p"
                    finalUrl.contains("720p") -> "720p"
                    finalUrl.contains("480p") -> "480p"
                    else -> "480p"
                }
                callback(
                    newExtractorLink(
                        source = name,
                        name = q,
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = mainUrl
                        quality = getQualityFromName(q)
                        headers = mapOf("Referer" to mainUrl)
                    }
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
