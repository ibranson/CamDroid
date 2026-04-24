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

## Run the daemon (Stage 3+)

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
