"""
Stage 1 smoke test.

Connects to the camera, starts the capture loop, prints a line for every
JPG that arrives on the Pi. Press Ctrl-C to stop.

Usage:
    python -m scripts.smoke_test ~/camdroid-images
"""
from __future__ import annotations

import logging
import signal
import sys
import time
from pathlib import Path

from camdroid.camera import Camera, CameraState, CapturedFile


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )

    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <download-dir>", file=sys.stderr)
        return 2
    download_dir = Path(sys.argv[1]).expanduser().resolve()

    counter = {"n": 0}

    def on_capture(f: CapturedFile) -> None:
        counter["n"] += 1
        size_kb = f.path.stat().st_size // 1024
        print(f"[{counter['n']:04d}] {f.camera_filename}  {size_kb} KB  -> {f.path}")

    def on_state(old: CameraState, new: CameraState, reason: str) -> None:
        print(f"state: {old.value} -> {new.value}  ({reason})")

    cam = Camera(download_dir=download_dir, on_capture=on_capture, on_state_change=on_state)

    stopped = False

    def handle_sigint(signum, frame):
        nonlocal stopped
        stopped = True

    signal.signal(signal.SIGINT, handle_sigint)
    signal.signal(signal.SIGTERM, handle_sigint)

    try:
        cam.connect()
    except Exception as e:
        print(f"connect failed: {e}", file=sys.stderr)
        return 1

    cam.start_capture_loop()
    print("\nCapture loop running. Press the shutter on the camera. Ctrl-C to exit.\n")

    try:
        while not stopped:
            time.sleep(0.2)
    finally:
        cam.stop_capture_loop()
        cam.disconnect()
        print(f"\nTotal JPGs received: {counter['n']}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
