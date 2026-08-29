package com.netshort.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private data class SubtitleItem(
    val url: String? = null,
    val format: String? = null,
    @JsonProperty("subtitleLanguage") val language: String? = null,
)

private data class EpisodeInfo(
    val episodeId: String? = null,
    val episodeNo: Int? = null,
    val isLock: Boolean? = null,
    val episodeCover: String? = null,
    val playVoucher: String? = null,
    val subtitleList: List<SubtitleItem>? = null,
)

private data class ShortPlayDetail(
    val shortPlayId: String? = null,
    val shortPlayName: String? = null,
    val shortPlayCover: String? = null,
    val shotIntroduce: String? = null,
    val videoEpisodeInfos: List<EpisodeInfo>? = null,
)

class NetShortProvider : MainAPI() {
    override var name = "NetShort"
    override var mainUrl = "https://netshort.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "ar" to "أحدث الدراما",
    )

    // صفحة الحلقات الكاملة فيها قائمة الأعمال
    private val episodeLinkRegex = Regex("""/ar/episode/([^"'\\]+)""")

    // استخراج كائنات JSON من بيانات الـ RSC
    private fun extractJsonObjects(html: String, key: String): List<String> {
        val results = mutableListOf<String>()
        val marker = "\"$key\":"
        var i = 0
        while (true) {
            val idx = html.indexOf(marker, i)
            if (idx < 0) break
            val start = html.indexOf('{', idx + marker.length)
            if (start < 0) break
            var depth = 0
            var inStr = false
            var esc = false
            var end = -1
            for (k in start until html.length) {
                val c = html[k]
                if (!inStr) {
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> { depth--; if (depth == 0) { end = k; break } }
                    }
                } else {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                }
            }
            if (end > start) {
                results.add(html.substring(start, end + 1).replace("\\\"", "\"").replace("\\u0026", "&"))
                i = end + 1
            } else break
        }
        return results
    }

    private fun parseShowList(html: String): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        for (m in episodeLinkRegex.findAll(html)) {
            val path = m.groupValues[1]
            val parts = path.split("-")
            if (parts.size < 3) continue
            val showName = parts.dropLast(2).joinToString("-").replace("-", " ").trim()
            if (showName.isNotEmpty() && seen.add(showName)) {
                results.add(newTvSeriesSearchResponse(showName, "$mainUrl/ar/episode/$path", TvType.TvSeries))
            }
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val res = app.get("$mainUrl/ar/all-episodes", referer = mainUrl).text
            val items = parseShowList(res)
            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val res = app.get("$mainUrl/ar/search?q=$q", referer = mainUrl).text
            parseShowList(res)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val res = app.get(url, referer = mainUrl).text
            val title = Regex("""<h1[^>]*>([^<]+)</h1>""").find(res)?.groupValues?.get(1)?.trim()
                ?: Regex("""/ar/episode/([^-]+)""")?.find(url)?.groupValues?.get(1)?.replace("-", " ")
                ?: "NetShort"

            val eps = mutableListOf<Episode>()
            val epPaths = episodeLinkRegex.findAll(res).map { it.groupValues[1] }.toSortedSet()
            for (path in epPaths) {
                val parts = path.split("-")
                val epNum = parts.last().toIntOrNull() ?: continue
                eps.add(newEpisode("$mainUrl/ar/episode/$path") {
                    episode = epNum; name = "الحلقة $epNum"
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {}
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
            val url = if (data.startsWith("http")) data else "$mainUrl$data"
            val res = app.get(url, referer = mainUrl).text

            // shortPlayDetailVo يحتوي playVoucher + subtitleList
            val detail = extractJsonObjects(res, "shortPlayDetailVo").firstOrNull()?.let {
                try { mapper.readValue(it, ShortPlayDetail::class.java) } catch (e: Exception) { null }
            }
            val episode = detail?.videoEpisodeInfos?.firstOrNull()

            val playVoucher = episode?.playVoucher
            if (playVoucher != null) {
                callback(newExtractorLink(name, "NetShort", playVoucher, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("720p")
                })
            }

            // الترجمات المتاحة
            episode?.subtitleList?.forEach { sub ->
                val subUrl = sub.url ?: return@forEach
                val lang = sub.language ?: "unknown"
                if (subUrl.isNotBlank()) {
                    try { subtitleCallback(newSubtitleFile(lang, subUrl)) } catch (e: Exception) {}
                }
            }
            playVoucher != null
        } catch (e: Exception) {
            false
        }
    }
}
