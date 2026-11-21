package com.pisces312.piscesplayer.presentation.navigation

internal fun isMainRootRoute(route: String?): Boolean = when (route) {
    Screen.Home.route,
    Screen.Search.route,
    Screen.Library.route,
    Screen.MusicGameHub.route,
    Screen.MusicLearning.route -> true
    else -> false
}

internal fun mainRootRouteIndex(route: String?): Int? = when (route) {
    Screen.Home.route -> 0
    Screen.Search.route -> 1
    Screen.Library.route -> 2
    Screen.MusicGameHub.route -> 3
    Screen.MusicLearning.route -> 4
    else -> null
}
