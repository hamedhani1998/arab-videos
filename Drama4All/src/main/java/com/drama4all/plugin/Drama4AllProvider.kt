package com.drama4all.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// LIBRARY item embedded on drama4all list + search pages.
private data class SearchItem(
    val cover: String? = null,
    val description: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int = 0,
    val slug: String? = null,
    val title: String? = null,
    val views: Long? = null,
    @JsonProperty("likes_count") val likesCount: Long? = null,
    val rating: Double? = null,
    val genres: List<String>? = null,
)

// /api/episode/{slug}/{ep}
private data class EpisodeItem(
    @JsonProperty("video_url") val videoUrl: String? = null,
    val subs: List<SubtitleItem>? = null,
)

private data class SubtitleItem(
    val lang: String? = null,
    val url: String? = null,
)

class Drama4AllProvider : MainAPI() {
    override var name = "دراما للجميع"
    override var mainUrl = "https://drama4all.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "list/trending" to "الأكثر مشاهدة",
        "list/recent" to "أضيف حديثاً",
        "list/all" to "المكتبة",
    )

    // Robustly extract the `const LIBRARY = [...]` JSON array token from an HTML page.
    // Uses a real bracket matcher (JSON-aware) instead of the naive indexOf('[')/lastIndexOf(']'),
    // which grabs too much when other arrays/JS follow the LIBRARY array (e.g. on /search pages).
    private fun extractLibraryArray(html: String): String? {
        val mark = html.indexOf("const LIBRARY")
        if (mark < 0) return null
        val start = html.indexOf('[', mark)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (k in start until html.length) {
            val c = html[k]
            if (!inStr) {
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) return html.substring(start, k + 1) }
                }
            } else {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            }
        }
        return null
    }

    private fun parseLibrary(html: String): List<SearchItem> {
        val arr = extractLibraryArray(html) ?: return emptyList()
        return try {
            mapper.readValue(
                arr,
                object : com.fasterxml.jackson.core.type.TypeReference<List<SearchItem>>() {}
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val s = slug ?: return null
        val t = title ?: return null
        // نعرض كل ما تستضيفه دراما للجميع (sf_ و nt_) — لا نستبعد شيئًا حتى تكتمل نتائج البحث
        return newTvSeriesSearchResponse(t, "$mainUrl/series/$s", TvType.TvSeries) {
            posterUrl = cover
            episodes = totalEpisodes.coerceAtLeast(1)
            score = rating?.let { Score.from(it, 10) }
        }
    }

    private fun List<SearchItem>.mapResults(): List<SearchResponse> =
        mapNotNull { it.toSearchResponse() }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("script")
                .mapNotNull { el -> el.html().takeIf { it.contains("const LIBRARY") } }
                .flatMap { parseLibrary(it) }
                .mapResults()
            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            // /api/local_search is capped (≈20, and returns narto nt_ too), so it misses most
            // drama4all sf_ works. The site's own /search?q= page embeds the FULL result set in
            // its `const LIBRARY` array — parse that instead.
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val res = app.get("$mainUrl/search?q=$q", referer = mainUrl).text
            val items = parseLibrary(res)
            if (items.isEmpty()) return emptyList()
            items.mapResults()
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

            // Slug from embedded SERIES JSON
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // data = /watch/<slug>/<ep>
            val m = Regex("""/watch/([\w\-]+)/(\d+)""").find(data) ?: return false
            val slug = m.groupValues[1]
            val ep = m.groupValues[2]

            val json = app.get("$mainUrl/api/episode/$slug/$ep", referer = "$mainUrl/watch/$slug/$ep").text
            val item = try {
                mapper.readValue(json, EpisodeItem::class.java)
            } catch (e: Exception) {
                return false
            }
            val vUrl = item.videoUrl ?: return false

            // 1) كل الترجمات/القرائن
            item.subs?.forEach { s ->
                val lang = s.lang ?: return@forEach
                val subUrl = s.url ?: return@forEach
                if (subUrl.isNotBlank()) {
                    try {
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    } catch (e: Exception) {}
                }
            }

            // 2) تحليل الـ m3u8: هل هو master (جودات + أصوات متعددة) أم ملف جودة واحدة؟
            val streamText = try { app.get(vUrl, referer = mainUrl).text } catch (e: Exception) { "" }

            if (streamText.contains("#EXT-X-STREAM-INF")) {
                // master playlist => رابط واحد كامل يحتوي كل الجودات المتاحة + الصوت.
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Full HD (كل الجودات)",
                        url = vUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = mainUrl
                        quality = getQualityFromName("1080p")
                        headers = mapOf("Referer" to mainUrl)
                    }
                )
            } else {
                // ملف media وحيد (جودة واحدة): نكشف عنه مباشرة
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
