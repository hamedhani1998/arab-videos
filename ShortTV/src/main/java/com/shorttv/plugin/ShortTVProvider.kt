package com.shorttv.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
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

    // رابط صفحة المسلسل الكامل: /ar/drama/{slug}-{showId}
    private val dramaLinkRegex = Regex("""/ar/drama/([^"'\\?]+)""")
    // رابط صفحة الحلقة: /ar/episode/{slug}-{showId}-{ep}
    private val episodeLinkRegex = Regex("""/ar/episode/([^"'\\?]+)""")
    // poster: <img data-src="https://akamai-static.shorttv.live/images/cover/.../xxx.jpg"
    private val posterRegex = Regex("""data-src="(https://akamai-static\.shorttv\.live/images/cover/[^"]+\.jpg)""")
    // عنوان بطاقة العرض
    private val cardTitleRegex = Regex("""<p class="card-title"[^>]*>([^<]+)</p>""")

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&")

    // فك ترميز الاسم من المسار
    private fun cleanName(encoded: String): String {
        var name = try {
            java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            encoded
        }
        name = name.replace("---", " ").replace("-", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        name = name.removePrefix("مدبلج").removeSuffix("مدبلج").trim()
        return name
    }

    // من مسار /ar/drama/{slug}-{showId} أو /ar/episode/{slug}-{showId}-{ep} نستخرج showId
    // المسار قد يكون URL-encoded وقد يحتوي على كلمات مثل "مدبلج" ملتصقة بالكلمة الأخيرة
    private fun showIdFromPath(rawPath: String): String? {
        val path = try { java.net.URLDecoder.decode(rawPath, "UTF-8") } catch (e: Exception) { rawPath }
        val parts = path.split("-")
        if (parts.isEmpty()) return null
        // المعرّف الرقمي في آخر مقطع (أو قبله لو كان "مدبلج")
        for (i in parts.indices.reversed()) {
            val seg = parts[i]
            if (seg.length >= 3 && seg.all(Char::isDigit)) return seg
        }
        return null
    }

    private fun extractNuxtData(html: String): JsonNode? {
        val regex = Regex("""<script type="application/json"[^>]*id="__NUXT_DATA__"[^>]*>([\s\S]*?)</script>""")
        val m = regex.find(html) ?: return null
        return try { mapper.readTree(m.groupValues[1]) } catch (e: Exception) { null }
    }

    // حلّ مرجع __NUXT_DATA__: مرجع رقمي يقفز إلى entry آخر، أو null
    private fun resolveRef(data: JsonNode, idx: Int, maxHops: Int = 6): JsonNode? {
        var cur: JsonNode? = data.get(idx)
        var hops = 0
        while (cur != null && cur.isNumber && hops < maxHops) {
            cur = data.get(cur.asInt())
            hops++
        }
        return cur
    }

    private data class ShowInfo(
        val shortPlayId: String,
        val title: String,
        val poster: String?,
        val description: String?,
        val lockBegin: Int,
        val episodeList: List<EpisodeInfo>,
    )

    private data class EpisodeInfo(
        val episodeNum: Int,
        val videoUrl480: String?,
        val videoUrl720: String?,
        val videoUrl1080: String?,
        val isFree: Boolean,
    )

    // يحلّل __NUXT_DATA__ في صفحة الحلقة ويستخرج معلومات المسلسل + قائمة الحلقات
    private fun parseShowFromNuxt(data: JsonNode): ShowInfo? {
        // ابحث عن كائن يحوي shortPlayId و episodeList
        for (i in 0 until data.size()) {
            val e = data.get(i)
            if (e == null || e.isNull || !e.isObject) continue
            if (!e.has("shortPlayId") || !e.has("episodeList")) continue
            val playId = resolveRef(data, e.get("shortPlayId").asInt())?.asText() ?: continue
            val titleNode = resolveRef(data, e.get("lanShortPlayName")?.asInt() ?: -1)
            val title = titleNode?.asText() ?: playId
            val posterNode = resolveRef(data, e.get("lanCoverId")?.asInt() ?: -1)
            val poster = posterNode?.asText()
            val descNode = resolveRef(data, e.get("lanShortPlayDescription")?.asInt() ?: -1)
            val desc = descNode?.asText()
            val lockNode = resolveRef(data, e.get("lockBegin")?.asInt() ?: -1)
            val lockBegin = lockNode?.asInt() ?: 0
            val epListNode = data.get(e.get("episodeList").asInt())
            val eps = if (epListNode != null && epListNode.isArray) {
                epListNode.mapNotNull { ref ->
                    val epObj = resolveRef(data, ref.asInt()) ?: return@mapNotNull null
                    val num = epObj.get("episodeNum")?.asInt() ?: return@mapNotNull null
                    val isFree = num <= lockBegin
                    val encNode = epObj.get("encryptedVideoUrl")
                    var u480: String? = null; var u720: String? = null; var u1080: String? = null
                    if (encNode != null && encNode.isInt) {
                        val str = resolveRef(data, encNode.asInt())?.asText()
                        if (str != null) {
                            try {
                                val obj = mapper.readTree(str)
                                u480 = obj.get("video_480")?.asText()
                                u720 = obj.get("video_720")?.asText()
                                u1080 = obj.get("video_1080")?.asText()
                            } catch (_: Exception) {}
                        }
                    }
                    EpisodeInfo(num, u480, u720, u1080, isFree)
                }
            } else emptyList()
            return ShowInfo(playId, title, poster, desc, lockBegin, eps)
        }
        return null
    }

    private fun parseShowList(html: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        val posters = posterRegex.findAll(html).map { it.groupValues[1] }.toList()
        var posterIdx = 0
        val titles = cardTitleRegex.findAll(html).map { it.groupValues[1].trim() }.toList()
        var titleIdx = 0

        for (m in dramaLinkRegex.findAll(html)) {
            val path = m.groupValues[1]
            val showId = showIdFromPath(path) ?: continue
            if (seen.add(showId)) {
                val poster = posters.getOrNull(posterIdx++)
                val cardTitle = titles.getOrNull(titleIdx++)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                val cleanTitle = cardTitle?.takeIf { it.isNotBlank() } ?: cleanName(path)
                results.add(
                    newTvSeriesSearchResponse(
                        cleanTitle,
                        "$mainUrl/ar/drama/$path",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster
                    }
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

    // البحث على ShortTV يعمل من جهة العميل فقط (لا API خارجي)، لذا نُعيد قائمة فارغة.
    override suspend fun search(query: String): List<SearchResponse>? = emptyList()

    // data المخزّن: "{shortPlayId}|{episodeNum}"
    private fun dataForEpisode(shortPlayId: String, ep: Int): String = "$shortPlayId|$ep"

    override suspend fun load(url: String): LoadResponse? {
        return try {
            // نستخرج showId من URL (يمكن أن يكون /ar/drama/... أو /ar/episode/...)
            val path = url.substringAfter("/ar/").substringAfter("/")
            val showId = showIdFromPath(path) ?: return null
            // صفحة الحلقة الأولى تحوي بيانات المسلسل كاملة في __NUXT_DATA__
            val res = app.get("$mainUrl/ar/episode/$showId-1", referer = mainUrl).text
            val nuxt = extractNuxtData(res) ?: return null
            val show = parseShowFromNuxt(nuxt) ?: return null
            if (show.episodeList.isEmpty()) return null

            val eps = show.episodeList.map { ep ->
                newEpisode(dataForEpisode(show.shortPlayId, ep.episodeNum)) {
                    episode = ep.episodeNum
                    name = if (ep.isFree) "الحلقة ${ep.episodeNum}" else "🔒 الحلقة ${ep.episodeNum}"
                }
            }
            newTvSeriesLoadResponse(show.title, url, TvType.TvSeries, eps.sortedBy { it.episode }) {
                this.posterUrl = show.poster
                this.plot = show.description
            }
        } catch (e: Exception) {
            null
        }
    }

    // استخراج معلومات الحلقة من __NUXT_DATA__ لمعرّف معيّن
    private fun findEpisodeUrls(html: String, episodeNum: Int): Triple<String?, String?, String?>? {
        val nuxt = extractNuxtData(html) ?: return null
        for (i in 0 until nuxt.size()) {
            val e = nuxt.get(i)
            if (e == null || !e.isObject || !e.has("shortPlayId")) continue
            val eps = nuxt.get(e.get("episodeList").asInt())
            if (eps == null || !eps.isArray) continue
            for (ref in eps) {
                val epObj = resolveRef(nuxt, ref.asInt()) ?: continue
                val num = epObj.get("episodeNum")?.asInt() ?: continue
                if (num != episodeNum) continue
                val encNode = epObj.get("encryptedVideoUrl") ?: continue
                if (!encNode.isInt) continue
                val str = resolveRef(nuxt, encNode.asInt())?.asText() ?: continue
                return try {
                    val obj = mapper.readTree(str)
                    Triple(
                        obj.get("video_480")?.asText(),
                        obj.get("video_720")?.asText(),
                        obj.get("video_1080")?.asText()
                    )
                } catch (_: Exception) { null }
            }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parts = data.split("|")
            if (parts.size != 2) return false
            val shortPlayId = parts[0]
            val ep = parts[1].toIntOrNull() ?: return false
            // صفحة الحلقة الأولى للمسلسل تكفي لاستخراج كل الحلقات (تحوي قائمة كاملة)
            val res = app.get("$mainUrl/ar/episode/${shortPlayId}-1", referer = mainUrl).text
            val urls = findEpisodeUrls(res, ep) ?: return false
            var found = false
            urls.third?.let { url ->
                callback(newExtractorLink(name, "1080p", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("1080p")
                })
                found = true
            }
            urls.second?.let { url ->
                callback(newExtractorLink(name, "720p", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("720p")
                })
                found = true
            }
            urls.first?.let { url ->
                callback(newExtractorLink(name, "480p", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl; quality = getQualityFromName("480p")
                })
                found = true
            }
            found
        } catch (e: Exception) {
            false
        }
    }
}
