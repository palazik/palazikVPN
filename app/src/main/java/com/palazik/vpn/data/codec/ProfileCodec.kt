package com.palazik.vpn.data.codec

import android.net.Uri
import android.util.Base64
import com.palazik.vpn.data.model.*
import org.json.JSONObject
import java.util.UUID

/**
 * Encodes/decodes share-links for all supported protocols.
 *
 * Supported import schemes:
 *   vmess://  vless://  ss://  trojan://  hysteria2://  wireguard://  socks5://
 *   tuic://   xhttp://  palazikvpn://  (palazikVPN proprietary share)
 *
 * Export always produces a palazikvpn:// URI plus the native URI.
 *
 * NOTE: xhttp:// is treated as VLESS + Transport.XHTTP on import.
 *       XHTTP is a transport layer, not a standalone protocol.
 */
object ProfileCodec {

    // ─────────────────────────────────────────────────────────────────────────
    // IMPORT
    // ─────────────────────────────────────────────────────────────────────────

    fun decode(raw: String): VpnProfile? = runCatching {
        val trimmed = raw.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        when {
            scheme == "palazikvpn"             -> decodePalazik(trimmed)
            trimmed.startsWith("vmess://")      -> decodeVmess(trimmed)
            trimmed.startsWith("vless://")      -> decodeVless(trimmed)
            trimmed.startsWith("ss://")         -> decodeShadowsocks(trimmed)
            trimmed.startsWith("trojan://")     -> decodeTrojan(trimmed)
            trimmed.startsWith("hysteria2://")  -> decodeHysteria2(trimmed)
            trimmed.startsWith("wireguard://")  -> decodeWireguard(trimmed)
            trimmed.startsWith("socks5://")     -> decodeSocks5(trimmed)
            trimmed.startsWith("tuic://")       -> decodeTuic(trimmed)
            // xhttp:// share links are VLESS profiles with Transport.XHTTP
            trimmed.startsWith("xhttp://")      -> decodeXhttp(trimmed)
            else -> null
        }
    }.getOrNull()

