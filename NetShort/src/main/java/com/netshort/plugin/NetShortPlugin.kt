package com.netshort.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NetShortPlugin : Plugin() {
    override fun load() {
        registerMainAPI(NetShortProvider())
    }
}
