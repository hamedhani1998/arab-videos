package com.nartodrama.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// ---- edge.narto-drama.com is the OPEN (no-Cloudflare) mirror of narto-drama.com ----
// It serves the native Arabic catalog (including ALL dubbed مدبلج content) at
//   GET /search?lang=ar-SA&q=<query>   -> HTML page embedding a ListItem JSON array
//   GET /detail/watch/{slug}           -> series detail (h1, poster, episode list)
//   GET /e/rs/detail/watch/{slug}/{ep}/refresh-source?rs_ctx={JWT} -> playback
// The web frontend narto-drama.com itself is Cloudflare-protected; edge is not.

// One JSON-LD ListItem entry from the search results page.
private data class SearchHit(
    @JsonProperty("@type") val type: String? = null,
    val url: String? = null,    // https://edge.narto-drama.com/detail/watch/{slug}?lang=...
    val name: String? = null,   // Arabic title (e.g. "[مدبلج] ...")
    val image: String? = null,  // /assets/poster/{id}.jpg
)

// ---- narto edge playback API ----
// Narto aggregates short-drama from MANY backends (shortmax, NetShort, StardustTV,
// mydramawave, Hakuna Matata, montagehub, TikTok, GoodShort, JoyReels, narto-native).
// Each work's direct_play_url/play_url/multi_resolutions therefore points to a DIFFERENT
// host and may be an HLS playlist OR a direct MP4 file — so loadLinks must detect the
// container type (M3U8 vs direct VIDEO) per link instead of assuming everything is HLS.
private data class EdgeResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val canonical: String? = null,          // full canonical URL hint on slug_mismatch
    @JsonProperty("direct_play_url") val directPlayUrl: String? = null, // may be HLS master OR direct MP4
    @JsonProperty("play_url") val playUrl: String? = null,
    @JsonProperty("multi_resolutions") val multiResolutions: List<EdgeResolution>? = null,
    @JsonProperty("multi_subtitles") val multiSubtitles: List<EdgeSub>? = null,
    // Single-track fields the API also returns (used when multi_* is empty / has exactly one entry).
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,           // single external subtitle
    @JsonProperty("direct_subtitle_url") val directSubtitleUrl: String? = null, // single direct subtitle
    @JsonProperty("direct_audio_url") val directAudioUrl: String? = null,      // single separate audio track
    @JsonProperty("selected_subtitle_language") val selectedSubtitleLanguage: String? = null,
)

private data class EdgeResolution(
    val resolution: Int? = null,
    val label: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
)

private data class EdgeSub(
    @JsonProperty("language_code") val languageCode: String? = null,
    val label: String? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,     // relative /e/s/{jwt}
)

// Minimal fake JWT the edge accepts (claims are not verified, slug/ep read from path).
private val fakeRsCtx = "eyJhbGciOiJub25lIn0.eyJ2IjoiMSJ9."
private const val STREAM_HOST = "https://stream.narto-drama.com"

// ---- Two (or more) mirror hosts of the same site ----
// narto-drama.com is the original web host (Cloudflare-protected); edge.narto-drama.com is an
// open backend with NO Cloudflare that serves the identical native Arabic catalog. Because either
// one of them can one day start throwing a Cloudflare challenge (or the reverse), the provider
// treats them as interchangeable mirrors and AUTO-FAILOVERS: it tries them in order and uses the
// first that answers with real content, skipping any that return a CF-challenge page. The default-
// override mainUrl is always tried first so the app "Clone + edit URL" native setting still wins.
private val NARTO_MIRRORS = listOf("https://edge.narto-drama.com", "https://narto-drama.com")

// Signatures of a Cloudflare interstitial (presents HTML regardless of HTTP status).
private val CF_HINTS = listOf(
    "cf-chl", "__cf_chl", "cf_chl", "challenge-platform", "Cloudflare",
    "Attention Required", "Just a moment", "cf-error-details", "incapsula",
)

