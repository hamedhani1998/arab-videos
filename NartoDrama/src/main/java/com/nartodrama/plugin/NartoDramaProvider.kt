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
    @JsonProperty("selected_subtitle_language") val selectedSubtitleLanguage: String? = null,
    @JsonProperty("direct_audio_url") val directAudioUrl: String? = null,      // single standalone audio track
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

// ---- SINGLE domain (NO mirror merging) ----
// edge.narto-drama.com is the ONE open backend (no Cloudflare) that serves the native Arabic
// catalog. Previous versions auto-failed-over between edge and the Cloudflare-protected
// narto-drama.com via tryMirrors — that MERGING of two domains is what broke the interface
// display on device. Now EVERY request uses ONLY this single mainUrl. To point the source at
// another link, clone it in CloudStream and edit the URL (the native "choose this link or that"
// mechanism) — no code-side merging.
class NartoDramaProvider : MainAPI() {
    override var name = "Narto Drama"
    override var mainUrl = "https://edge.narto-drama.com"  // single open backend (no Cloudflare)
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Main screen = one section per tab, each a distinct Arabic query (verified live: different
// queries return DIFFERENT 24-item feeds, 0 overlap — so these are real categories, not the
// same list repackaged). The empty query (q='') is the site's "recommendations" feed but it
// intermittently returns HTTP 502 from edge, so it is NOT used. Each tab lazily fetches ONE
// query only when opened (keeps browsing light — one ~400KB fetch per section).
    override val mainPage = mainPageOf(
        "دراما" to "🎬 دراما",
        "مدبلج" to "🎙️ مدبلج",
        "رومانسي" to "💕 رومانسي",
        "أكشن" to "⚔️ أكشن",
        "كوميدي" to "😄 كوميدي",
        "جريمة" to "🕵️ جريمة",
        "تركي" to "🇹🇷 تركي",
        "كوري" to "🇰🇷 كوري",
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
    // NOTE: purely URL-based — NO network HEAD probe. Probing each link (some hosts are dead/slow)
    // was the "التشغيل بطيء" delay; classifying from the URL is instant.
    private fun inferStreamType(url: String): ExtractorLinkType {
        val lower = url.lowercase()
        // "contains" (NOT endsWith) so a signed direct-MP4 like `file.mp4?token=abc` — which ends in
        // the query string, not `.mp4` — still classifies as VIDEO and plays (was misread as M3U8).
        if (lower.contains("mime_type=video_mp4") || lower.contains(".mp4") || lower.endsWith(".m4v"))
            return ExtractorLinkType.VIDEO
        // M3U8 is the dominant container across narto's backends (shortmax/-stream, proxy /e/m/,
        // akamai) — ambiguous token URLs default to HLS rather than block on a network probe.
        return ExtractorLinkType.M3U8
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
        // Browse hits the OPEN mirror (edge) DIRECTLY — NOT through tryMirrors. The CF-protected
        // narto-drama.com fallback in tryMirrors made the whole page come back EMPTY on device when
        // edge was slow/blank (the fallback then also got challenged -> null). This exact direct form
        // is what showed the main page reliably in v6/v7. request.data is the tab's query string.
        val t0 = System.currentTimeMillis()
        return try {
            val q = request.data.trim()
            val html = app.get("$mainUrl/search?lang=ar-SA&q=$q", referer = nartoOrigin, headers = mapOf("User-Agent" to UA)).text
            val t1 = System.currentTimeMillis()
            android.util.Log.e("NartoDrama", "getMainPage q=$q fetchMs=${t1 - t0} len=${html.length} item=" + html.contains("\"@type\":\"ListItem\""))
            val items = parseSearchItems(html)
            if (items.isEmpty()) return null
            val list = items.mapNotNull { it.toSearchResponse() }
            if (list.isEmpty()) null else newHomePageResponse(request.name, list)
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "getMainPage ERROR", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Direct to the OPEN mirror (edge), like getMainPage — not via the CF-prone tryMirrors
        // fallback that could empty results on device. URL-encode the user's free-text query.
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val html = app.get("$mainUrl/search?lang=ar-SA&q=$q", referer = nartoOrigin, headers = mapOf("User-Agent" to UA)).text
            val items = parseSearchItems(html)
            items.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "search ERROR q=$query", e)
            null
        }
    }

    private fun SearchHit.toSearchResponse(): SearchResponse? {
        val u = url ?: return null
        val slug = Regex("""/detail/watch/([^/?]+)""").find(u)?.groupValues?.get(1) ?: return null
        val name = this.name ?: return null
        // Keep the "[مدبلج] ..." prefix so dubbed entries are obviously marked.
        val poster = image?.let { if (it.startsWith("http")) it else mainUrl + it }
        return newTvSeriesSearchResponse(name, "$mainUrl/detail/watch/$slug", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val slug = Regex("""/detail/watch/([^/?]+)""").find(url)?.groupValues?.get(1) ?: return null
            // Use app.get(...).document (the SDK's own Jsoup-backed accessor) — importing Jsoup
            // directly to re-parse a text body is NOT reliably on the plugin classpath and made the
            // detail page fail after v8. Detail is a low-CF path; keep it on the direct mainUrl
            // form like v7. (Single-domain now — no failover anywhere.)
            val doc = app.get("$mainUrl/detail/watch/$slug", referer = nartoOrigin, headers = mapOf("User-Agent" to UA)).document
            android.util.Log.e("NartoDrama", "load slug=$slug fetchMs=${System.currentTimeMillis() - t0} eps=" + doc.select("div.episode-list a.episode-item").size)

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")

            val eps = doc.select("div.episode-list a.episode-item")
                .mapNotNull { el ->
                    val href = el.attr("href") ?: return@mapNotNull null
                    val ep = Regex("""/detail/watch/[^/]+/(\d+)""").find(href)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: return@mapNotNull null
                    newEpisode("$mainUrl/detail/watch/$slug/$ep") {
                        episode = ep
                        name = "الحلقة $ep"
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
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
            val body = app.get("$mainUrl/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx", referer = nartoOrigin).text
            mapper.readValue(body, EdgeResponse::class.java)
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "edgeRefreshSource ERROR slug=$slug ep=$ep", e)
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

            var edge = edgeRefreshSource(slug, ep)
            if (edge == null) {
                android.util.Log.e("NartoDrama", "loadLinks NO EDGE (all mirrors failed or JSON parse error) slug=$slug ep=$ep")
                return false
            }

            // Explicit transient outage — surface a readable failure instead of a useless empty list.
            // (Driven by the API's own message string, not by guessing.)
            if (edge.ok != true && edge.message == "stream_temporarily_unavailable") return false

            // Canonical re-discovery: a requested slug may differ from the narto canonical one.
            if (edge.ok != true && edge.message == "slug_mismatch") {
                val canon = edge.canonical?.let { Regex("""/detail/watch/([^/?]+)/""").find(it)?.groupValues?.get(1) }
                if (canon != null && canon != slug) {
                    android.util.Log.e("NartoDrama", "loadLinks slug_mismatch $slug -> $canon ep=$ep")
                    slug = canon
                    edge = edgeRefreshSource(slug, ep)
                    if (edge == null) return false
                }
            }
            // Do NOT hard-bail on `ok != true` here — it is often ABSENT or typed as an int in the
            // JSON, and Jackson leaves the Kotlin `Boolean?` null in that case (`null != true` would
            // have dropped EVERY work -> "لم يتم العثور على روابط"). Only the two explicit messages
            // above abort; otherwise we parse whatever play_url/direct_play_url/multi_resolutions the
            // API returned. Log the actual edge state so real failures are visible in logcat.
            if (edge.ok != true) {
                android.util.Log.e("NartoDrama", "loadLinks edge.ok!=true (continuing anyway) slug=$slug ep=$ep msg=${edge.message} play=${edge.directPlayUrl?.take(60)} res=${edge.multiResolutions?.size}")
            }

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
            // Each survives as its OWN distinctly-named link so the list never shows two confusing
// same-named entries (user: "تفصل كل رابط باضافه منفصله"). play -> كامل, direct -> رابط مباشر.
            var first = true
            for (u in listOfNotNull(edge.playUrl, edge.directPlayUrl)) {
                emit(u, if (first) "كامل" else "رابط مباشر", "1080p")
                first = false
            }

            // 3) AUDIO — surface EVERY audio track the work carries (user: "اقسام الصوت اظهرها بالكامل"):
            //    a) direct_audio_url — the API's standalone audio link (restored; it returned a
            //       "صوت منفصل" castable track before the v7 "subtitles only" cleanup).
            //    b) #EXT-X-MEDIA:TYPE=AUDIO groups in the HLS master(s) — fetched once from the first
            //       HLS master we already have, each group's URI emitted as its own "صوت: NAME (LANG)" link.
            // All audio work is best-effort and wrapped so it can NEVER drop the video links above.
            val audioSeen = LinkedHashSet<String>()
            edge.directAudioUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let { aud ->
                val type = if (aud.lowercase().contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                if (audioSeen.add(aud)) {
                    callback(newExtractorLink(source = name, name = "صوت منفصل", url = aud, type = type) {
                        referer = nartoOrigin
                        headers = mapOf("Referer" to nartoOrigin)
                    })
                    any = true
                }
            }
            // Parse the AUDIO groups out of the first HLS master we emitted (a tiny extra fetch; the
            // player would fetch it anyway, so this only *lists* what's already selected when playing).
            val master = listOfNotNull(edge.directPlayUrl, edge.playUrl)
                .firstOrNull { it.lowercase().contains("m3u8") || it.lowercase().contains("/e/m/") }
            if (master != null) {
                try {
                    val masterBody = app.get(master, referer = nartoOrigin).text
                    val audioUriTxt = Regex("""#EXT-X-MEDIA:TYPE=AUDIO[^\n]*""").findAll(masterBody).toList()
                    val seenGroups = LinkedHashSet<String>()
                    for (line in audioUriTxt) {
                        val name = Regex("""NAME="([^"]*)"""").find(line.value)?.groupValues?.get(1) ?: "صوت"
                        val lang = Regex("""LANGUAGE="([^"]*)"""").find(line.value)?.groupValues?.get(1)
                        val uri = Regex("""URI="([^"]*)"""").find(line.value)?.groupValues?.get(1) ?: continue
                        val resolved = if (uri.startsWith("http")) uri
                        else java.net.URI(master).resolve(uri).toString()
                        if (!seenGroups.add(resolved)) continue
                        val label = lang?.let { "صوت: $name ($it)" } ?: "صوت: $name"
                        callback(newExtractorLink(
                            source = name, name = label, url = resolved, type = ExtractorLinkType.M3U8
                        ) { referer = nartoOrigin; headers = mapOf("Referer" to nartoOrigin) })
                        any = true
                    }
                    android.util.Log.e("NartoDrama", "loadLinks AUDIO master groups=${seenGroups.size} slug=$slug ep=$ep")
                } catch (e: Exception) {
                    android.util.Log.e("NartoDrama", "loadLinks AUDIO parse ERROR slug=$slug ep=$ep", e)
                }
            } else {
                android.util.Log.e("NartoDrama", "loadLinks AUDIO no hls master to read groups slug=$slug ep=$ep play=${edge.directPlayUrl?.take(50)}")
            }

            android.util.Log.e("NartoDrama", "loadLinks DONE slug=$slug ep=$ep links=${emitted.size} subs=${subTracks.size} any=$any")
            any
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "loadLinks FATAL", e)
            false
        }
    }
}
