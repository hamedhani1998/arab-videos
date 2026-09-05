package com.nartodrama.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NartoDramaPlugin : Plugin() {
    override fun load() {
        // Standalone "Narto Drama" extension — pinned to https://narto-drama.com only.
        // Fully separate from the "Edge Narto Drama" extension (own module, own cache, own
        // refresh channel, own cooldown handling) so the two never interfere or merge.
        registerMainAPI(NartoDramaProvider())
    }
}