package com.reelree.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class ReelreeProvider : MainAPI() {
    override var name = "Reelree"
    override var mainUrl = "https://reelree.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "explore/" to "أحدث المسلسلات",
        "explore/?sort=trending" to "الأكثر مشاهدة",
        "tag/metarjam-arabi/" to "مترجم عربي",
        "tag/mudabalaj-arabi/" to "مدبلج عربي",
        "tag/lang-en/" to "بالإنجليزية",
    )

    private fun parseCards(doc: Document, typ: TvType = TvType.TvSeries): List<SearchResponse> {
        return doc.select("article.rr-card").mapNotNull { card ->
            try {
                val a = card.selectFirst("a.rr-card-link") ?: card.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val title = card.selectFirst("h3")?.text()?.trim()
                    ?: a.attr("aria-label")?.trim()
                    ?: a.attr("title")?.trim()
                    ?: return@mapNotNull null
                val poster = card.selectFirst("img.rr-poster")?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }
                }?.ifBlank { null }

                newMovieSearchResponse(title, href, typ) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val base = request.data
            val url = when {
                base.contains("?") -> {
                    // explore/?sort=trending → explore/page/N/?sort=trending
                    val (path, q) = base.split("?", limit = 2)
                    if (page > 1) "$mainUrl/$path/page/$page/?$q" else "$mainUrl/$path/?$q"
                }
                else -> if (page > 1) "$mainUrl/${base}page/$page/" else "$mainUrl/$base"
            }
            val doc = app.get(url, referer = mainUrl).document
            newHomePageResponse(request.name, parseCards(doc))
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}", referer = mainUrl).document
            parseCards(doc)
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            // عنصر المشغّل يحمل كل البيانات (بالعربية واﻹنجليزية في صفحتي الشرح)
            val watch = doc.selectFirst("[data-media]")

            val title = watch?.attr("data-title")?.trim()
                ?.let { cleanTitle(it) }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.let { cleanTitle(it) }
                ?: doc.selectFirst("h1, [data-title]")?.text()?.trim()
                ?: return null

            val poster = watch?.attr("data-poster")?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

            val mediaTemplate = watch?.attr("data-media")?.trim()
            val episodes = watch?.attr("data-episodes")?.trim()?.toIntOrNull() ?: 1
            val dataSource = watch?.attr("data-source")?.trim().orEmpty()

            val eps = (1..episodes).map { n ->
                newEpisode("$mediaTemplate|$n|$dataSource") {
                    episode = n
                    name = "الحلقة $n"
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = listOf(dataSource).filter { it.isNotBlank() }
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
            // data: "<mediaTemplate>|<ep>|<source>"
            val parts = data.split("|", limit = 3)
            val template = parts.getOrNull(0) ?: return false
            val ep = parts.getOrNull(1) ?: return false
            val url = template.replace("%EP%", ep)
            if (url.isBlank() || !url.startsWith("http")) return false

            // معظم المصادر تعيد m3u8 (أو 302 → m3u8) — نتعامل كـ HLS
            callback(
                newExtractorLink(name, "Reelree $ep", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl
                    quality = getQualityFromName("720p")
                }
            )
            true
        } catch (e: Exception) { false }
    }

    /** ينزع البادئات/اللواحق الشائعة من og:title مثل "مشاهدة مسلسل … كامل جميع الحلقات". */
    private fun cleanTitle(t: String): String {
        var s = t
            .replace(Regex("""مشاهدة\s*"""), "")
            .replace(Regex("""\s*[-–—|:]\s*.*$"""), "")           // "ابنة المدير — مسلسل مترجم…"
            .replace(Regex("""\s*(كامل|جميع الحلقات|مسلسل|حلقات كاملة).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (s.length < 2) s = t.trim()
        return s
    }
}
