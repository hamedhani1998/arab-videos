package com.deepdrama.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DeepDramaPlugin : Plugin() {
    override fun load() {
        registerMainAPI(DeepDramaProvider())
    }
}
