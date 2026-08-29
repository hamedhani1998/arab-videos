package com.flextv.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class FlexTVProvider : MainAPI() {
    override var name = "FlexTV"
    override var mainUrl = "https://www.flextv.cc"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "ar" to "أحدث الدراما",
    )

    // رابط صفقة الحلقة: /ar/episodes/episode-{no}-{name}-{seriesId}
    private val episodeLinkRegex = Regex("""/ar/episodes/episode-\d+-([^"'\\]+)""")
    private val seriesIdRegex = Regex("""/ar/episodes/episode-\d+-.+-([A-Za-z0-9_-]+)""")

    // الماستر بلاي ليست مضمّن في صفحة الحلقة
    private val masterRegex = Regex("""https://resources-sgp-auth\.flextv\.cc/wz/m3u8/[^"\\]+/abr\.m3u8\?auth_key=[^"\\]+""")

    private fun parseShowList(html: String): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        for (m in seriesIdRegex.findAll(html)) {
            val seriesId = m.groupValues[1]
            if (seen.add(seriesId)) {
                results.add(
                    newTvSeriesSearchResponse(
                        seriesId.replace("-", " ").trim(),
                        "$mainUrl/ar/episodes/episode-1-$seriesId",
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
            val seriesId = seriesIdRegex.find(url)?.groupValues?.get(1)
                ?: Regex("""/ar/episodes/[^"'\\]+""").find(url)?.value
                ?: return null

            val title = Regex("""<h[12][^>]*>([^<]+)</h[12]>""").find(res)?.groupValues?.get(1)?.trim()
                ?: seriesId.replace("-", " ").trim()

            val eps = mutableListOf<Episode>()
            val epNumbers = Regex("""/ar/episodes/episode-(\d+)-""").findAll(res).mapNotNull { it.groupValues[1].toIntOrNull() }.toSortedSet()
            if (epNumbers.isEmpty()) {
                eps.add(newEpisode(url) { episode = 1; name = "الحلقة 1" })
            } else {
                for (n in epNumbers) {
                    eps.add(newEpisode("$mainUrl/ar/episodes/episode-$n-$seriesId") {
                        episode = n; name = "الحلقة $n"
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
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
            val master = masterRegex.find(res)?.value ?: return false

            // الماستر بلاي ليست تحتوي جودات متعددة — نمررها مباشرة
            callback(
                newExtractorLink(name, "FlexTV (متعدد الجودات)", master, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("1080p")
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
