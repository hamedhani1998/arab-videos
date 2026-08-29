package com.nartodrama.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// /api/local_search item (same shape as Drama4All)
private data class SearchItem(
    val cover: String? = null,
    val description: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int = 0,
    val slug: String? = null,
    val title: String? = null,
    val rating: Double? = null,
    val genres: List<String>? = null,
)

// /api/episode/{slug}/{ep} — narto episodes return real video_url (token-bound short TTL)
private data class EpisodeItem(
    @JsonProperty("video_url") val videoUrl: String? = null,
    val subs: List<SubtitleItem>? = null,
)

private data class SubtitleItem(
    val lang: String? = null,
    val url: String? = null,
)

class NartoDramaProvider : MainAPI() {
    override var name = "Narto Drama"
    override var mainUrl = "https://drama4all.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "list/trending" to "الأكثر مشاهدة",
        "list/recent" to "أضيف حديثاً",
        "list/all" to "المكتبة",
    )

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val s = slug ?: return null
        // هذا المصدر مسئول عن محتوى narto-drama (nt_)؛ محتوى دراما للجميع يعالج في مصدر Drama4All
        if (!s.startsWith("nt_")) return null
        val t = title ?: return null
        return newTvSeriesSearchResponse(t, "$mainUrl/series/$s", TvType.TvSeries) {
            posterUrl = cover
            episodes = totalEpisodes.coerceAtLeast(1)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Drama4All homepage embeds LIBRARY JSON — pick nt_ items
        return try {
            val url = "$mainUrl/${request.data}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("script").filter { el -> el.html().contains("const LIBRARY") }
                .flatMap { el ->
                    val html = el.html()
                    val start = html.indexOf('[')
                    val end = html.lastIndexOf(']')
                    if (start < 0 || end <= start) return@flatMap emptyList()
                    try {
                        val list = mapper.readValue(
                            html.substring(start, end + 1),
                            object : com.fasterxml.jackson.core.type.TypeReference<List<SearchItem>>() {}
                        )
                        list.mapNotNull { it.toSearchResponse() }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val res = app.get("$mainUrl/api/local_search?q=${java.net.URLEncoder.encode(query, "UTF-8")}", referer = mainUrl).text
            val items: List<SearchItem> = try {
                mapper.readValue(
                    res,
                    object : com.fasterxml.jackson.core.type.TypeReference<List<SearchItem>>() {}
                )
            } catch (e: Exception) {
                return null
            }
            items.mapNotNull { it.toSearchResponse() }
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

            item.subs?.forEach { s ->
                val lang = s.lang ?: return@forEach
                val subUrl = s.url ?: return@forEach
                if (subUrl.isNotBlank()) {
                    try {
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    } catch (e: Exception) {}
                }
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "Narto HLS",
                    url = vUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = mainUrl
                    quality = getQualityFromName("1080p")
                    headers = mapOf("Referer" to mainUrl)
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}