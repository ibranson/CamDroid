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

## Stage 1 smoke test

With the camera plugged in and powered on:

```bash
source ~/CamDroid/pi/.venv/bin/activate
cd ~/CamDroid/pi
python -m scripts.smoke_test ~/camdroid-images
```

Press the shutter on the camera. Each press should print a line in the
terminal and write the JPG to `~/camdroid-images/`. Ctrl-C to exit.

This is the same behavior as `gphoto2 --capture-tethered`, but in our
own Python code, with our state machine and our event hooks — the
foundation that the API and admin daemon will build on.
