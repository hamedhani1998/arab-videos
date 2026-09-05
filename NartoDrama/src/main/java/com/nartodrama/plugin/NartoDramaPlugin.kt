package com.nartodrama.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NartoDramaPlugin : Plugin() {
    override fun load() {
        // Two Narto sources requested by the user: the open edge mirror (browsing) and the main
        // domain (fast playback refresh). Both are MainAPI providers in the same dex.
        registerMainAPI(NartoEdgeProvider())
        registerMainAPI(NartoMainProvider())
    }
}