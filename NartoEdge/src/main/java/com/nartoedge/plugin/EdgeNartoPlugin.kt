package com.nartoedge.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EdgeNartoPlugin : Plugin() {
    override fun load() {
        // Standalone "Edge Narto Drama" extension — pinned to https://edge.narto-drama.com only.
        // Fully separate from the "Narto Drama" extension (own module, own cache, own refresh
        // channel, own cooldown handling) so the two never interfere or merge.
        registerMainAPI(EdgeNartoProvider())
    }
}