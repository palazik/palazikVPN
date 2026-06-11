#!/bin/bash
# palazikVPN Linux installer — packaged into a self-extracting .run by CI (makeself).
# Installs to /opt/palazikVPN, adds the `palazikvpn` command, a desktop entry with
# proxy-scheme deep links (like Android), and an uninstaller.
set -e

if [ "$(id -u)" -ne 0 ]; then
  echo "→ Root is required to install into /opt — re-running with sudo…"
  exec sudo bash "$0" "$@"
fi

APP_DIR=/opt/palazikVPN
BIN_LINK=/usr/local/bin/palazikvpn
DESKTOP=/usr/share/applications/palazikvpn.desktop

echo "→ Installing palazikVPN to $APP_DIR"
rm -rf "$APP_DIR"
mkdir -p /opt
cp -r ./palazikVPN "$APP_DIR"
# The CI staging dir is created by mktemp (mode 700) — without this, /opt/palazikVPN
# ends up unreadable for normal users and the launcher "is not an executable file".
chmod -R a+rX "$APP_DIR"
chmod +x "$APP_DIR/bin/palazikVPN"

echo "→ Creating command: palazikvpn"
ln -sf "$APP_DIR/bin/palazikVPN" "$BIN_LINK"

ICON="$APP_DIR/lib/palazikVPN.png"
echo "→ Creating desktop entry"
cat > "$DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=palazikVPN
Comment=Open-source Xray proxy client
Exec=$APP_DIR/bin/palazikVPN %u
Icon=$ICON
Terminal=false
Categories=Network;
MimeType=x-scheme-handler/palazikvpn;x-scheme-handler/vmess;x-scheme-handler/vless;x-scheme-handler/ss;x-scheme-handler/trojan;x-scheme-handler/hysteria2;x-scheme-handler/wireguard;x-scheme-handler/socks5;x-scheme-handler/tuic;x-scheme-handler/anytls;x-scheme-handler/xhttp;x-scheme-handler/httpproxy;
EOF
update-desktop-database /usr/share/applications 2>/dev/null || true

echo "→ Writing uninstaller: $APP_DIR/uninstall.sh"
cat > "$APP_DIR/uninstall.sh" <<EOF
#!/bin/bash
set -e
if [ "\$(id -u)" -ne 0 ]; then exec sudo bash "\$0" "\$@"; fi
rm -f "$BIN_LINK" "$DESKTOP"
rm -f "\$HOME/.config/autostart/palazikvpn.desktop" 2>/dev/null || true
rm -rf "$APP_DIR"
update-desktop-database /usr/share/applications 2>/dev/null || true
echo "palazikVPN uninstalled. Your profiles/settings in ~/.config/palazikVPN were kept."
EOF
chmod +x "$APP_DIR/uninstall.sh"

echo ""
echo "✓ palazikVPN installed."
echo "  Run it with:    palazikvpn   (or from your app launcher)"
echo "  Uninstall with: sudo $APP_DIR/uninstall.sh"
