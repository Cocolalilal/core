package com.maxrave.kotlinytmusicscraper.models.body

import com.maxrave.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class UnsubscribeBody(
    val context: Context,
    val channelIds: List<String>,
)
