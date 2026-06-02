package com.palazik.vpn.data.model

enum class DesignSystem { MIUIX, MD3 }

data class AppSettings(
    val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "223.5.5.5",
    val bypassPackages: List<String> = emptyList(),
    val startOnBoot: Boolean = false,
    val autoUpdateSubscriptions: Boolean = true,
    val subscriptionUpdateIntervalHours: Long = 2L,
    val designSystem: DesignSystem = DesignSystem.MIUIX,

    // ── Subscriptions ──────────────────────────────────────────────────────────
    val subscriptionUserAgent: String = "v2rayNG/1.0",

    // ── Routing ────────────────────────────────────────────────────────────────
    val blockAds: Boolean = true,
    val bypassChina: Boolean = false,           // route geosite:cn / geoip:cn direct
    val customDirectDomains: List<String> = emptyList(),
    val customBlockedDomains: List<String> = emptyList(),

    // ── Privacy / leak protection ──────────────────────────────────────────────
    val enableIpv6: Boolean = false,            // add IPv6 address + ::/0 route to TUN
    val lockdownMode: Boolean = false,          // block traffic while VPN handler not ready (kill switch)
)
