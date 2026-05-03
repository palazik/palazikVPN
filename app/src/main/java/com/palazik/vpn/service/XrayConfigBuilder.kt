package com.palazik.vpn.service

import com.palazik.vpn.data.model.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a VpnProfile into a full xray-core JSON configuration string.
 *
 * Inbounds:  SOCKS5 on 127.0.0.1:10808 + HTTP on 127.0.0.1:10809
 *            libxray's internal tun2socks routes TUN traffic here
 * Outbound:  the actual proxy (VLESS/VMess/SS/Trojan/Hysteria2)
 * DNS:       remote DoH for proxied traffic, direct for local
 * Routing:   private IPs → direct, everything else → proxy
 */
object XrayConfigBuilder {

    fun build(profile: VpnProfile): String =
        JSONObject().apply {
            put("log",       buildLog())
            put("dns",       buildDns())
            put("inbounds",  buildInbounds())
            put("outbounds", buildOutbounds(profile))
            put("routing",   buildRouting())
        }.toString(2)

    // ── Log ───────────────────────────────────────────────────────────────────

    private fun buildLog() = JSONObject().apply {
        put("loglevel", "warning")
        put("error", "")
        put("access", "none")
    }

    // ── Inbounds ──────────────────────────────────────────────────────────────

    private fun buildInbounds() = JSONArray().apply {
        put(JSONObject().apply {
            put("tag", "socks")
            put("port", 10808)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("ip", "127.0.0.1")
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply {
                    put("http"); put("tls"); put("quic")
                })
                put("routeOnly", false)
            })
        })
        put(JSONObject().apply {
            put("tag", "http")
            put("port", 10809)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("settings", JSONObject())
        })
    }

    // ── Outbounds ─────────────────────────────────────────────────────────────

    private fun buildOutbounds(profile: VpnProfile) = JSONArray().apply {
        put(buildProxyOutbound(profile))
        put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply { put("domainStrategy", "UseIPv4") })
        })
        put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject())
        })
    }

    private fun buildProxyOutbound(profile: VpnProfile) = JSONObject().apply {
        put("tag", "proxy")
        when (profile.protocol) {
            Protocol.VLESS, Protocol.XHTTP -> buildVless(this, profile)
            Protocol.VMESS                 -> buildVmess(this, profile)
            Protocol.SHADOWSOCKS           -> buildShadowsocks(this, profile)
            Protocol.TROJAN               -> buildTrojan(this, profile)
            Protocol.HYSTERIA2            -> buildHysteria2(this, profile)
            Protocol.SOCKS5               -> buildSocksOut(this, profile)
            else                          -> buildVless(this, profile)
        }
        put("streamSettings", buildStreamSettings(profile))
        // Mux: disable for XHTTP (has its own multiplexing) and Hysteria2/WireGuard (QUIC-based)
        val useMux = profile.transport !in listOf(Transport.XHTTP, Transport.QUIC) &&
                     profile.protocol !in listOf(Protocol.HYSTERIA2, Protocol.WIREGUARD)
        put("mux", JSONObject().apply {
            put("enabled", useMux)
            if (useMux) put("concurrency", 8)
        })
    }

    // ── Protocol settings ─────────────────────────────────────────────────────

    private fun buildVless(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "vless")
        obj.put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", p.address)
                    put("port", p.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", p.uuid)
                            put("encryption", "none")
                            // XTLS vision flow: required for REALITY and XTLS
                            val flow = when (p.security) {
                                Security.REALITY, Security.XTLS -> "xtls-rprx-vision"
                                else -> ""
                            }
                            if (flow.isNotEmpty()) put("flow", flow)
                        })
                    })
                })
            })
        })
    }

    private fun buildVmess(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "vmess")
        obj.put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", p.address)
                    put("port", p.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", p.uuid)
                            put("alterId", 0)
                            put("security", "auto")
                        })
                    })
                })
            })
        })
    }

    private fun buildShadowsocks(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "shadowsocks")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address",  p.address)
                    put("port",     p.port)
                    put("method",   p.ssMethod)
                    put("password", p.ssPassword)
                })
            })
        })
    }

    private fun buildTrojan(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "trojan")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address",  p.address)
                    put("port",     p.port)
                    put("password", p.uuid)
                })
            })
        })
    }

    private fun buildHysteria2(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "hysteria2")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address",  p.address)
                    put("port",     p.port)
                    put("password", p.hystPassword)
                    if (p.hystObfs.isNotEmpty()) {
                        put("obfs", JSONObject().apply {
                            put("type",     p.hystObfs)
                            put("password", p.hystObfsPassword)
                        })
                    }
                })
            })
        })
    }

    private fun buildSocksOut(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "socks")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", p.address)
                    put("port",    p.port)
                })
            })
        })
    }

    // ── Stream settings (transport + TLS/REALITY) ─────────────────────────────

    private fun buildStreamSettings(p: VpnProfile) = JSONObject().apply {
        put("network", transportId(p.transport))

        when (p.transport) {
            Transport.XHTTP -> put("xhttpSettings", JSONObject().apply {
                put("path", p.path.ifEmpty { "/" })
                put("host", p.host.ifEmpty { p.sni.ifEmpty { p.address } })
                // "stream-one" = full-duplex chunked streaming, compatible with xray-core xhttp inbound
                put("mode", "stream-one")
            })
            Transport.WS -> put("wsSettings", JSONObject().apply {
                put("path", p.path.ifEmpty { "/" })
                if (p.host.isNotEmpty()) {
                    put("headers", JSONObject().apply { put("Host", p.host) })
                }
            })
            Transport.GRPC -> put("grpcSettings", JSONObject().apply {
                put("serviceName", p.path.ifEmpty { "" })
                put("multiMode", false)
            })
            Transport.H2 -> put("httpSettings", JSONObject().apply {
                put("path", p.path.ifEmpty { "/" })
                if (p.host.isNotEmpty()) {
                    put("host", JSONArray().apply { put(p.host) })
                }
            })
            Transport.TCP, Transport.QUIC -> { /* no extra settings */ }
        }

        when (p.security) {
            Security.TLS -> {
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("serverName",    p.sni.ifEmpty { p.address })
                    put("allowInsecure", false)
                    if (p.fingerprint.isNotEmpty()) put("fingerprint", p.fingerprint)
                    put("alpn", JSONArray().apply {
                        // h2 required for gRPC/H2 transport
                        if (p.transport == Transport.GRPC || p.transport == Transport.H2) put("h2")
                        put("http/1.1")
                    })
                })
            }
            Security.REALITY -> {
                put("security", "reality")
                put("realitySettings", JSONObject().apply {
                    put("serverName",  p.sni.ifEmpty { p.address })
                    put("fingerprint", p.fingerprint.ifEmpty { "chrome" })
                    put("shortId",     p.shortId)
                    put("publicKey",   p.publicKey)
                    put("spiderX",     "")
                })
            }
            Security.XTLS -> {
                // XTLS uses TLS settings in newer xray-core; flow is set in user settings above
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("serverName",    p.sni.ifEmpty { p.address })
                    put("allowInsecure", false)
                    if (p.fingerprint.isNotEmpty()) put("fingerprint", p.fingerprint)
                })
            }
            Security.NONE -> put("security", "none")
        }
    }

    private fun transportId(t: Transport) = when (t) {
        Transport.TCP   -> "tcp"
        Transport.WS    -> "ws"
        Transport.GRPC  -> "grpc"
        Transport.XHTTP -> "xhttp"
        Transport.H2    -> "http"
        Transport.QUIC  -> "quic"
    }

    // ── DNS ───────────────────────────────────────────────────────────────────

    private fun buildDns() = JSONObject().apply {
        put("hosts", JSONObject().apply {
            put("domain:googleapis.cn", "googleapis.com")
            put("domain:gstatic.com",   "googleapis.com")
        })
        put("servers", JSONArray().apply {
            // Remote DoH for non-CN domains → goes through proxy
            put(JSONObject().apply {
                put("address", "https://1.1.1.1/dns-query")
                put("domains", JSONArray().apply { put("geosite:geolocation-!cn") })
                put("expectIPs", JSONArray().apply { put("geoip:!cn") })
            })
            // Direct DNS for CN domains
            put("223.5.5.5")
            put("localhost")
        })
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private fun buildRouting() = JSONObject().apply {
        put("domainStrategy", "IPIfNonMatch")
        put("domainMatcher",  "hybrid")
        put("rules", JSONArray().apply {
            // Block ad domains
            put(JSONObject().apply {
                put("type",        "field")
                put("outboundTag", "block")
                put("domain",      JSONArray().apply { put("geosite:category-ads-all") })
            })
            // Private / LAN IPs → direct
            put(JSONObject().apply {
                put("type",        "field")
                put("outboundTag", "direct")
                put("ip",          JSONArray().apply {
                    put("geoip:private")
                    put("127.0.0.0/8")
                    put("10.0.0.0/8")
                    put("172.16.0.0/12")
                    put("192.168.0.0/16")
                })
            })
            // Everything else → proxy
            put(JSONObject().apply {
                put("type",        "field")
                put("outboundTag", "proxy")
                put("network",     "tcp,udp")
            })
        })
    }
}
