#!/usr/bin/env bash
# Install the camdroid systemd unit on this Pi.
#
# Usage:
#   ./scripts/install_service.sh                 # uses $USER
#   ./scripts/install_service.sh someuser        # explicit user
#
# Re-running is safe — it overwrites the unit and re-enables the service.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$SCRIPT_DIR/../systemd/camdroid.service.template"
TARGET=/etc/systemd/system/camdroid.service

USER_NAME="${1:-$USER}"
HOME_DIR=$(getent passwd "$USER_NAME" | cut -d: -f6)

if [ -z "$HOME_DIR" ]; then
    echo "error: could not resolve home dir for user '$USER_NAME'" >&2
    exit 1
fi

if [ ! -f "$TEMPLATE" ]; then
    echo "error: template not found at $TEMPLATE" >&2
    exit 1
fi

if [ ! -x "$HOME_DIR/CamDroid/pi/.venv/bin/camdroid" ]; then
    echo "error: $HOME_DIR/CamDroid/pi/.venv/bin/camdroid is not executable." >&2
    echo "       Run 'pip install -e .' from $HOME_DIR/CamDroid/pi first." >&2
    exit 1
fi

echo "Installing camdroid service for user '$USER_NAME' (home: $HOME_DIR)"

sed -e "s|__USER__|$USER_NAME|g" -e "s|__HOME__|$HOME_DIR|g" "$TEMPLATE" \
    | sudo tee "$TARGET" >/dev/null

sudo systemctl daemon-reload
sudo systemctl enable --now camdroid.service

# Avahi service file so Android tablets can discover the Pi over mDNS.
# Avahi watches /etc/avahi/services/ live; no daemon restart needed.
AVAHI_SRC="$SCRIPT_DIR/../avahi/camdroid.service"
AVAHI_DST=/etc/avahi/services/camdroid.service
if [ -f "$AVAHI_SRC" ]; then
    sudo cp "$AVAHI_SRC" "$AVAHI_DST"
    echo "Installed avahi service file -> $AVAHI_DST"
else
    echo "warning: avahi service file not found at $AVAHI_SRC; mDNS discovery disabled"
fi

# Make sure avahi is running.
if systemctl list-unit-files avahi-daemon.service &>/dev/null; then
    sudo systemctl enable --now avahi-daemon.service || true
fi

echo
echo "Done. Service status:"
sudo systemctl --no-pager --full status camdroid.service || true
echo
echo "Tail logs with:    journalctl -u camdroid -f"
echo "Restart with:      sudo systemctl restart camdroid"
echo "Stop with:         sudo systemctl stop camdroid"
echo "Disable autostart: sudo systemctl disable camdroid"
echo
echo "Next: for field-mode AP setup, run: sudo ./scripts/install_ap.sh"
