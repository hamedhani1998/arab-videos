package com.minutedrama.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MinuteDramaPlugin : Plugin() {
    override fun load() {
        registerMainAPI(MinuteDramaProvider())
    }
}
