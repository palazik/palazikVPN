# palazikVPN

palazikVPN is a modern Android VPN client built with Kotlin, Jetpack Compose, Material 3, Hilt, OkHttp, and libv2ray/Xray. It focuses on importing proxy profiles, managing subscriptions, and running a full-device VPN through Android's `VpnService`.

## Features

- Full-device VPN mode powered by libv2ray/Xray
- Miuix and Material 3 Expressive design systems with light, dark, dynamic, and custom color themes
- English and Russian UI languages (system default, English, or Русский)
- Profile import from share links, clipboard, and subscriptions
- QR import from image files and live camera, plus QR export for profile sharing
- Import preview with parsed protocol, server, transport, security, and validation warnings
- Manual profile editor with validation, masked secret fields, per-profile `allowInsecure`, and VMess cipher selection
- Per-profile overrides: multiplexing (mux) on/off and TLS fragment (anti-DPI) toggles
- Profile duplicate, export as native link, export as palazikVPN link, and export generated JSON
- Profile search and sort (by name or latency) with grouping per subscription
- Subscription refresh with progress and partial-failure messages, plus configurable subscription User-Agent
- Subscription data usage and expiry display (parsed from the `Subscription-Userinfo` header)
- Background auto-update of subscriptions on a configurable interval (WorkManager)
- TCP / HTTP GET / HTTP HEAD latency tests, run concurrently for "ping all" and "choose best"
- Live connection state, connected duration, and traffic counters
- Quick Settings tile, a home-screen widget, and a notification **Disconnect** action
- Clear connection error panel with retry action
- Diagnostics log with copy-to-clipboard support
- Custom DNS settings, optional FakeDNS, and a selectable routing domain strategy
- Routing presets (rule-based, global, bypass LAN), ad blocking, bypass China, and custom direct/blocked domain lists
- TLS fragmentation (anti-DPI) with configurable packets/length/interval, applied per profile
- IPv6 leak protection (IPv6 is always captured by the tunnel) with an optional IPv6-through-tunnel toggle
- Kill switch (lockdown) that blocks traffic while the tunnel is not ready
- Backup and restore: export all profiles to a file and re-import on another device
- Split tunneling by choosing installed apps to bypass the VPN
- Optional auto-connect on boot when VPN permission has already been granted
- ABI split APKs for `arm64-v8a` and `armeabi-v7a`, plus a universal APK in CI artifacts

## Supported Profile Types

Import support currently includes:

- `vmess://`
- `vless://`
- `ss://`
- `trojan://`
- `hysteria2://`
- `wireguard://`
- `socks5://`
- `tuic://`
- `anytls://`
- `xhttp://` as VLESS + XHTTP transport
- `httpproxy://` for HTTP-proxy profiles (plain `http://` is treated as a subscription URL, not a profile)
- `palazikvpn://` proprietary share links

Native share links (`vmess://`, `vless://`, `ss://`, etc.) and `palazikvpn://` links also open directly from a browser or file manager via deep links.

## Screens

- **Home**: connect/disconnect, active profile, connection status, duration, traffic, error retry, and quick ping
- **Profiles**: link/clipboard/QR import, import preview, manual add/edit (incl. per-profile mux and TLS-fragment toggles), search & sort, duplicate, ping, export, QR share, delete with confirmation/undo
- **Subscriptions**: add, refresh, update all, choose best by latency, data usage/expiry, delete with confirmation
- **Settings**: style (design system, dark mode, theme), language (English/Russian), ping mode, DNS, routing & privacy (routing presets, domain strategy, FakeDNS, ad block, bypass China, IPv6, kill switch, TLS fragment params, custom domains), subscription User-Agent, backup/restore, split tunneling, startup, diagnostics, app info

## Tech Stack

- Kotlin
- Jetpack Compose
- Material 3 Expressive + Miuix design systems
- Navigation Compose
- Hilt
- WorkManager (background subscription updates)
- OkHttp
- ZXing QR encoder/decoder
- Android `VpnService` + Quick Settings `TileService`
- libv2ray / Xray via `libv2ray.aar`

