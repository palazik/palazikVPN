package com.palazik.vpn.data.model

import java.util.UUID

data class Subscription(
    val id: String   = UUID.randomUUID().toString(),
    val name: String = "Unnamed Subscription",
    val url: String  = "",
    val lastUpdated: Long = 0L,
    val profileCount: Int = 0,
)
