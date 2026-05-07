# palazikVPN

palazikVPN is a modern Android VPN client built with Kotlin, Jetpack Compose, Material 3, Hilt, OkHttp, and libv2ray/Xray. It focuses on importing proxy profiles, managing subscriptions, and running a full-device VPN through Android's `VpnService`.

## Features

- Full-device VPN mode powered by libv2ray/Xray
- Material 3 UI with light, dark, dynamic, and custom color themes
- Profile import from share links and subscriptions
- QR import from image files and QR export for profile sharing
- Import preview with parsed protocol, server, transport, security, and validation warnings
- Manual profile editor with validation and masked secret fields
- Profile duplicate, export as native link, export as palazikVPN link, and export generated JSON
- Subscription refresh with progress and partial-failure messages
- TCP / HTTP GET / HTTP HEAD latency tests
- Live connection state, connected duration, and traffic counters
- Clear connection error panel with retry action
- Diagnostics log with copy-to-clipboard support
- Custom DNS settings
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
- `xhttp://` as VLESS + XHTTP transport
- `palazikvpn://` proprietary share links

## Screens

- **Home**: connect/disconnect, active profile, connection status, duration, traffic, error retry, and quick ping
- **Profiles**: link import, QR import, import preview, manual add/edit, duplicate, ping, export, QR share, delete with confirmation/undo
- **Subscriptions**: add, refresh, update all, delete with confirmation
- **Settings**: theme, ping mode, DNS, split tunneling, startup, diagnostics, app info

## Tech Stack

- Kotlin
- Jetpack Compose
- Material 3
- Navigation Compose
- Hilt
- OkHttp
- ZXing QR encoder/decoder
- Android `VpnService`
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
6. Builds unsigned release APKs
7. Uploads APKs as GitHub Actions artifacts
8. Optionally sends per-ABI APKs to Telegram if repository secrets are configured

Telegram upload requires:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`

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

Release APKs produced by the default workflow are unsigned.

## Android Permissions

The app uses:

- Internet access
- Android VPN service binding
- Foreground service
- Notification permission on Android 13+
- Boot completed receiver for optional auto-connect
- Network state/change access for underlying-network handling

## Important Caveats

- Always-on VPN still needs to be configured by the user in Android system settings.
- Auto-connect on boot only works when Android has already granted VPN permission.
- Subscription and profile compatibility depends on the fields present in imported links.
- Some advanced Xray options are not exposed in the UI yet.
- QR import reads QR codes from selected image files; live camera scanning is not implemented yet.
- Unsigned release APKs from CI must be signed before distribution outside debug/testing use.

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
