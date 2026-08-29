package com.drama4all.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Drama4AllPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Drama4AllProvider())
    }
}