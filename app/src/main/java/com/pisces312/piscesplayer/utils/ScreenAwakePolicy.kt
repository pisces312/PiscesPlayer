package com.pisces312.piscesplayer.utils

internal fun shouldKeepScreenAwake(
    preferenceEnabled: Boolean,
    isPlaying: Boolean,
): Boolean = preferenceEnabled && isPlaying