    /** Decode a subscription body (newline-separated or base64-encoded links) */
    fun decodeSubscriptionBody(body: String): List<VpnProfile> {
        val lines = try {
            String(Base64.decode(body.trim(), Base64.DEFAULT)).lines()
        } catch (_: Exception) {
            body.lines()
        }
        return lines.mapNotNull { decode(it.trim()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXPORT — native + palazikvpn:// wrapper
    // ─────────────────────────────────────────────────────────────────────────

    fun encodeNative(p: VpnProfile): String = when (p.protocol) {
        Protocol.VMESS       -> encodeVmess(p)
        Protocol.VLESS       -> encodeVless(p)      // covers XHTTP transport too
        Protocol.SHADOWSOCKS -> encodeShadowsocks(p)
        Protocol.TROJAN      -> encodeTrojan(p)
        Protocol.HYSTERIA2   -> encodeHysteria2(p)
        Protocol.WIREGUARD   -> encodeWireguard(p)
        Protocol.SOCKS5      -> encodeSocks5(p)
        Protocol.TUIC        -> encodeTuic(p)
        else                 -> encodeVless(p)
    }

    /** Returns palazikvpn://<base64(json)>#name */
    fun encodePalazik(p: VpnProfile): String {
        val json = JSONObject().apply {
            put("v", 1)
            put("id", p.id)   // FIX: persist id so active-profile state survives restart
            put("proto", p.protocol.name)
            put("addr", p.address)
            put("port", p.port)
            put("uuid", p.uuid)
            put("transport", p.transport.name)
            put("path", p.path)
            put("host", p.host)
            put("security", p.security.name)
            put("sni", p.sni)
            put("fp", p.fingerprint)
            put("pubkey", p.publicKey)
            put("shortId", p.shortId)
            put("ssMethod", p.ssMethod)
            put("ssPwd", p.ssPassword)
            put("wgPriv", p.wgPrivateKey)
            put("wgPub", p.wgPeerPublicKey)
            put("wgPsk", p.wgPreSharedKey)
            put("wgEndp", p.wgEndpoint)
            put("hystPwd", p.hystPassword)
            put("name", p.name)
        }.toString()
        val b64 = Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "palazikvpn://$b64#${Uri.encode(p.name)}"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private decoders
    // ─────────────────────────────────────────────────────────────────────────

    private fun decodePalazik(raw: String): VpnProfile {
        val b64 = raw.substringAfter("://").substringBefore("#")
        val json = JSONObject(String(Base64.decode(b64, Base64.URL_SAFE)))
        // Guard: old profiles saved with "XHTTP" or "REALITY" as protocol — migrate them
        val protoStr = json.optString("proto", "VLESS")
        val protocol = runCatching { Protocol.valueOf(protoStr) }.getOrDefault(Protocol.VLESS)
        val transportStr = json.optString("transport", "TCP")
        val transport = runCatching { Transport.valueOf(transportStr) }.getOrDefault(Transport.TCP)
        val savedId = json.optString("id").takeIf { it.isNotEmpty() }
        return VpnProfile(
            id          = savedId ?: UUID.randomUUID().toString(),  // FIX: restore id
            protocol    = protocol,
            address     = json.optString("addr"),
            port        = json.optInt("port", 443),
            uuid        = json.optString("uuid"),
            transport   = transport,
            path        = json.optString("path", "/"),
            host        = json.optString("host"),
            security    = Security.valueOf(json.optString("security", "TLS")),
            sni         = json.optString("sni"),
            fingerprint = json.optString("fp", "chrome"),
            publicKey   = json.optString("pubkey"),
            shortId     = json.optString("shortId"),
            ssMethod    = json.optString("ssMethod", "chacha20-ietf-poly1305"),
            ssPassword  = json.optString("ssPwd"),
            wgPrivateKey    = json.optString("wgPriv"),
            wgPeerPublicKey = json.optString("wgPub"),
            wgPreSharedKey  = json.optString("wgPsk"),
            wgEndpoint      = json.optString("wgEndp"),
            hystPassword    = json.optString("hystPwd"),
            name        = json.optString("name", "Imported"),
        )
    }

    private fun decodeVmess(raw: String): VpnProfile {
        val b64  = raw.removePrefix("vmess://")
        val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
        val transport = when (json.optString("net")) {
            "ws"   -> Transport.WS
            "grpc" -> Transport.GRPC
            "h2"   -> Transport.H2
            "quic" -> Transport.QUIC
            "xhttp"-> Transport.XHTTP
            else   -> Transport.TCP
        }
        val security = when (json.optString("tls")) {
            "tls"    -> Security.TLS
            "xtls"   -> Security.XTLS
            "reality"-> Security.REALITY
            else     -> Security.NONE
        }
        return VpnProfile(
            name      = json.optString("ps", "VMess"),
            protocol  = Protocol.VMESS,
            address   = json.optString("add"),
            port      = json.optString("port").toIntOrNull() ?: 443,
            uuid      = json.optString("id"),
            transport = transport,
            // BUG FIX: always read path and host regardless of transport type
            path      = json.optString("path", "/"),
            host      = json.optString("host"),
            security  = security,
            sni       = json.optString("sni"),
        )
    }

    private fun decodeVless(raw: String): VpnProfile {
        val uri  = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        val transport = Transport.values().firstOrNull {
            it.name.equals(params["type"], true)
        } ?: Transport.TCP
        val security = when (params["security"]) {
            "tls"    -> Security.TLS
            "reality"-> Security.REALITY
            "xtls"   -> Security.XTLS
            else     -> Security.NONE
        }
        return VpnProfile(
            name        = Uri.decode(uri.fragment ?: "VLESS"),
            protocol    = Protocol.VLESS,
            address     = uri.host ?: "",
            port        = uri.port.takeIf { it > 0 } ?: 443,
            uuid        = uri.userInfo ?: "",
            transport   = transport,
            // BUG FIX: always read path and host regardless of transport type
            path        = params["path"] ?: "/",
            host        = params["host"] ?: "",
            security    = security,
            sni         = params["sni"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            publicKey   = params["pbk"] ?: "",
            shortId     = params["sid"] ?: "",
        )
    }

    private fun decodeShadowsocks(raw: String): VpnProfile {
        val fragment = raw.substringAfter("#", "Shadowsocks")
        val body     = raw.removePrefix("ss://").substringBefore("#")
        return try {
            val uri = Uri.parse("ss://$body")
            val userInfo = uri.userInfo ?: run {
                val decoded = String(Base64.decode(body.substringBefore("@"), Base64.DEFAULT))
                decoded
            }
            val methodPwd = if (userInfo.contains(":")) userInfo else
                String(Base64.decode(userInfo, Base64.DEFAULT))
            val method  = methodPwd.substringBefore(":")
            val pwd     = methodPwd.substringAfter(":")
            VpnProfile(
                name       = Uri.decode(fragment),
                protocol   = Protocol.SHADOWSOCKS,
                address    = uri.host ?: "",
                port       = uri.port.takeIf { it > 0 } ?: 8388,
                ssMethod   = method,
                ssPassword = pwd,
                // SS does not use TLS — must override the default Security.TLS
                // otherwise XrayConfigBuilder adds tlsSettings and xray fails to connect
                security   = Security.NONE,
                transport  = Transport.TCP,
            )
        } catch (_: Exception) {
            VpnProfile(name = Uri.decode(fragment), protocol = Protocol.SHADOWSOCKS)
        }
    }

    private fun decodeTrojan(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        return VpnProfile(
            name     = Uri.decode(uri.fragment ?: "Trojan"),
            protocol = Protocol.TROJAN,
            address  = uri.host ?: "",
            port     = uri.port.takeIf { it > 0 } ?: 443,
            uuid     = uri.userInfo ?: "",
            security = Security.TLS,
            sni      = params["sni"] ?: "",
        )
    }

    private fun decodeHysteria2(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        return VpnProfile(
            name         = Uri.decode(uri.fragment ?: "Hysteria2"),
            protocol     = Protocol.HYSTERIA2,
            address      = uri.host ?: "",
            port         = uri.port.takeIf { it > 0 } ?: 443,
            hystPassword = uri.userInfo ?: "",
            sni          = params["sni"] ?: "",
            hystObfs     = params["obfs"] ?: "",
            hystObfsPassword = params["obfs-password"] ?: "",
        )
    }

    private fun decodeWireguard(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        return VpnProfile(
            name            = Uri.decode(uri.fragment ?: "WireGuard"),
            protocol        = Protocol.WIREGUARD,
            address         = uri.host ?: "",
            port            = uri.port.takeIf { it > 0 } ?: 51820,
            wgPrivateKey    = params["privatekey"] ?: "",
            wgPeerPublicKey = params["publickey"] ?: "",
            wgPreSharedKey  = params["presharedkey"] ?: "",
            wgDns           = params["dns"] ?: "1.1.1.1",
            wgMtu           = params["mtu"]?.toIntOrNull() ?: 1280,
        )
    }

    private fun decodeSocks5(raw: String): VpnProfile {
        val uri = Uri.parse(raw)
        return VpnProfile(
            name     = Uri.decode(uri.fragment ?: "SOCKS5"),
            protocol = Protocol.SOCKS5,
            address  = uri.host ?: "",
            port     = uri.port.takeIf { it > 0 } ?: 1080,
            uuid     = uri.userInfo ?: "",
        )
    }

    private fun decodeTuic(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        val userInfo = uri.userInfo ?: ""
        val uuid     = userInfo.substringBefore(":")
        val password = userInfo.substringAfter(":", "")
        return VpnProfile(
            name       = Uri.decode(uri.fragment ?: "TUIC"),
            protocol   = Protocol.TUIC,
            address    = uri.host ?: "",
            port       = uri.port.takeIf { it > 0 } ?: 443,
            uuid       = uuid,
            ssPassword = password.ifEmpty { params["password"] ?: "" },
            security   = Security.TLS,
            sni        = params["sni"] ?: "",
        )
    }

    /**
     * xhttp:// share links are VLESS profiles using Transport.XHTTP.
     * XHTTP is NOT a protocol — it is a transport layer.
     * BUG FIX: was Protocol.XHTTP, now correctly Protocol.VLESS + Transport.XHTTP.
     */
    private fun decodeXhttp(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        return VpnProfile(
            name      = Uri.decode(uri.fragment ?: "XHTTP"),
            protocol  = Protocol.VLESS,          // FIX: was Protocol.XHTTP
            address   = uri.host ?: "",
            port      = uri.port.takeIf { it > 0 } ?: 443,
            uuid      = uri.userInfo ?: "",
            transport = Transport.XHTTP,
            // BUG FIX: always read path and host
            path      = params["path"] ?: "/",
            host      = params["host"] ?: "",
            security  = if (params["tls"] == "tls") Security.TLS else Security.NONE,
            sni       = params["sni"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private encoders
    // ─────────────────────────────────────────────────────────────────────────

    private fun encodeVmess(p: VpnProfile): String {
        val net = when (p.transport) {
            Transport.WS    -> "ws"
            Transport.GRPC  -> "grpc"
            Transport.H2    -> "h2"
            Transport.QUIC  -> "quic"
            Transport.XHTTP -> "xhttp"
            else            -> "tcp"
        }
        val tls = when (p.security) {
            Security.TLS    -> "tls"
            Security.XTLS   -> "xtls"
            Security.REALITY-> "reality"
            else            -> ""
        }
        val json = JSONObject().apply {
            put("v","2"); put("ps",p.name); put("add",p.address)
            put("port",p.port); put("id",p.uuid); put("aid",0)
            put("net",net); put("path",p.path); put("host",p.host)
            put("tls",tls); put("sni",p.sni)
        }.toString()
        return "vmess://${Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)}"
    }

    private fun encodeVless(p: VpnProfile): String {
        val b = Uri.Builder().scheme("vless")
            .encodedAuthority("${p.uuid}@${p.address}:${p.port}")
            .appendQueryParameter("type", p.transport.name.lowercase())
            .appendQueryParameter("security", p.security.name.lowercase())
            // FIX: always encode path and host, not just for certain transports
            .appendQueryParameter("path", p.path)
            .appendQueryParameter("host", p.host)
            .appendQueryParameter("sni", p.sni)
            .appendQueryParameter("fp", p.fingerprint)
        if (p.security == Security.REALITY) {
            b.appendQueryParameter("pbk", p.publicKey)
                .appendQueryParameter("sid", p.shortId)
        }
        b.fragment(p.name)
        return b.build().toString()
    }

    private fun encodeShadowsocks(p: VpnProfile): String {
        val userInfo = Base64.encodeToString(
            "${p.ssMethod}:${p.ssPassword}".toByteArray(), Base64.NO_WRAP)
        return "ss://$userInfo@${p.address}:${p.port}#${Uri.encode(p.name)}"
    }

    private fun encodeTrojan(p: VpnProfile) =
        Uri.Builder().scheme("trojan")
            .encodedAuthority("${p.uuid}@${p.address}:${p.port}")
            .appendQueryParameter("sni", p.sni)
            .fragment(p.name).build().toString()

    private fun encodeHysteria2(p: VpnProfile): String {
        val b = Uri.Builder().scheme("hysteria2")
            .encodedAuthority("${p.hystPassword}@${p.address}:${p.port}")
            .appendQueryParameter("sni", p.sni)
        if (p.hystObfs.isNotEmpty()) {
            b.appendQueryParameter("obfs", p.hystObfs)
                .appendQueryParameter("obfs-password", p.hystObfsPassword)
        }
        return b.fragment(p.name).build().toString()
    }

    private fun encodeWireguard(p: VpnProfile) =
        Uri.Builder().scheme("wireguard")
            .authority("${p.address}:${p.port}")
            .appendQueryParameter("privatekey", p.wgPrivateKey)
            .appendQueryParameter("publickey", p.wgPeerPublicKey)
            .appendQueryParameter("dns", p.wgDns)
            .appendQueryParameter("mtu", p.wgMtu.toString())
            .fragment(p.name).build().toString()

    private fun encodeSocks5(p: VpnProfile) =
        "socks5://${p.uuid}@${p.address}:${p.port}#${Uri.encode(p.name)}"

    private fun encodeTuic(p: VpnProfile) =
        Uri.Builder().scheme("tuic")
            .encodedAuthority("${p.uuid}:${p.ssPassword}@${p.address}:${p.port}")
            .appendQueryParameter("sni", p.sni)
            .fragment(p.name).build().toString()
}
