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
    private val episodePathRegex = Regex("""/ar/episodes/([^"'\\]+)""")

    // الماستر بلاي ليست مضمّن في صفحة الحلقة
    private val masterRegex = Regex("""https://resources-sgp-auth\.flextv\.cc/wz/m3u8/[^"\\]+/abr\.m3u8\?auth_key=[^"\\]+""")

    // يفكّ اسم العمل (عربي) ومعرّف المسلسل من مسار الحلقة
    private fun parsePath(path: String): Pair<String, String>? {
        if (!path.startsWith("episode-")) return null
        val parts = path.split("-")
        if (parts.size < 3) return null
        val seriesId = parts.last()
        if (!seriesId.all { it.isLetterOrDigit() || it == '_' }) return null
        val encTitle = parts.drop(2).dropLast(1).joinToString("-")
        if (encTitle.isBlank()) return null
        val title = try {
            java.net.URLDecoder.decode(encTitle.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            encTitle
        }
        return title.trim() to seriesId
    }

    private fun parseShowList(html: String): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        for (m in episodePathRegex.findAll(html)) {
            val path = m.groupValues[1]
            val parsed = parsePath(path) ?: continue
            val (title, seriesId) = parsed
            if (seen.add(seriesId)) {
                results.add(
                    newTvSeriesSearchResponse(
                        title,
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

            // استخراج seriesId من الـ URL
            val pathFromUrl = url.substringAfter("/ar/episodes/")
            val parsed = parsePath(pathFromUrl)
            val seriesId = parsed?.second
                ?: return null
            val urlTitle = parsed?.first ?: "FlexTV"

            val title = Regex("""<h[12][^>]*>([^<]+)</h[12]>""").find(res)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() } ?: urlTitle

            val eps = mutableListOf<Episode>()
            val seenEp = mutableSetOf<Int>()
            for (m in episodePathRegex.findAll(res)) {
                val p = m.groupValues[1]
                val pParsed = parsePath(p) ?: continue
                if (pParsed.second != seriesId) continue // حلقات نفس العمل فقط
                val pNo = p.substringBefore("-").removePrefix("episode-").toIntOrNull() ?: continue
                if (seenEp.add(pNo)) {
                    eps.add(newEpisode("$mainUrl/ar/episodes/episode-$pNo-$seriesId") {
                        episode = pNo; name = "الحلقة $pNo"
                    })
                }
            }
            if (eps.isEmpty()) {
                eps.add(newEpisode(url) { episode = 1; name = "الحلقة 1" })
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
