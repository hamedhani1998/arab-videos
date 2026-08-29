package com.nartodrama.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NartoDramaPlugin : Plugin() {
    override fun load() {
        registerMainAPI(NartoDramaProvider())
    }
}