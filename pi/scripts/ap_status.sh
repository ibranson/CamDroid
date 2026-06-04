#!/usr/bin/env bash
# Print the current state of the CamDroid AP for diagnostics.

set -euo pipefail

PROFILE_NAME="camdroid-ap"
IFACE="wlan0"

if ! command -v nmcli >/dev/null 2>&1; then
    echo "error: nmcli not found" >&2
    exit 1
fi

echo "--- profile ----------------------------------------------------"
if nmcli -t -f NAME connection show | grep -Fxq "$PROFILE_NAME"; then
    nmcli -t -f NAME,STATE,DEVICE connection show --active | grep "^$PROFILE_NAME:" \
        || echo "$PROFILE_NAME exists but is not active"
    ssid="$(nmcli -g 802-11-wireless.ssid connection show "$PROFILE_NAME" 2>/dev/null || echo '?')"
    psk="$(nmcli -s -g 802-11-wireless-security.psk connection show "$PROFILE_NAME" 2>/dev/null || true)"
    echo "  SSID:       $ssid"
    if [ -n "$psk" ]; then
        echo "  Passphrase: $psk"
    elif [ "$EUID" -ne 0 ]; then
        echo "  Passphrase: (re-run with sudo to reveal)"
    else
        echo "  Passphrase: (not set on profile)"
    fi
else
    echo "$PROFILE_NAME profile not installed. Run scripts/install_ap.sh."
    exit 0
fi

echo
echo "--- $IFACE address --------------------------------------------"
ip -4 addr show dev "$IFACE" 2>/dev/null | awk '/inet /{print "  "$0}' \
    || echo "  (no v4 address on $IFACE)"

echo
echo "--- connected clients ------------------------------------------"
if command -v iw >/dev/null 2>&1; then
    stations="$(iw dev "$IFACE" station dump 2>/dev/null | awk '/^Station/{print "  "$2}')"
    if [ -n "$stations" ]; then
        echo "$stations"
    else
        echo "  (none)"
    fi
else
    echo "  iw not installed; cannot enumerate stations"
fi
