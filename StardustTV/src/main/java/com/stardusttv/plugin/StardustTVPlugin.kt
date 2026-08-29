package com.stardusttv.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StardustTVPlugin : Plugin() {
    override fun load() {
        registerMainAPI(StardustTVProvider())
    }
}
