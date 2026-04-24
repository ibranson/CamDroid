"""
Stage 2 smoke test.

Connects to the camera, then for every JPG that arrives:
  - moves it into the storage layout under <storage-root>
  - generates thumb (256px) and preview (2048px) variants
  - extracts shooting EXIF
  - persists the record to the SQLite index

Press Ctrl-C to stop. Final summary prints index stats.

Usage:
    python -m scripts.smoke_test_2 ~/camdroid-storage
"""
from __future__ import annotations

import json
import logging
import signal
import sys
import time
from dataclasses import asdict
from pathlib import Path
from tempfile import mkdtemp

from camdroid.camera import Camera, CameraState, CapturedFile
from camdroid.storage import ImageStorage


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )

    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <storage-root>", file=sys.stderr)
        return 2
    storage_root = Path(sys.argv[1]).expanduser().resolve()
    storage = ImageStorage(storage_root)

    # Camera downloads land in a temp staging dir; storage.add() moves them out.
    staging = Path(mkdtemp(prefix="camdroid-staging-"))

    counter = {"n": 0}

    def on_capture(f: CapturedFile) -> None:
        counter["n"] += 1
        try:
            rec = storage.add(f)
        except Exception:
            logging.exception("storage.add() failed for %s", f.path)
            return
        ex = rec.exif
        bits = []
        if ex.iso is not None:
            bits.append(f"ISO{ex.iso}")
        if ex.shutter:
            bits.append(ex.shutter)
        if ex.aperture is not None:
            bits.append(f"f/{ex.aperture}")
        if ex.focal_length is not None:
            bits.append(f"{ex.focal_length:g}mm")
        meta = "  ".join(bits) if bits else "(no EXIF)"
        print(f"[{counter['n']:04d}] {rec.id}  {rec.width}x{rec.height}  {meta}")

    def on_state(old: CameraState, new: CameraState, reason: str) -> None:
        print(f"state: {old.value} -> {new.value}  ({reason})")

    cam = Camera(download_dir=staging, on_capture=on_capture, on_state_change=on_state)

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
    print(f"\nCapture loop running. Storage root: {storage_root}")
    print("Press the shutter on the camera. Ctrl-C to exit.\n")

    try:
        while not stopped:
            time.sleep(0.2)
    finally:
        cam.stop_capture_loop()
        cam.disconnect()

    # Summary
    total = storage.count()
    print(f"\n=== Stage 2 summary ===")
    print(f"Captured this session: {counter['n']}")
    print(f"Total records in index: {total}")
    recent = storage.list_recent(limit=5)
    if recent:
        print(f"Most recent {len(recent)}:")
        for rec in recent:
            print(f"  {rec.id}  {rec.width}x{rec.height}  full={rec.full_size//1024}KB  "
                  f"preview={rec.preview_size//1024}KB  thumb={rec.thumb_size//1024}KB")

    return 0


if __name__ == "__main__":
    sys.exit(main())
