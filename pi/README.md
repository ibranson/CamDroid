# pi/ — CamDroid Pi-side daemon

Python daemon that USB-tethers the camera and serves the v0 API.

## First-time setup on the Pi

```bash
cd ~/CamDroid/pi
python3 -m venv --system-site-packages .venv
source .venv/bin/activate
pip install -e .
```

The `--system-site-packages` flag is intentional: the `python3-gphoto2`
binding is installed at the system level via `apt`, and the venv layers
the Python deps (`fastapi`, `uvicorn`, `pillow`, ...) on top. This avoids
re-compiling `libgphoto2` bindings inside the venv.

## Smoke tests

Stage 1 (just the camera wrapper, prints to stdout):

```bash
source .venv/bin/activate
python -m scripts.smoke_test ~/camdroid-images
```

Stage 2 (camera + storage + variants + SQLite index):

```bash
python -m scripts.smoke_test_2 ~/camdroid-storage
```

## Run the daemon manually (development)

```bash
source .venv/bin/activate
camdroid ~/camdroid-storage
# or:
python -m camdroid ~/camdroid-storage
```

Defaults to binding `0.0.0.0:8080`. Once running, point a browser at
`http://camdroid.local:8080/api/v0/status` to see the live snapshot.

After re-installing dependencies (e.g. after pulling new code), run
`pip install -e .` again from `pi/` to refresh the `camdroid` script
entry point.

## Install as a systemd service (autostart on boot)

```bash
chmod +x scripts/install_service.sh
./scripts/install_service.sh
```

The script installs `/etc/systemd/system/camdroid.service`, enables it,
and starts it. Reboots will bring the daemon up automatically; crashes
are auto-restarted with a 2-second backoff.

Useful commands once installed:

```bash
journalctl -u camdroid -f             # live log tail
sudo systemctl status camdroid        # current state + last lines of log
sudo systemctl restart camdroid       # cycle the service
sudo systemctl stop camdroid          # stop without disabling autostart
./scripts/uninstall_service.sh        # remove entirely
```

The default storage root in the systemd unit is `~/camdroid-storage`.
Edit `systemd/camdroid.service.template` and re-run the install script
to change it.

## Field-mode Wi-Fi AP

Turn the Pi into its own access point so the tablet connects directly,
no external router required. Uses NetworkManager's built-in AP/shared
mode (handles DHCP + DNS in one profile, no hostapd/dnsmasq config).

```bash
sudo ./scripts/install_ap.sh
```

The script:
- Sets the Wi-Fi regulatory country (default `US`, override with
  `--country GB` etc.)
- Picks an SSID `CamDroid-XXXX` based on `wlan0`'s MAC suffix
- Generates a random 12-character passphrase on first run (preserved on
  re-runs unless you pass `--regenerate`)
- Removes any other Wi-Fi profiles (this Pi is a field appliance, not a
  dev box on home Wi-Fi)
- Activates the profile and prints the SSID + passphrase **once**

Diagnostics and tear-down:

```bash
./scripts/ap_status.sh         # SSID, passphrase, IP, connected clients
sudo ./scripts/uninstall_ap.sh # remove the AP profile entirely
```

The daemon's systemd unit now passes `--bind-interface wlan0`, so the
API only listens on the AP-side IP. If you reboot, the AP comes up on
its own and the tablet reconnects automatically.
