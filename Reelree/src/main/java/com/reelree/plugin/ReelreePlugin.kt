package com.reelree.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelreePlugin : Plugin() {
    override fun load() {
        registerMainAPI(ReelreeProvider())
    }
}
