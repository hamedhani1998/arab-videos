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
    // تنسيق JSON مضمّن في سكربت: video_1080\":\"URL  (كلتا علامتي الاقتباس يسبقها \)
    private val video1080Regex = Regex("""video_1080\\*"\s*:\s*\\*"(https?://[^"\\]+)""")
    private val video720Regex = Regex("""video_720\\*"\s*:\s*\\*"(https?://[^"\\]+)""")
    private val video480Regex = Regex("""video_480\\*"\s*:\s*\\*"(https?://[^"\\]+)""")

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&")

    // فكّ ترميز الاسم (ارجع إلى عربي) وتنظيفه
    private fun cleanName(encoded: String): String {
        var name = try {
            java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            encoded
        }
        name = name.replace("---", " ").replace("-", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        // إزالة وسم "مدبلج" في أول/آخر الاسم
        name = name.removePrefix("مدبلج").removeSuffix("مدبلج").trim()
        return name
    }

    private fun parseShowList(html: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenKey = mutableSetOf<String>()
        for (m in episodeLinkRegex.findAll(html)) {
            val path = m.groupValues[1]
            // path = {name}-{showId}-{ep}
            val parts = path.split("-")
            if (parts.size < 3) continue
            val ep = parts.last()
            val showId = parts[parts.size - 2]
            if (!ep.all { it.isDigit() } || !showId.all { it.isDigit() }) continue
            val showNameEnc = parts.dropLast(2).joinToString("-")
            if (seenKey.add(showId)) {
                results.add(
                    newTvSeriesSearchResponse(
                        cleanName(showNameEnc),
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
            val path = url.substringAfter("/ar/episode/")
            val parts = path.split("-")
            val currentShowId = if (parts.size >= 3) parts[parts.size - 2] else null

            // الاسم من الـ URL (أفضل من <title> الذي قد يحوي ضجيجاً)
            val encodedName = if (parts.size >= 3) parts.dropLast(2).joinToString("-") else ""
            val title = cleanName(encodedName)
                .ifBlank { Regex("""<title>([^<]+)</title>""").find(res)?.groupValues?.get(1)?.trim() ?: "ShortTV" }

            val eps = mutableListOf<Episode>()
            val seenEp = mutableSetOf<Int>()
            for (m in episodeLinkRegex.findAll(res)) {
                val p = m.groupValues[1]
                val pParts = p.split("-")
                if (pParts.size < 3) continue
                val pEp = pParts.last().toIntOrNull() ?: continue
                val pShowId = pParts[pParts.size - 2]
                // نعرض حلقات نفس العمل فقط
                if (currentShowId != null && pShowId != currentShowId) continue
                if (seenEp.add(pEp)) {
                    eps.add(
                        newEpisode("/ar/episode/$p") {
                            episode = pEp
                            name = "الحلقة $pEp"
                        }
                    )
                }
            }
            if (eps.isEmpty()) return null
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
