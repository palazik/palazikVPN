package com.palazik.vpn.data.model

import java.util.UUID

enum class Protocol {
    VMESS, VLESS, SHADOWSOCKS, TROJAN,
    HYSTERIA2, WIREGUARD, SOCKS5, HTTP,
    TUIC
    // XHTTP removed — it is a Transport, not a Protocol (use VLESS + Transport.XHTTP)
    // REALITY removed — it is a Security layer, not a Protocol (use Security.REALITY)
}

enum class Transport { TCP, WS, GRPC, XHTTP, H2, QUIC }
enum class Security  { NONE, TLS, REALITY, XTLS }

data class VpnProfile(
    val id: String        = UUID.randomUUID().toString(),
    val name: String      = "Unnamed",
    val protocol: Protocol= Protocol.VMESS,

    // ── common ───────────────────────────────────────────────────────────────
    val address: String   = "",
    val port: Int         = 443,
    val uuid: String      = "",       // vmess / vless / trojan password

    // ── transport ────────────────────────────────────────────────────────────
    val transport: Transport = Transport.TCP,
    val path: String      = "/",
    val host: String      = "",

    // ── security ─────────────────────────────────────────────────────────────
    val security: Security   = Security.TLS,
    val sni: String       = "",
    val fingerprint: String = "chrome",
    val publicKey: String = "",       // reality / wireguard
    val shortId: String   = "",       // reality

    // ── Shadowsocks ──────────────────────────────────────────────────────────
    val ssMethod: String  = "chacha20-ietf-poly1305",
    val ssPassword: String= "",

    // ── WireGuard ────────────────────────────────────────────────────────────
    val wgPrivateKey: String = "",
    val wgPeerPublicKey: String = "",
    val wgPreSharedKey: String = "",
    val wgEndpoint: String = "",
    val wgDns: String = "1.1.1.1",
    val wgMtu: Int = 1280,

    // ── Hysteria2 ────────────────────────────────────────────────────────────
    val hystPassword: String = "",
    val hystObfs: String = "",
    val hystObfsPassword: String = "",

    // ── metadata ─────────────────────────────────────────────────────────────
    val subscriptionId: String? = null,
    val latencyMs: Long   = -1L,
    val isActive: Boolean = false,
    val addedAt: Long     = System.currentTimeMillis(),
)