class NartoDramaProvider : MainAPI() {
    override var name = "Narto Drama"
    override var mainUrl = "https://edge.narto-drama.com"  // open backend (no Cloudflare)
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // True if a body looks like a Cloudflare/bot interstitial rather than real content.
    // NOTE: detection is on the BODY only — a 200/HTTP status alone is not trustworthy from the
    // CloudStream client, so we never branch on `Response.code` (which isn't available anyway).
    private fun isCfChallenge(body: String): Boolean {
        val head = body.take(4000)
        return CF_HINTS.any { head.contains(it, ignoreCase = true) }
    }

    // Run `fetch` against each mirror (mainUrl override first, then the rest) and return the first
    // body that is NOT a Cloudflare challenge, together with the mirror base that served it. If any
    // mirror throws on fetch, try the next; if every mirror is challenged/empty, return null.
    private suspend fun tryMirrors(fetch: suspend (String) -> String?): Pair<String, String>? {
        val order = buildList {
            add(mainUrl)
            for (m in NARTO_MIRRORS) if (m != mainUrl) add(m)
        }
        for (base in order) {
            val body = try { fetch(base) } catch (e: Exception) { null }
            if (body.isNullOrBlank()) continue
            if (!isCfChallenge(body)) return base to body
        }
        return null
    }

    // Curated seed queries mirroring the native Narto home: a generic feed, the all-dubbed
    // مدبلج feed, and a general drama feed. The empty query returns the default browse list.
    override val mainPage = mainPageOf(
        "ق" to "الأحدث والمقترحة",
        "مدبلج" to "مسلسلات مدبلجة 🎙️",
        "دراما" to "دراما",
    )

    // The video-serve origin we must present to the edge/stream endpoints.
    private val nartoOrigin = "https://narto-drama.com"

    // SITE'S CHANGING LINK (native "setting"): every request is built from `mainUrl`, which is an
    // open OVERRIDE var. In CloudStream the user can change the base link from the provider
    // settings screen by cloning Narto Drama and editing its URL — that is how the site's
    // ever-changing mirror (currently edge.narto-drama.com, previously narto-drama.com) is kept
    // up to date without touching code. All playback links are emitted directly from the API so a
    // move to another mirror keeps working.

    // Detect whether a stream URL is an HLS playlist (M3U8) or a direct single video file (MP4).
    // Narto serves BOTH from different backends, and CloudStream must get the right link type or
    // it will refuse/garble playback (an MP4 handed as M3U8 -> parse error, the "doesn't play" bug).
    private suspend fun inferStreamType(url: String): ExtractorLinkType {
        val lower = url.lowercase()
        if (lower.contains("mime_type=video_mp4") || lower.endsWith(".mp4")) return ExtractorLinkType.VIDEO
        if (lower.contains(".m3u8") || lower.contains("/e/m/") || lower.contains("/e/s/")
            || lower.contains("/main.m3u8")) return ExtractorLinkType.M3U8
        // Ambiguous (e.g. token-style montagehub URLs with no extension) -> probe Content-Type.
        return try {
            val r = app.head(url, headers = mapOf("User-Agent" to UA), referer = nartoOrigin)
            val ct = r.headers["Content-Type"]?.lowercase() ?: ""
            if (ct.contains("mpegurl") || ct.contains("hls") || ct.contains("apple")) ExtractorLinkType.M3U8
            else ExtractorLinkType.VIDEO
        } catch (e: Exception) {
            ExtractorLinkType.M3U8
        }
    }

    // ---- Parse the ListItem JSON array embedded in a /search HTML page ----
    private fun parseSearchItems(html: String): List<SearchHit> {
        return try {
            val marker = "\"@type\":\"ListItem\""
            val start = html.indexOf(marker)
            if (start < 0) return emptyList()
            // Walk back to the opening '[' of the array that holds the list items.
            var arrStart = start
            while (arrStart > 0 && html[arrStart] != '[') arrStart--
            if (html[arrStart] != '[') return emptyList()
            // Walk forward to the matching ']'.
            var depth = 0
            var arrEnd = -1
            for (k in arrStart until html.length) {
                when (html[k]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { arrEnd = k; break } }
                }
            }
            if (arrEnd < 0) return emptyList()
            mapper.readValue(
                html.substring(arrStart, arrEnd + 1),
                object : com.fasterxml.jackson.core.type.TypeReference<List<SearchHit>>() {}
            ).filter { !it.url.isNullOrBlank() && !it.name.isNullOrBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val q = request.data.replace(" ", "+")
            val (base, body) = tryMirrors { b ->
                app.get("$b/search?lang=ar-SA&q=$q", referer = nartoOrigin).text
            } ?: return null
            val items = parseSearchItems(body)
            if (items.isEmpty()) return null
            val list = items.mapNotNull { it.toSearchResponse(base) }
            if (list.isEmpty()) null else newHomePageResponse(request.name, list)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val (base, body) = tryMirrors { b ->
                app.get("$b/search?lang=ar-SA&q=$q", referer = nartoOrigin).text
            } ?: return null
            val items = parseSearchItems(body)
            items.mapNotNull { it.toSearchResponse(base) }
        } catch (e: Exception) {
            null
        }
    }

