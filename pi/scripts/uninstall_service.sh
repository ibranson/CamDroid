#!/usr/bin/env bash
# Stop, disable, and remove the camdroid systemd unit.
set -euo pipefail

TARGET=/etc/systemd/system/camdroid.service

if [ -f "$TARGET" ]; then
    sudo systemctl stop camdroid.service || true
    sudo systemctl disable camdroid.service || true
    sudo rm -f "$TARGET"
    sudo systemctl daemon-reload
    echo "camdroid service uninstalled."
else
    echo "no camdroid.service unit found at $TARGET — nothing to do."
fi
