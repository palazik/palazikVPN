package com.palazik.vpn.service

import com.palazik.vpn.data.model.*
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    fun build(profile: VpnProfile): String =
        JSONObject().apply {
            put("log",       buildLog())
            put("dns",       buildDns())
            put("inbounds",  buildInbounds())
            put("outbounds", buildOutbounds(profile))
            put("routing",   buildRouting())
            put("stats",     JSONObject())
            put("policy",    buildPolicy())
        }.toString(2)

    // ── Log ───────────────────────────────────────────────────────────────────

    private fun buildLog() = JSONObject().apply {
        put("loglevel", "warning")
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
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply {
                    put("http"); put("tls"); put("quic")
                })
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
            Protocol.VLESS       -> buildVless(this, profile)
            Protocol.VMESS       -> buildVmess(this, profile)
            Protocol.SHADOWSOCKS -> buildShadowsocks(this, profile)
            Protocol.TROJAN      -> buildTrojan(this, profile)
            Protocol.HYSTERIA2   -> buildHysteria2(this, profile)
            Protocol.WIREGUARD   -> buildWireguard(this, profile)
            Protocol.SOCKS5      -> buildSocksOut(this, profile)
            Protocol.TUIC        -> buildTuic(this, profile)
            else                 -> buildVless(this, profile)
        }

        // streamSettings only applies to TCP-based proxy protocols.
        // Hysteria2 and WireGuard handle their own transport — adding streamSettings
        // to them will cause Xray to fail to parse the config.
        val needsStream = profile.protocol !in listOf(
            Protocol.HYSTERIA2, Protocol.WIREGUARD
        )
        if (needsStream) {
            put("streamSettings", buildStreamSettings(profile))
        }

        // Mux: disable for transports/protocols that don't support it or break with it.
        // XHTTP, QUIC, Hysteria2, WireGuard, TUIC all must have mux disabled.
        val useMux = profile.protocol !in listOf(
            Protocol.HYSTERIA2, Protocol.WIREGUARD, Protocol.TUIC, Protocol.SOCKS5
        ) && profile.transport !in listOf(Transport.XHTTP, Transport.QUIC)

        put("mux", JSONObject().apply {
            put("enabled", useMux)
            if (useMux) put("concurrency", 8)
        })
    }

    // ── Protocol builders ─────────────────────────────────────────────────────

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
                            // flow is required for XTLS-Vision (Reality/XTLS security)
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

    private fun buildWireguard(obj: JSONObject, p: VpnProfile) {
        // BUG FIX: WireGuard was missing — fell through to buildVless which
        // produced a completely wrong config for WireGuard profiles.
        obj.put("protocol", "wireguard")
        obj.put("settings", JSONObject().apply {
            put("secretKey", p.wgPrivateKey)
            put("address", JSONArray().apply {
                put(p.address.ifEmpty { "10.0.0.2/32" })
            })
            put("peers", JSONArray().apply {
                put(JSONObject().apply {
                    put("publicKey",    p.wgPeerPublicKey)
                    put("allowedIPs",   JSONArray().apply { put("0.0.0.0/0"); put("::/0") })
                    // endpoint: use wgEndpoint if set, otherwise address:port
                    val endpoint = p.wgEndpoint.ifEmpty { "${p.address}:${p.port}" }
                    put("endpoint", endpoint)
                    if (p.wgPreSharedKey.isNotEmpty()) put("preSharedKey", p.wgPreSharedKey)
                })
            })
            put("mtu", p.wgMtu)
            if (p.wgDns.isNotEmpty()) {
                put("domainStrategy", "UseIPv4")
            }
        })
    }

    private fun buildSocksOut(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "socks")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", p.address)
                    put("port",    p.port)
                    // include auth if uuid is set (uuid field stores socks5 username:password)
                    if (p.uuid.isNotEmpty() && p.uuid.contains(":")) {
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("user", p.uuid.substringBefore(":"))
                                put("pass", p.uuid.substringAfter(":"))
                            })
                        })
                    }
                })
            })
        })
    }

    private fun buildTuic(obj: JSONObject, p: VpnProfile) {
        // BUG FIX: TUIC was missing — fell through to buildVless.
        // Note: Xray-core supports TUIC v5. ssPassword field holds the TUIC password.
        obj.put("protocol", "tuic")
        obj.put("settings", JSONObject().apply {
            put("server",   p.address)
            put("port",     p.port)
            put("uuid",     p.uuid)
            put("password", p.ssPassword)
            put("congestionControl", "bbr")
            put("udpRelayMode",      "native")
            put("zeroRttHandshake",  false)
        })
    }

    // ── Stream settings ───────────────────────────────────────────────────────

    private fun buildStreamSettings(p: VpnProfile) = JSONObject().apply {
        val network = when (p.transport) {
            Transport.TCP   -> "tcp"
            Transport.WS    -> "ws"
            Transport.GRPC  -> "grpc"
            Transport.XHTTP -> "xhttp"
            Transport.H2    -> "http"
            Transport.QUIC  -> "quic"
        }
        put("network", network)

        when (p.transport) {
            Transport.XHTTP -> put("xhttpSettings", JSONObject().apply {
                put("path", p.path.ifEmpty { "/" })
                put("host", p.host.ifEmpty { p.sni.ifEmpty { p.address } })
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
            Transport.TCP -> {
                // BUG FIX: for TCP with a host header (CDN configs), emit tcpSettings.
                // Previously TCP had no settings block at all, silently dropping host.
                if (p.host.isNotEmpty()) {
                    put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().apply {
                            put("type", "http")
                            put("request", JSONObject().apply {
                                put("version", "1.1")
                                put("method", "GET")
                                put("path", JSONArray().apply { put(p.path.ifEmpty { "/" }) })
                                put("headers", JSONObject().apply {
                                    put("Host", JSONArray().apply { put(p.host) })
                                    put("User-Agent", JSONArray().apply {
                                        put("Mozilla/5.0")
                                    })
                                })
                            })
                        })
                    })
                }
            }
            else -> {}
        }

        when (p.security) {
            Security.TLS -> {
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("serverName",    p.sni.ifEmpty { p.address })
                    put("allowInsecure", false)
                    if (p.fingerprint.isNotEmpty()) put("fingerprint", p.fingerprint)
                    put("alpn", JSONArray().apply {
                        if (p.transport in listOf(Transport.GRPC, Transport.H2)) put("h2")
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
                // XTLS is negotiated via flow="xtls-rprx-vision" in the user object.
                // The security layer itself is still "tls" in streamSettings.
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

    // ── DNS ───────────────────────────────────────────────────────────────────

    private fun buildDns() = JSONObject().apply {
        put("servers", JSONArray().apply {
            put(JSONObject().apply {
                put("address", "https://1.1.1.1/dns-query")
                put("domains", JSONArray().apply { put("geosite:geolocation-!cn") })
            })
            put("223.5.5.5")
            put("localhost")
        })
    }

    // ── Policy ────────────────────────────────────────────────────────────────

    private fun buildPolicy() = JSONObject().apply {
        put("levels", JSONObject().apply {
            put("0", JSONObject().apply {
                put("statsUserUplink",   true)
                put("statsUserDownlink", true)
            })
        })
        put("system", JSONObject().apply {
            put("statsOutboundUplink",   true)
            put("statsOutboundDownlink", true)
        })
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private fun buildRouting() = JSONObject().apply {
        put("domainStrategy", "IPIfNonMatch")
        put("domainMatcher",  "hybrid")
        put("rules", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "field"); put("outboundTag", "block")
                put("domain", JSONArray().apply { put("geosite:category-ads-all") })
            })
            put(JSONObject().apply {
                put("type", "field"); put("outboundTag", "direct")
                put("ip", JSONArray().apply {
                    put("geoip:private")
                    put("127.0.0.0/8")
                    put("10.0.0.0/8")
                    put("172.16.0.0/12")
                    put("192.168.0.0/16")
                })
            })
            put(JSONObject().apply {
                put("type", "field"); put("outboundTag", "proxy")
                put("network", "tcp,udp")
            })
        })
    }
}