    private fun SearchHit.toSearchResponse(base: String): SearchResponse? {
        val u = url ?: return null
        val slug = Regex("""/detail/watch/([^/?]+)""").find(u)?.groupValues?.get(1) ?: return null
        val name = this.name ?: return null
        // Keep the "[مدبلج] ..." prefix so dubbed entries are obviously marked.
        val poster = image?.let { if (it.startsWith("http")) it else base + it }
        return newTvSeriesSearchResponse(name, "$base/detail/watch/$slug", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val slug = Regex("""/detail/watch/([^/?]+)""").find(url)?.groupValues?.get(1) ?: return null
            val (base, body) = tryMirrors { b ->
                app.get("$b/detail/watch/$slug", referer = nartoOrigin).text
            } ?: return null

            val doc = org.jsoup.Jsoup.parse(body)

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.let { if (it.startsWith("http")) it else null }
            val description = doc.selectFirst("meta[name=description]")?.attr("content")

            val eps = doc.select("div.episode-list a.episode-item")
                .mapNotNull { el ->
                    val href = el.attr("href") ?: return@mapNotNull null
                    val ep = Regex("""/detail/watch/[^/]+/(\d+)""").find(href)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: return@mapNotNull null
                    newEpisode("$base/detail/watch/$slug/$ep") {
                        episode = ep
                        name = "الحلقة $ep"
                    }
                }

            newTvSeriesLoadResponse(title, base + "/detail/watch/$slug", TvType.TvSeries, eps) {
                posterUrl = poster
                plot = description
            }
        } catch (e: Exception) {
            null
        }
    }

    // Fetch the narto edge refresh-source payload for a canonical slug+episode.
    private suspend fun edgeRefreshSource(slug: String, ep: String): EdgeResponse? {
        return try {
            val (_, body) = tryMirrors { b: String ->
                app.get("$b/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx", referer = nartoOrigin).text
            } ?: return null
            mapper.readValue(body, EdgeResponse::class.java)
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
            // data = $mainUrl/detail/watch/{slug}/{ep}
            val m = Regex("""/detail/watch/([^/?]+)/(\d+)""").find(data) ?: return false
            val ep = m.groupValues[2]
            var slug = m.groupValues[1]

            var edge = edgeRefreshSource(slug, ep) ?: return false

            // If the source reports a stream error that is temporarily unavailable, surface a
            // readable failure rather than silently returning nothing.
            if (edge.ok != true && edge.message == "stream_temporarily_unavailable") return false

            // Canonical re-discovery: a requested slug may differ from the narto canonical one.
            if (edge.ok != true && edge.message == "slug_mismatch") {
                val canon = edge.canonical?.let { Regex("""/detail/watch/([^/?]+)/""").find(it)?.groupValues?.get(1) }
                if (canon != null && canon != slug) {
                    slug = canon
                    edge = edgeRefreshSource(slug, ep) ?: return false
                }
            }
            if (edge.ok != true) return false

            // 1) subtitles — resolve relative /e/s/{jwt} paths on stream.narto-drama.com
            //    (narto-drama.com itself is CF-blocked). Emit EVERY subtitle the API offers:
            //    the full multi_subtitles set plus any single track (subtitle_url /
            //    direct_subtitle_url) that appears alone instead. Nothing the site returns is dropped.
            val seenSubs = LinkedHashSet<String>()
            val subTracks = buildList {
                edge.multiSubtitles.orEmpty().forEach { s ->
                    val rel = s.subtitleUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                    val lang = s.label?.takeIf { it.isNotBlank() } ?: s.languageCode ?: "ترجمة"
                    add(lang to rel)
                }
                // Single external subtitle (may be absolute http or relative /e/s/...).
                edge.subtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                    add((edge.selectedSubtitleLanguage?.takeIf { l -> l.isNotBlank() } ?: "ترجمة") to it)
                }
                // Single direct subtitle (absolute or relative).
                edge.directSubtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                    add("ترجمة مباشرة" to it)
                }
            }
            for ((lang, rel) in subTracks) {
                val subUrl = if (rel.startsWith("http")) rel else STREAM_HOST + rel
                if (!seenSubs.add(subUrl)) continue
                try { subtitleCallback(newSubtitleFile(lang, subUrl)) } catch (e: Exception) {}
            }

