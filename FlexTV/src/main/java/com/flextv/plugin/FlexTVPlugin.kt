package com.flextv.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FlexTVPlugin : Plugin() {
    override fun load() {
        registerMainAPI(FlexTVProvider())
    }
}