## Repository Notes

Large runtime files are intentionally not committed:

- `app/libs/libv2ray.aar`
- `app/src/main/assets/geoip.dat`
- `app/src/main/assets/geosite.dat`

The GitHub Actions build downloads these files automatically before compiling.

## Build With GitHub Actions

The main workflow is:

```text
.github/workflows/build.yml
```

Run it manually from GitHub Actions using **Build palazikVPN**. The workflow:

1. Sets up JDK 17 and Gradle 8.9
2. Regenerates the Gradle wrapper
3. Installs Android SDK, NDK, CMake, and ccache
4. Downloads `libv2ray.aar`, `geoip.dat`, and `geosite.dat`
5. Builds debug APKs
6. Builds release APKs (signed if signing secrets are configured, otherwise unsigned)
7. Uploads APKs as GitHub Actions artifacts
8. Optionally sends per-ABI APKs to Telegram if repository secrets are configured

Telegram upload requires:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`

### Release signing

Release APKs are signed automatically when these repository secrets are present (if
they are absent, the workflow still produces an unsigned release as before):

- `KEYSTORE_BASE64` — your keystore (`.jks`) base64-encoded: `base64 -w0 release.jks`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Generate a keystore once with:

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias palazik
```

Keep `release.jks` and its passwords out of git (they are gitignored). For local signed
builds, create `keystore.properties` in the project root instead:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=palazik
keyPassword=…
```

## Local Build

This checkout may not include the Gradle wrapper jar or the large runtime assets. To build locally, prepare them first.

```bash
gradle wrapper --gradle-version=8.9
chmod +x gradlew
mkdir -p app/libs app/src/main/assets
```

Download runtime files:

```bash
curl -fL -o app/libs/libv2ray.aar \
  https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.5.3/libv2ray.aar

curl -fL -o app/src/main/assets/geoip.dat \
  https://github.com/v2fly/geoip/releases/latest/download/geoip.dat

curl -fL -o app/src/main/assets/geosite.dat \
  https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat
```

Then build:

```bash
./gradlew assembleDebug --no-daemon --no-configuration-cache
```

Release APKs are unsigned unless you provide signing secrets (CI) or a
`keystore.properties` (local) — see [Release signing](#release-signing).

## Android Permissions

The app uses:

- Internet access
- Android VPN service binding
- Foreground service
- Notification permission on Android 13+
- Boot completed receiver for optional auto-connect
- Network state/change access for underlying-network handling

## Important Caveats

- Always-on VPN still needs to be configured by the user in Android system settings. The in-app kill switch (lockdown) blocks traffic while the tunnel is not ready, but full system-level lockdown requires enabling **Always-on VPN** + **Block connections without VPN** in Android settings.
- IPv6 is always captured by the tunnel to prevent leaks; the "Route IPv6 through tunnel" toggle only controls whether IPv6 is dialled out (otherwise it is dropped, and apps fall back to IPv4).
- Auto-connect on boot only works when Android has already granted VPN permission.
- Subscription and profile compatibility depends on the fields present in imported links.
- Some advanced Xray options are not exposed in the UI yet.
- AnyTLS, FakeDNS, and TLS fragmentation depend on the bundled Xray core supporting them; older cores may ignore or reject these configs.
- Protocols that are exclusive to other cores (e.g. SSH, Juicity, Mieru, ShadowTLS in sing-box) are intentionally not offered, since this app runs on Xray.
- Shadowsocks SIP003 plugins (obfs / v2ray-plugin) are not supported by the bundled Xray core.
- QR import reads QR codes from selected image files / live camera.
- Release APKs are signed only when signing secrets / `keystore.properties` are configured; otherwise the unsigned output must be signed before distribution.

## Project Structure

```text
app/src/main/java/com/palazik/vpn/
  data/        models, codecs, repository, validation
  di/          Hilt dependency injection
  receiver/    boot receiver
  service/     VPN service and Xray config builder
  ui/          Activity, screens, theme, viewmodel
```

## License

See [LICENSE](LICENSE).