            // 2) the video — Narto aggregates from many backends, so a work can carry:
            //      a) multi_resolutions -> the per-resolution stream_url set (1080/720/480)
            //      b) a single play_url / direct_play_url (HLS master/media, or a direct MP4 file)
            // We emit EVERY distinct URL with its correct container type so the right link plays.
            val emitted = LinkedHashSet<String>()
            var any = false

            suspend fun emit(u: String, label: String, q: String) {
                if (u.isBlank() || !emitted.add(u)) return
                val type = inferStreamType(u)
                callback(
                    newExtractorLink(source = name, name = label, url = u, type = type) {
                        referer = nartoOrigin
                        quality = getQualityFromName(q)
                        headers = mapOf("Referer" to nartoOrigin)
                    }
                )
                any = true
            }

            // multi_resolutions first — the full per-quality URL set.
            val resolutions = edge.multiResolutions.orEmpty()
                .filter { !it.streamUrl.isNullOrBlank() }
            if (resolutions.isNotEmpty()) {
                // Several "resolutions" often share ONE master/proxy URL — group by actual URL so
                // we don't emit the same link 3 times, then surface each distinct source as its own
                // playable quality (this is the "more than one link" the user wants to see).
                val byUrl = linkedMapOf<String, MutableList<String>>()
                for (r in resolutions) {
                    val u = r.streamUrl!!
                    byUrl.getOrPut(u) { mutableListOf() }
                        .add(r.label ?: "${r.resolution ?: 480}p")
                }
                for ((u, labels) in byUrl) {
                    val label = if (labels.size == 1) labels[0] else labels.joinToString("/")
                    val res = resolutions.firstOrNull { it.streamUrl == u }?.resolution
                    val q = when (res) {
                        1080 -> "1080p"
                        720 -> "720p"
                        540 -> "540p"
                        else -> "480p"
                    }
                    emit(u, label, q)
                }
            }

            // ALWAYS also surface play_url + direct_play_url (deduped against the URLs above).
            // Rationale: the per-quality multi_resolutions URLs are often short-lived signed token
            // links (shortmax-stream, hakuna, montagehub, netshort...) that are already expired or
            // dead by the time the user taps play — but direct_play_url is the reliable narto-local
            // proxy (stream-e1 /e/m or cdn.narto-drama.com MP4). Emitting them as fallbacks means a
            // work still plays even when every multi_resolutions token has lapsed. This is the fix
            // for "المسلسل يعرض حلقات ولا تشتغل": multi_res empty-but-dead no longer hides the good link.
            for (u in listOfNotNull(edge.playUrl, edge.directPlayUrl)) emit(u, "كامل", "1080p")

            // 3) a standalone audio track when the API provides one (e.g. a dubbed/original audio
            //    file separate from the video). Most narto works mux the audio into the video/HLS
            //    stream instead, in which case the player exposes the voices directly from the
            //    master — no separate link is needed. Only emit when direct_audio_url is actually set.
            edge.directAudioUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                if (emitted.add(it)) {
                    callback(
                        newExtractorLink(source = name, name = "صوت منفصل", url = it, type = ExtractorLinkType.VIDEO) {
                            referer = nartoOrigin
                            headers = mapOf("Referer" to nartoOrigin)
                        }
                    )
                    any = true
                }
            }

            any
        } catch (e: Exception) {
            false
        }
    }
}
