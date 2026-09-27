package com.maxrave.domain.data.player

/**
 * Generic Cast (Google Cast) state wrapper (no Cast SDK dependencies)
 */
data class GenericCastState(
    val isRemote: Boolean = false,
    val deviceName: String? = null,
    val deviceType: RemoteDeviceType = RemoteDeviceType.CAST,
) {
    companion object {
        val NOT_CASTING = GenericCastState()
    }
}
