package com.onshort.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OnShortPlugin : Plugin() {
    override fun load() {
        registerMainAPI(OnShortArabicProvider())
        registerMainAPI(OnShortEnglishProvider())
    }
}
