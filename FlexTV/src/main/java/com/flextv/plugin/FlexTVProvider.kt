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

    private val episodePathRegex = Regex("""/ar/episodes/([^"'\?#\s]+)""")
    private val masterRegex = Regex("""https://resources-sgp-auth\.flextv\.cc/wz/m3u8/[^"\\]+/abr\.m3u8\?auth_key=[^"\\]+""")

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

    // استخراج الصور من الصفحة الرئيسية
    private fun parseShowList(html: String): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        // نوع البطاقة الأول: كاروسيل - الصورة داخل <a> بسمة src
        val carouselRe = Regex("""<a\s+href="(/ar/episodes/episode-[^"]+)"[^>]*title="([^"]*)"[^>]*>(?:(?!</a>).)*?<img[^>]*?\bsrc="(http[^"]+)""")
        // نوع البطاقة الثاني: شبكة - كل <li class="item ..."> يحوي صورة (data-src أو src)
        val gridLiRe = Regex("""<li\s+class="item(?:\s+[^"]*)?"[\s\S]*?</li>""")
        val imgInBlock = Regex("""<img[^>]*?(?:data-src|src)="(http[^"]+)"""")
        val anchorInBlock = Regex("""<a\s+href="(/ar/episodes/episode-[^"]+)"""")
        val nameInBlock = Regex("""<meta itemprop="name"\s+content="([^"]+)"""")

        fun cleanTitle(cardTitle: String): String {
            var t = cardTitle.trim()
            t = t.replace(Regex("""^شاهد\s+حلقات\s+"""), "")
            t = t.replace(Regex("""\s+كاملة\s+أونلاين.*$"""), "")
            t = t.replace(Regex("""\s*[-–|]\s*FlexTV\s*$"""), "")
            t = t.replace(Regex("""\s+- بجودة عالية$"""), "")
            return t.trim()
        }

        fun processLink(link: String, poster: String?, titleHint: String?): Boolean {
            val cleanLink = link.substringBefore("?").substringBefore("#")
            val segments = cleanLink.split("-")
            if (segments.size < 3) return false
            val seriesId = segments.last().takeIf { it.all { c -> c.isLetterOrDigit() } } ?: return false
            if (seriesId.length < 3 || !seen.add(seriesId)) return false
            val titleFromMeta = titleHint?.takeIf { it.isNotBlank() }
            val fallback = decodeEpisodeTitle(segments)
            val cardTitle = cleanTitle(titleFromMeta ?: fallback ?: "FlexTV")
            results.add(newTvSeriesSearchResponse(cardTitle, "$mainUrl$link", TvType.TvSeries) {
                this.posterUrl = poster
            })
            return true
        }

        // الكاروسيل
        for (m in carouselRe.findAll(html)) {
            val poster = m.groupValues[3]
            if (poster.isBlank()) continue
            val titleHint = m.groupValues[2].takeIf { it.isNotBlank() }
            processLink(m.groupValues[1], poster, titleHint)
        }

        // الشبكة
        for (m in gridLiRe.findAll(html)) {
            val block = m.value
            val link = anchorInBlock.find(block)?.groupValues?.get(1) ?: continue
            val poster = imgInBlock.find(block)?.groupValues?.get(1)
            val name = nameInBlock.find(block)?.groupValues?.get(1)
            processLink(link, poster, name)
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

            // العنوان من h1 أو og:title ونظّفه
            val ogTitle = Regex("""<meta\s+property="og:title"\s+content="([^"]+)""")
                .find(res)?.groupValues?.get(1)?.trim()
            val h1 = Regex("""<h[12][^>]*>([^<]+)</h[12]>""").find(res)?.groupValues?.get(1)?.trim()
            val resolvedTitle = (h1?.takeIf { it.isNotBlank() }
                ?: ogTitle?.takeIf { it.isNotBlank() }
                ?: urlTitle?.takeIf { it.isNotBlank() }
                ?: "FlexTV")
            val title = resolvedTitle
                .replace(Regex("""^مشاهدة\s+"""), "")
                .replace(Regex("""\s+الحلقة\s+\d+\s+.*$"""), "")
                .replace(Regex("""\s*\|\s*FlexTV$"""), "")
                .replace(Regex("""\s+-\s+FlexTV$"""), "")
                .trim()
                .ifBlank { resolvedTitle.trim() }

            // الصورة من og:image
            val posterUrl = Regex("""<meta\s+property="og:image"\s+content="([^"]+)""")
                .find(res)?.groupValues?.get(1)

            // استخراج الحلقات
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
