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
    private val episodePathRegex = Regex("""/ar/episodes/([^"'\?#\s]+)""")

    // الماستر بلاي ليست مضمّن في صفحة الحلقة
    private val masterRegex = Regex("""https://resources-sgp-auth\.flextv\.cc/wz/m3u8/[^"\\]+/abr\.m3u8\?auth_key=[^"\\]+""")

    // يفكّ اسم العمل ومعرّف المسلسل من مسار الحلقة
    private fun parsePath(path: String): Pair<String, String>? {
        if (!path.startsWith("episode-")) return null
        val parts = path.split("-")
        if (parts.size < 3) return null
        val seriesId = parts.last()
        if (!seriesId.all { it.isLetterOrDigit() || it == '_' }) return null
        val encTitle = parts.drop(2).dropLast(1).joinToString("-")
        val title = if (encTitle.isBlank()) ""
        else try {
            java.net.URLDecoder.decode(encTitle.replace("+", "%2B"), "UTF-8").trim()
        } catch (e: Exception) { encTitle }
        return title to seriesId
    }

    private fun parseShowList(html: String): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        val linkRegex = Regex("""href="(/ar/episodes/episode-[^"]+)""")
        val imgRegex = Regex("""<img[^>]+data-src="([^"]+)""")

        val links = linkRegex.findAll(html).map { it.groupValues[1] }.toList()
        val imgs = imgRegex.findAll(html).map { it.groupValues[1] }.toList()

        var imgIdx = 0

        for (link in links) {
            val cleanLink = link.substringBefore("?").substringBefore("#")
            val segments = cleanLink.split("-")
            if (segments.size < 3) continue

            val seriesId = segments.last().takeIf { it.all { c -> c.isLetterOrDigit() } } ?: continue
            if (seriesId.length < 3) continue

            if (seen.add(seriesId)) {
                val poster = imgs.getOrNull(imgIdx++)
                // استخرج الاسم من المسار (أوضح من title attribute الطويل)
                val cardTitle = decodeEpisodeTitle(segments) ?: "FlexTV"

                val cardUrl = "$mainUrl$link"
                results.add(
                    newTvSeriesSearchResponse(cardTitle, cardUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        // احتياطي
        if (results.isEmpty()) {
            val ogTitles = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""").findAll(html)
                .map { it.groupValues[1].trim() }.toList()
            val ogImages = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""").findAll(html)
                .map { it.groupValues[1] }.toList()

            if (ogTitles.isNotEmpty()) {
                val title = ogTitles.first()
                val ogUrlMatch = Regex("""<meta\s+property="og:url"\s+content="([^"]+)"""").find(html)
                val seriesId = ogUrlMatch?.groupValues?.get(1)?.let { url ->
                    url.substringAfterLast("/").substringBefore("?").substringBefore("#")
                        .split("-").lastOrNull()?.takeIf { it.all { c -> c.isLetterOrDigit() } }
                } ?: "1"

                if (seen.add(seriesId.toString())) {
                    results.add(newTvSeriesSearchResponse(
                        title,
                        "$mainUrl/ar/episodes/episode-1-$seriesId",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = ogImages.getOrNull(0)
                    })
                }
            }
        }

        return results
    }

    private fun decodeEpisodeTitle(segments: List<String>): String? {
        // الصيغة: episode-{N}-{title-with-dashes}-{seriesId}
        if (segments.size < 4) return null
        val enc = segments.drop(2).dropLast(1).joinToString("-")
        if (enc.isBlank()) return null
        return try {
            java.net.URLDecoder.decode(enc.replace("+", "%2B"), "UTF-8")
                .replace("-", " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        } catch (e: Exception) {
            enc.replace("-", " ").trim()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val res = app.get("$mainUrl/${request.data}", referer = mainUrl).text
            val items = parseShowList(res)
            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val res = app.get("$mainUrl/ar", referer = mainUrl).text
            val all = parseShowList(res)
            val q = query.trim().lowercase()
            if (q.isBlank()) all else all.filter { it.name.lowercase().contains(q) }
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val res = app.get(url, referer = mainUrl).text

            val pathFromUrl = url.substringAfter("/ar/episodes/")
            val parsed = parsePath(pathFromUrl)
            val seriesId = parsed?.second ?: return null
            val urlTitle = parsed?.first

            // العنوان: من og:title ونظّفه
            val ogTitle = Regex("""<meta\s+property="og:title"\s+content="([^"]+)""")
                .find(res)?.groupValues?.get(1)?.trim()
            val h1 = Regex("""<h[12][^>]*>([^<]+)</h[12]>""").find(res)?.groupValues?.get(1)?.trim()
            val resolvedTitle = (h1?.takeIf { it.isNotBlank() }
                ?: ogTitle?.takeIf { it.isNotBlank() }
                ?: urlTitle?.takeIf { it.isNotBlank() }
                ?: "FlexTV")
            // تنظيف "مشاهدة X الحلقة 1 مجاناً HD | FlexTV" -> X
            val title = resolvedTitle
                .replace(Regex("""^مشاهدة\s+"""), "")
                .replace(Regex("""\s+الحلقة\s+\d+\s+.*$"""), "")
                .replace(Regex("""\s*\|\s*FlexTV$"""), "")
                .replace(Regex("""\s+-\s+FlexTV$"""), "")
                .trim()
                .ifBlank { resolvedTitle.trim() }

            val eps = mutableListOf<Episode>()
            val seenEp = mutableSetOf<Int>()
            for (m in episodePathRegex.findAll(res)) {
                val p = m.groupValues[1].substringBefore("#")
                val pParsed = parsePath(p) ?: continue
                if (pParsed.second != seriesId) continue
                val pParts = p.split("-")
                val pNo = if (pParts.size >= 3 && pParts[0] == "episode") pParts[1].toIntOrNull() else null
                if (pNo == null || pNo < 1) continue
                if (seenEp.add(pNo)) {
                    eps.add(newEpisode("$mainUrl/ar/episodes/$p") {
                        episode = pNo; name = "الحلقة $pNo"
                    })
                }
            }
            // احتياطي
            if (eps.isEmpty() && urlTitle.isNullOrBlank()) {
                try {
                    val home = app.get("$mainUrl/ar", referer = mainUrl).text
                    for (m in episodePathRegex.findAll(home)) {
                        val p = m.groupValues[1]
                        val pParsed = parsePath(p) ?: continue
                        if (pParsed.second != seriesId) continue
                        val fullUrl = "$mainUrl/ar/episodes/$p"
                        val res2 = app.get(fullUrl, referer = mainUrl).text
                        for (m2 in episodePathRegex.findAll(res2)) {
                            val p2 = m2.groupValues[1].substringBefore("#")
                            val p2Parsed = parsePath(p2) ?: continue
                            if (p2Parsed.second != seriesId) continue
                            val p2Parts = p2.split("-")
                            val pNo = if (p2Parts.size >= 3 && p2Parts[0] == "episode") p2Parts[1].toIntOrNull() else null
                            if (pNo == null || pNo < 1) continue
                            if (seenEp.add(pNo)) {
                                eps.add(newEpisode("$mainUrl/ar/episodes/$p2") {
                                    episode = pNo; name = "الحلقة $pNo"
                                })
                            }
                        }
                        if (eps.isNotEmpty()) break
                    }
                } catch (_: Exception) {}
            }
            if (eps.isEmpty()) {
                eps.add(newEpisode(url) { episode = 1; name = "الحلقة 1" })
            }
            val posterUrl = Regex("""<meta\s+property="og:image"\s+content="([^"]+)""")
                .find(res)?.groupValues?.get(1)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                plot = Regex("""<meta name="description" content="([^"]+)""").find(res)?.groupValues?.get(1)
            }
        } catch (e: Exception) { null }
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

            callback(
                newExtractorLink(name, "FlexTV (متعدد الجودات)", master, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("1080p")
                }
            )
            true
        } catch (e: Exception) { false }
    }
}
