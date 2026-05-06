#!/usr/bin/env bash
# Tear down the CamDroid AP profile.
#
# Note: this does NOT restore any station-mode profiles that install_ap.sh
# removed. After uninstall_ap.sh the Pi has no Wi-Fi profile at all; you'll
# need to re-add one with `nmcli device wifi connect` or re-run install_ap.sh.

set -euo pipefail

PROFILE_NAME="camdroid-ap"

if [ "$EUID" -ne 0 ]; then
    echo "error: must run as root (try: sudo $0)" >&2
    exit 1
fi

if ! command -v nmcli >/dev/null 2>&1; then
    echo "error: nmcli not found" >&2
    exit 1
fi

if nmcli -t -f NAME connection show | grep -Fxq "$PROFILE_NAME"; then
    nmcli connection down "$PROFILE_NAME" >/dev/null 2>&1 || true
    nmcli connection delete "$PROFILE_NAME" >/dev/null
    echo "Removed $PROFILE_NAME profile."
else
    echo "$PROFILE_NAME profile not found; nothing to do."
fi
