package com.maxrave.kotlinytmusicscraper.models.body

import com.maxrave.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class SubscribeBody(
    val context: Context,
    val channelIds: List<String>,
)
