package com.shorttv.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ShortTVPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ShortTVProvider())
    }
}
