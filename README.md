# palazikVPN

[![Release](https://img.shields.io/github/v/release/palazik/palazikVPN?sort=semver&color=00E5FF)](https://github.com/palazik/palazikVPN/releases/latest)
[![Build](https://github.com/palazik/palazikVPN/actions/workflows/build.yml/badge.svg)](https://github.com/palazik/palazikVPN/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/palazik/palazikVPN)](LICENSE)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
[![Stars](https://img.shields.io/github/stars/palazik/palazikVPN?style=social)](https://github.com/palazik/palazikVPN/stargazers)

A clean, fast, open-source proxy client for Android. Built on Xray (libv2ray) with a
Jetpack Compose UI, palazikVPN imports your servers, manages subscriptions, and runs a
full-device tunnel through Android's `VpnService`. **No accounts, no telemetry** — your
configs stay on your device.

> Bring your own servers, or generate a free Cloudflare WARP profile in one tap.

## Highlights

- **Material 3 Expressive UI** — with an optional Miuix-animations toggle for springy, Xiaomi-style list overscroll and animated theme transitions.
- **One-tap WARP** — generate a free Cloudflare WARP/WireGuard profile so a brand-new user can connect with zero setup (Android 13+).
- **Broad protocol support** — VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard, AnyTLS, SOCKS5, HTTP, plus REALITY/XTLS and the WS/gRPC/H2/QUIC/XHTTP transports.
- **Subscriptions** — auto-update on a schedule, custom User-Agent, and live data-usage / expiry parsed from the `Subscription-Userinfo` header.
- **Split tunneling** — route specific apps *around* the tunnel, or route *only* selected apps through it.
- **Smart routing** — presets (rule-based / global / bypass-LAN), selectable domain strategy, ad blocking, China bypass, custom direct/blocked domain lists, and FakeDNS.
- **Anti-DPI** — per-profile TLS fragmentation with configurable packet sizes.
- **Privacy** — kill switch (lockdown), IPv6 leak protection, and a per-profile mux toggle.

## Features

**Connection**
- Full-device VPN via Xray, with a Quick Settings tile, a home-screen widget, and a notification **Disconnect** action
- Live connection state, duration, and traffic counters
- Auto-connect on boot (when VPN permission is already granted)
- Kill switch that blocks traffic until the tunnel is ready
- In-app update checker against GitHub releases

**Profiles**
- Import from share links, clipboard, QR (camera or image), or a subscription URL
- Import preview with parsed protocol/server/transport/security and validation warnings
- Manual editor with masked secrets, per-profile `allowInsecure`, mux toggle, and TLS-fragment toggle
- Duplicate, export as native link / palazikVPN link / generated JSON, share as QR
- Search, sort (name or latency), and grouping per subscription
- TCP / HTTP GET / HTTP HEAD latency tests, run concurrently for "ping all" and "choose best"

**Subscriptions**
- Add, refresh, update all, and pick the fastest server by latency
- Background auto-update on a configurable interval (WorkManager)
- Data usage and expiry shown per subscription

**Settings**
- Appearance: dark mode, color themes (Cyber, Ocean, Forest, Sunset, Rose, Violet, AMOLED, Dynamic), a Miuix-animations toggle, and app language (English / Russian)
- Routing & privacy: presets, domain strategy, FakeDNS, ad block, China bypass, IPv6, kill switch, TLS-fragment params, custom domains
- DNS: VPN / remote / direct servers
- Geo files: override the bundled `geoip.dat` / `geosite.dat` from a URL
- Subscriptions: auto-update interval and fetch User-Agent
- Split tunneling, backup/restore, startup, diagnostics log (copy or save to file), and app info

**First run**
- A short onboarding flow that points new users at importing a config and granting VPN permission

## Supported import schemes

`vmess://` · `vless://` · `ss://` · `trojan://` · `hysteria2://` · `tuic://` ·
`anytls://` · `wireguard://` · `socks5://` · `xhttp://` (VLESS + XHTTP transport) ·
`httpproxy://` · `palazikvpn://`

These open directly from a browser or file manager via deep links. Plain `http(s)://`
links are treated as subscription URLs, not single profiles.

## Tech stack

Kotlin · Jetpack Compose · Material 3 Expressive (+ optional Miuix animations) · Navigation Compose · Hilt ·
WorkManager · OkHttp · ZXing · Android `VpnService` + Quick Settings tile + home-screen
widget · Xray via `libv2ray.aar`.

## Repository layout

The repo is a monorepo:

```text
android/   Kotlin / Jetpack Compose app (Gradle project)
ios/       Swift / SwiftUI app + NEPacketTunnelProvider (XcodeGen project)
docs/      website (GitHub Pages)
```

## Building — Android

Some large runtime files are intentionally **not** committed and are fetched at build time:

- `android/app/libs/libv2ray.aar`
- `android/app/src/main/assets/geoip.dat`
- `android/app/src/main/assets/geosite.dat`

### GitHub Actions (recommended)

The **Android CI** workflow builds debug and release APKs from `android/` and uploads them as artifacts.
Run it from the **Actions** tab → **Android CI** → **Run workflow**, where you can also:

- **Publish a GitHub Release** — tick the box and enter a tag (e.g. `v2.0.2`) to attach the release APKs to a new GitHub Release.
- Receive per-ABI APKs in Telegram if `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` are set.

The workflow downloads the runtime files, then builds ABI-split APKs (`arm64-v8a`,
`armeabi-v7a`) plus a universal APK.

### Local build

```bash
cd android
gradle wrapper --gradle-version=9.4.1
chmod +x gradlew
mkdir -p app/libs app/src/main/assets

curl -fL -o app/libs/libv2ray.aar \
  https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.5.3/libv2ray.aar
curl -fL -o app/src/main/assets/geoip.dat \
  https://github.com/v2fly/geoip/releases/latest/download/geoip.dat
curl -fL -o app/src/main/assets/geosite.dat \
  https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat

./gradlew assembleDebug
```

## Building — iOS

The iOS app runs Xray-core in a `NEPacketTunnelProvider` via the
[SwiftyXrayKit](https://github.com/dima-u/SwiftyXrayKit) Swift package (which bundles the
`LibXray.xcframework` from [XTLS/libXray](https://github.com/XTLS/libXray)). Geo files are
the same `.dat` files as Android, bundled into the extension.

The **iOS CI** workflow runs on a macOS runner: it installs XcodeGen, generates the Xcode
project from `ios/project.yml`, builds an **unsigned** `.ipa`, and sends it to the maintainer
DM via Telegram. To build/run on a real device you need an Apple Developer account (the
Network Extension entitlement is not available to free accounts) and your own signing.

```bash
cd ios
brew install xcodegen
xcodegen generate
open palazikVPN.xcodeproj   # set your team + bundle IDs to run on a device
```

## Signing

Release builds are signed automatically when credentials are present, and stay unsigned
otherwise (so a fork without secrets still builds).

- **CI:** set the repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The workflow decodes the keystore to a temp file at build time — it never lives in git.
- **Local:** create a `keystore.properties` in `android/`:

  ```properties
  storeFile=/absolute/path/to/release.jks
  storePassword=…
  keyAlias=…
  keyPassword=…
  ```

Generate a keystore once:

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias palazik
```

`release.jks`, `keystore.properties`, and the runtime files above are all gitignored —
nothing secret is ever committed.

## Permissions

Internet, VPN service binding, foreground service, notifications (Android 13+), boot
receiver (optional auto-connect), and network-state access for underlying-network handling.

## Notes & limitations

- One-tap WARP generation needs Android 13+ (it uses the platform X25519 provider). On older devices, import a WARP config manually.
- AnyTLS, FakeDNS, and TLS fragmentation require the bundled Xray core to support them; older cores may ignore or reject these options.
- Protocols exclusive to other cores (SSH, Juicity, Mieru, ShadowTLS — all sing-box) are intentionally not offered, since this app runs on Xray.
- Shadowsocks SIP003 plugins (obfs / v2ray-plugin) are not supported by the bundled core.
- A full system-level kill switch also needs Android's **Always-on VPN** + **Block connections without VPN**.
- Google Play is hostile to proxy/VPN apps; the primary distribution channels are GitHub releases and sideloading.

## Project structure

```text
android/app/src/main/java/com/palazik/vpn/
  data/        models, codecs, repository, WARP provisioning, validation
  di/          Hilt modules
  receiver/    boot receiver
  service/     VPN service + Xray config builder
  ui/          activity, screens, theme, locale, viewmodel
  widget/      home-screen widget

ios/
  App/         SwiftUI app (entry, views, store, VPN manager)
  PacketTunnel/ NEPacketTunnelProvider running Xray via SwiftyXrayKit
  Shared/      App Group identifiers shared by both targets
  project.yml  XcodeGen project spec
```

## License

See [LICENSE](LICENSE).
