#!/usr/bin/env bash
# Installe Hex sur le Nothing Phone (1) branché en USB (Mac ou Linux).
# Usage : ./install.sh chemin/vers/hex.apk
set -euo pipefail
cd "$(dirname "$0")"
APK="${1:-hex.apk}"
PKG=com.rod.hex

# adb installé dans ~/hex/platform-tools (voir le guide)
for d in "$HOME/hex/platform-tools" "$(pwd)/platform-tools"; do if [ -x "$d/adb" ]; then export PATH="$d:$PATH"; fi; done
command -v adb >/dev/null || { echo "adb introuvable : installe les Android platform-tools (voir le guide)."; exit 1; }
[ -f "$APK" ] || { echo "APK introuvable : $APK"; exit 1; }
[ -f hex-config.txt ] || { echo "Copie hex-config.exemple.txt en hex-config.txt et remplis-le."; exit 1; }

echo "1/5  Téléphone détecté ?"
adb wait-for-device
adb devices

echo "2/5  Installation de Hex"
adb install -r "$APK"

echo "3/5  Hex devient propriétaire de l'appareil (mode kiosque)"
adb shell dpm set-device-owner "$PKG/.HexAdminReceiver" || echo "   (déjà fait, ou un compte est présent sur le téléphone : voir le guide)"

echo "4/5  Permissions Glyph"
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
adb shell settings put global nt_glyph_interface_debug_enable 1

echo "5/5  Envoi de la configuration"
get() { { grep -E "^$1=" hex-config.txt || true; } | head -n 1 | cut -d= -f2- | tr -d '\r' ; }
ARGS=()
for pair in claude:CLAUDE tts:TTS pin:PIN model:MODEL budget:BUDGET; do
  v="$(get "${pair#*:}")"
  if [ -n "$v" ]; then ARGS+=(--es "${pair%%:*}" "$v"); fi
done
adb shell am start -n "$PKG/.SetupActivity" "${ARGS[@]}"

echo
echo "Terminé. Au premier démarrage, Hex télécharge son modèle « Hey Hex » (environ 40 Mo), puis se présente."
