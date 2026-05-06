#!/usr/bin/env bash
# Install the CamDroid Wi-Fi access point profile via NetworkManager.
#
# Re-running is safe: existing passphrase is preserved unless --regenerate
# is passed. Existing 802-11-wireless profiles other than camdroid-ap are
# deleted (this Pi is a field appliance, not a dev machine on home Wi-Fi).
#
# Usage:
#   sudo ./scripts/install_ap.sh
#   sudo ./scripts/install_ap.sh --country GB
#   sudo ./scripts/install_ap.sh --regenerate
#   sudo ./scripts/install_ap.sh --ssid-suffix BEEF       # for testing
#   sudo ./scripts/install_ap.sh --force-country US

set -euo pipefail

PROFILE_NAME="camdroid-ap"
IFACE="wlan0"
DEFAULT_COUNTRY="US"

COUNTRY=""
FORCE_COUNTRY=0
REGENERATE=0
SSID_SUFFIX_OVERRIDE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --country)         COUNTRY="${2:-}"; shift 2 ;;
        --force-country)   FORCE_COUNTRY=1; shift ;;
        --regenerate)      REGENERATE=1; shift ;;
        --ssid-suffix)     SSID_SUFFIX_OVERRIDE="${2:-}"; shift 2 ;;
        -h|--help)
            sed -n '2,12p' "$0" | sed 's|^# \{0,1\}||'
            exit 0
            ;;
        *) echo "error: unknown arg: $1" >&2; exit 2 ;;
    esac
done

if [ "$EUID" -ne 0 ]; then
    echo "error: must run as root (try: sudo $0)" >&2
    exit 1
fi

if ! command -v nmcli >/dev/null 2>&1; then
    echo "error: nmcli not found. Install NetworkManager first." >&2
    exit 1
fi

if ! systemctl is-active --quiet NetworkManager; then
    echo "error: NetworkManager is not active. Start it with:" >&2
    echo "    sudo systemctl enable --now NetworkManager" >&2
    exit 1
fi

if ! ip link show "$IFACE" >/dev/null 2>&1; then
    echo "error: interface $IFACE not found." >&2
    exit 1
fi

# --- Country code ---------------------------------------------------------
current_country="$(iw reg get 2>/dev/null | awk '/country/ {print $2; exit}' | tr -d ':' || true)"
if [ -z "$current_country" ] || [ "$current_country" = "00" ] || [ "$FORCE_COUNTRY" -eq 1 ]; then
    desired="${COUNTRY:-$DEFAULT_COUNTRY}"
    echo "Setting Wi-Fi regulatory country to $desired"
    if command -v raspi-config >/dev/null 2>&1; then
        raspi-config nonint do_wifi_country "$desired" || true
    else
        iw reg set "$desired" || true
    fi
else
    echo "Wi-Fi regulatory country already set to $current_country (use --force-country to override)"
fi

# --- SSID -----------------------------------------------------------------
if [ -n "$SSID_SUFFIX_OVERRIDE" ]; then
    suffix="$SSID_SUFFIX_OVERRIDE"
else
    mac="$(cat "/sys/class/net/$IFACE/address" 2>/dev/null || true)"
    if [ -z "$mac" ]; then
        echo "error: could not read MAC for $IFACE" >&2
        exit 1
    fi
    # Last 4 hex chars, uppercase, no colon.
    suffix="$(echo "$mac" | tr -d ':' | tr 'a-z' 'A-Z')"
    suffix="${suffix: -4}"
fi
SSID="CamDroid-$suffix"

# --- Passphrase -----------------------------------------------------------
existing_psk=""
if nmcli -t -f NAME connection show | grep -Fxq "$PROFILE_NAME"; then
    existing_psk="$(nmcli -s -g 802-11-wireless-security.psk connection show "$PROFILE_NAME" 2>/dev/null || true)"
fi

if [ "$REGENERATE" -eq 1 ] || [ -z "$existing_psk" ]; then
    PSK="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 12)"
    psk_is_new=1
else
    PSK="$existing_psk"
    psk_is_new=0
fi

# --- Delete other wifi profiles ------------------------------------------
echo "Removing other Wi-Fi profiles (AP-only mode)..."
while IFS=: read -r name type; do
    [ "$type" = "802-11-wireless" ] || continue
    [ "$name" = "$PROFILE_NAME" ] && continue
    echo "  removing: $name"
    nmcli connection delete "$name" >/dev/null 2>&1 || true
done < <(nmcli -t -f NAME,TYPE connection show)

# --- Create or update the AP profile -------------------------------------
if nmcli -t -f NAME connection show | grep -Fxq "$PROFILE_NAME"; then
    echo "Updating existing $PROFILE_NAME profile"
else
    echo "Creating $PROFILE_NAME profile"
    nmcli connection add type wifi ifname "$IFACE" con-name "$PROFILE_NAME" \
        autoconnect yes ssid "$SSID" >/dev/null
fi

nmcli connection modify "$PROFILE_NAME" \
    802-11-wireless.mode ap \
    802-11-wireless.band bg \
    802-11-wireless.ssid "$SSID" \
    ipv4.method shared \
    ipv6.method ignore \
    wifi-sec.key-mgmt wpa-psk \
    wifi-sec.psk "$PSK" \
    connection.autoconnect yes \
    connection.autoconnect-priority 100

# --- Activate -------------------------------------------------------------
echo "Bringing up $PROFILE_NAME..."
nmcli connection up "$PROFILE_NAME" >/dev/null

# Give NM a moment to assign the v4 address before we read it back.
for _ in 1 2 3 4 5; do
    gw_ip="$(ip -4 -o addr show dev "$IFACE" 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -n1)"
    [ -n "$gw_ip" ] && break
    sleep 1
done
gw_ip="${gw_ip:-<pending>}"

# --- Print credentials box -----------------------------------------------
cat <<EOF

==============================================================
  CamDroid AP active
--------------------------------------------------------------
  SSID:        $SSID
  Passphrase:  $PSK
  Gateway IP:  $gw_ip
  Profile:     $PROFILE_NAME
==============================================================
EOF

if [ "$psk_is_new" -eq 1 ]; then
    cat <<EOF
NOTE: This passphrase will not be displayed again. Save it on the
tablet now. Re-run with --regenerate to roll a new one.
EOF
else
    cat <<EOF
NOTE: Existing passphrase preserved. Re-run with --regenerate to
roll a new one.
EOF
fi
