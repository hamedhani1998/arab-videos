package com.stardusttv.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class StardustTVProvider : MainAPI() {
    override var name = "StardustTV"
    override var mainUrl = "https://www.stardusttv.net"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "ar" to "أحدث الدراما",
    )

    // روابط الـ m3u8 مضمّنة مباشرة في  HTML
    // النمط 1: v.stardust-tv.com/{lang}/{series}_{AR_DUB|AR}/h264/{ep}_{hash}.m3u8
    // النمط 2: v.stardust-tv.com/prod/{seriesId}/{ep}/{hash}.m3u8
    private val m3u8Regex = Regex("""https://v\.stardust-tv\.com/[^"\\]+\.m3u8""")

    private data class ParsedShow(
        val title: String,
        val cover: String?,
        val dubType: String, // "AR_DUB" (مدبلج) أو "AR" (مترجم)
        val episodes: List<String>,
    )

    private fun parseShows(html: String): List<ParsedShow> {
        val shows = mutableMapOf<String, MutableList<String>>()
        val covers = mutableMapOf<String, String>()
        for (m in m3u8Regex.findAll(html)) {
            val url = m.value
            val file = url.substringAfterLast("/").removeSuffix(".m3u8")
            val langAndSeries = url.removePrefix("https://v.stardust-tv.com/").substringBefore("/h264/")
            val dubType = if (langAndSeries.endsWith("_AR_DUB")) "AR_DUB"
                else if (langAndSeries.endsWith("_AR")) "AR" else "ORIGINAL"
            val seriesName = langAndSeries
                .removeSuffix("_AR_DUB").removeSuffix("_AR")
                .replace("-", " ").trim()
            shows.getOrPut(seriesName) { mutableListOf() }.add(url)
        }
        // ملاحظة: الغلاف غير متاح بسهولة من رابط الـ m3u8؛ نتركه null
        return shows.map { (title, eps) ->
            ParsedShow(title, null, "AR_DUB", eps)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val res = app.get("$mainUrl/${request.data}", referer = mainUrl).text
            val shows = parseShows(res)
            val items = shows.map { show ->
                newTvSeriesSearchResponse(show.title, "$mainUrl/video/index", TvType.TvSeries)
            }
            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val res = app.get("$mainUrl/ar/search?q=$q", referer = mainUrl).text
            parseShows(res).map { newTvSeriesSearchResponse(it.title, "$mainUrl/video/index", TvType.TvSeries) }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val res = app.get("$mainUrl/ar", referer = mainUrl).text
            val shows = parseShows(res)
            // نعرض كل الأعمال مع حلقاتها (كل حلقة = فيديو قصير)
            // نبني قائمة حلقات شاملة من كل الـ m3u8 المتاحة
            val eps = mutableListOf<Episode>()
            var idx = 1
            for (show in shows) {
                for (epUrl in show.episodes) {
                    eps.add(newEpisode(epUrl) { episode = idx; name = "${show.title} - الحلقة ${idx}" })
                    idx++
                }
            }
            newTvSeriesLoadResponse("StardustTV", url, TvType.TvSeries, eps) {}
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
            // data هنا هو رابط الـ m3u8 مباشرة
            val m3u8 = data
            callback(
                newExtractorLink(name, "StardustTV", m3u8, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("720p")
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
