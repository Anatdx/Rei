package com.anatdx.rei.core.root

sealed class RootAccessState {
    data object Requesting : RootAccessState()
    data class Granted(val stdout: String) : RootAccessState()
    data class Denied(val reason: String) : RootAccessState()
    data object Ignored : RootAccessState()
}

