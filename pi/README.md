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
