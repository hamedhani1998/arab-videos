package com.arabx.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ArabxCamProvider : MainAPI() {
    override var name = "ArabX"
    override var mainUrl = "https://www.arabx.cam"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest-updates/" to "احدث الافلام",
        "top-rated/" to "افضل الافلام",
        "most-popular/" to "الاعلى مشاهدة",
        "categories/سكس-مترجم/" to "مترجم",
        "categories/سكس-امهات-مترجم/" to "أمهات",
        "categories/سكس-محارم/" to "محارم",
        "categories/سكس-اخوات/" to "اخوات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim() ?: a.attr("title") ?: ""
                    val poster = item.selectFirst("img.thumb")?.let { it.attr("data-original").ifBlank { it.attr("src") } }
                    val rating = item.selectFirst("div.rating")?.text()?.trim()?.replace("%", "")
                    newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = poster
                        if (!rating.isNullOrBlank()) this.score = Score.from(rating, 100)
                    }
                } catch (e: Exception) { null }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/search/$query/", referer = mainUrl).document
            doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim() ?: a.attr("title") ?: ""
                    val poster = item.selectFirst("img.thumb")?.let { it.attr("data-original").ifBlank { it.attr("src") } }
                    newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.map { it.trim() }?.take(6)
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data, referer = mainUrl).document
            val html = doc.html()

            // Method 1: iframe embed - PRIMARY for this site (playeriz.com)
            val iframe = doc.selectFirst("div.embed-wrap iframe")
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.isNotBlank()) {
                    // Load the iframe page to extract video URL
                    val iframeDoc = app.get(iframeUrl, referer = data).document
                    val iframeHtml = iframeDoc.html()

                    // Try to find video URL in iframe
                    val videoUrl = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(iframeHtml)?.groupValues?.get(1)
                    if (videoUrl != null) {
                        callback(newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                            this.referer = iframeUrl
                            this.quality = getQualityFromName("720p")
                        })
                        return true
                    }

                    // Try m3u8
                    val m3u8Url = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(iframeHtml)?.groupValues?.get(1)
                    if (m3u8Url != null) {
                        callback(newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = iframeUrl
                            this.quality = getQualityFromName("720p")
                        })
                        return true
                    }

                    // Fallback: use loadExtractor
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                    return true
                }
            }

            // Method 2: contentUrl in JSON-LD
            val contentUrl = Regex(""""contentUrl"\s*:\s*"([^"]+\.mp4[^"]*)""").find(html)?.groupValues?.get(1)
            if (!contentUrl.isNullOrBlank()) {
                callback(newExtractorLink(name, name, contentUrl, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName("720p")
                })
                return true
            }

            return false
        } catch (e: Exception) { return false }
    }
}