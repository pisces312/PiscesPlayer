package com.pisces312.piscesplayer.ui.glancewidget

import androidx.glance.appwidget.GlanceAppWidget

class PiscesPlayerGlanceWidgetReceiver : GuardedGlanceWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PiscesPlayerGlanceWidget()
}