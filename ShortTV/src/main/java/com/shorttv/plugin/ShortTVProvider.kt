package com.shorttv.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

class ShortTVProvider : MainAPI() {
    override var name = "ShortTV"
    override var mainUrl = "https://www.shorttv.live"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "ar" to "أحدث الدراما",
    )

    private val episodeLinkRegex = Regex("""/ar/episode/([^"'\\]+)""")

    // صفحة الحلقة تضم روابط الجودات مباشرة
    private val video1080Regex = Regex("""video_1080\\*"\s*:\s*"(https?://[^"\\]+)""")
    private val video720Regex = Regex("""video_720\\*"\s*:\s*"(https?://[^"\\]+)""")
    private val video480Regex = Regex("""video_480\\*"\s*:\s*"(https?://[^"\\]+)""")

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&")

    private fun parseShowList(html: String): List<SearchResponse> {
        val shows = linkedSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (m in episodeLinkRegex.findAll(html)) {
            val path = m.groupValues[1]
            // path = {name}-{showId}-{ep}
            val parts = path.split("-")
            if (parts.size < 3) continue
            val ep = parts.last()
            val showId = parts[parts.size - 2]
            val showName = parts.dropLast(2).joinToString("-")
            if (seen.add(showName)) {
                results.add(
                    newTvSeriesSearchResponse(
                        showName.replace("-", " ").trim(),
                        "$mainUrl/ar/episode/$path",
                        TvType.TvSeries
                    )
                )
            }
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val res = app.get("$mainUrl/${request.data}", referer = mainUrl).text
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
            val title = Regex("""<title>([^<]+)</title>""").find(res)?.groupValues?.get(1)?.trim()
                ?: Regex("""/ar/episode/([^-\d]+)""").find(url)?.groupValues?.get(1)?.replace("-", " ")
                ?: return null

            val eps = mutableListOf<Episode>()
            val epLinks = episodeLinkRegex.findAll(res).map { it.groupValues[1] }.toSet()
            for (path in epLinks) {
                val parts = path.split("-")
                if (parts.size < 3) continue
                val epNum = parts.last().toIntOrNull() ?: continue
                eps.add(
                    newEpisode("/ar/episode/$path") {
                        episode = epNum
                        name = "الحلقة $epNum"
                    }
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps.sortedBy { it.episode }) {
                plot = Regex("""<meta name="description" content="([^"]+)""").find(res)?.groupValues?.get(1)
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
            val url = if (data.startsWith("http")) data else "$mainUrl$data"
            val res = app.get(url, referer = mainUrl).text

            // الجودات الثلاث مضمّنة في صفحة الحلقة
            val q1080 = video1080Regex.find(res)?.groupValues?.get(1)?.let(::unescape)
            val q720 = video720Regex.find(res)?.groupValues?.get(1)?.let(::unescape)
            val q480 = video480Regex.find(res)?.groupValues?.get(1)?.let(::unescape)

            if (q1080 != null) {
                callback(newExtractorLink(name, "1080p", q1080, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("1080p")
                })
            }
            if (q720 != null) {
                callback(newExtractorLink(name, "720p", q720, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("720p")
                })
            }
            if (q480 != null) {
                callback(newExtractorLink(name, "480p", q480, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("480p")
                })
            }
            q1080 != null || q720 != null || q480 != null
        } catch (e: Exception) {
            false
        }
    }
}
