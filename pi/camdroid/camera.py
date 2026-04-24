"""
gphoto2 camera wrapper.

Wraps the python-gphoto2 binding into a small interface the rest of the daemon
uses: connect, set up RAW+JPG -> card-and-Pi semantics, run a capture-event
loop in a background thread, and call back when a JPG arrives on the Pi.

The state machine values match docs/api-contract-v0.md.
"""
from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Callable, Optional

import gphoto2 as gp

log = logging.getLogger(__name__)


class CameraState(str, Enum):
    UNKNOWN = "unknown"
    NO_USB = "no_usb"
    USB_PRESENT = "usb_present"
    PTP_OPENING = "ptp_opening"
    PTP_READY = "ptp_ready"
    PTP_DEGRADED = "ptp_degraded"
    PTP_FAILED = "ptp_failed"


@dataclass
class CameraInfo:
    manufacturer: str
    model: str
    serial: str
    firmware: str


@dataclass
class CapturedFile:
    path: Path
    camera_folder: str
    camera_filename: str
    captured_at: float  # unix epoch seconds


CaptureCallback = Callable[[CapturedFile], None]
StateCallback = Callable[[CameraState, CameraState, str], None]


class Camera:
    """One USB-attached camera. Not safe to share across threads except via the
    public methods here. The capture loop runs on its own thread and calls the
    capture callback synchronously — the callback should do its work quickly
    (write to disk, enqueue to the event bus, etc.) and return.
    """

    def __init__(
        self,
        download_dir: Path,
        on_capture: CaptureCallback,
        on_state_change: Optional[StateCallback] = None,
    ) -> None:
        self.download_dir = download_dir
        self.download_dir.mkdir(parents=True, exist_ok=True)
        self._on_capture = on_capture
        self._on_state_change = on_state_change

        self._camera: Optional[gp.Camera] = None
        self._info: Optional[CameraInfo] = None
        self._state = CameraState.UNKNOWN
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()

    # --- public API ---

    @property
    def state(self) -> CameraState:
        return self._state

    @property
    def info(self) -> Optional[CameraInfo]:
        return self._info

    def connect(self) -> None:
        """Detect camera, open PTP session, set capturetarget=1."""
        self._set_state(CameraState.PTP_OPENING, "connect() called")
        try:
            cam = gp.Camera()
            cam.init()  # raises gp.GPhoto2Error if no camera or busy
            self._camera = cam
            self._info = self._read_device_info(cam)
            self._set_capture_target_to_card(cam)
            self._set_state(CameraState.PTP_READY, "PTP session open")
            log.info("Connected: %s %s (firmware %s)", self._info.manufacturer, self._info.model, self._info.firmware)
        except gp.GPhoto2Error as e:
            self._set_state(CameraState.PTP_FAILED, f"connect failed: {e}")
            raise

    def disconnect(self) -> None:
        if self._camera is not None:
            try:
                self._camera.exit()
            except gp.GPhoto2Error as e:
                log.warning("camera.exit() raised: %s", e)
            self._camera = None
        self._set_state(CameraState.NO_USB, "disconnect() called")

    def start_capture_loop(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        if self._camera is None:
            raise RuntimeError("connect() before start_capture_loop()")
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="camdroid-capture", daemon=True)
        self._thread.start()

    def stop_capture_loop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=timeout)
            self._thread = None

    # --- internals ---

    def _set_state(self, new: CameraState, reason: str) -> None:
        old = self._state
        if old == new:
            return
        self._state = new
        log.info("state: %s -> %s (%s)", old.value, new.value, reason)
        if self._on_state_change is not None:
            try:
                self._on_state_change(old, new, reason)
            except Exception:
                log.exception("on_state_change callback raised")

    def _read_device_info(self, cam: gp.Camera) -> CameraInfo:
        summary = str(cam.get_summary())
        return CameraInfo(
            manufacturer=_extract(summary, "Manufacturer:"),
            model=_extract(summary, "Model:"),
            serial=_extract(summary, "Serial Number:"),
            firmware=_extract(summary, "Device version:") or _extract(summary, "DeviceVersion:"),
        )

    def _set_capture_target_to_card(self, cam: gp.Camera) -> None:
        """capturetarget=1 (Memory card). Required so RAW+JPG are written to the
        card and the Pi pulls download copies. Default of 0 (Internal RAM) would
        defeat the architecture."""
        try:
            cfg = cam.get_config()
            target = cfg.get_child_by_name("capturetarget")
            target.set_value("Memory card")
            cam.set_config(cfg)
            log.info("capturetarget set to Memory card")
        except gp.GPhoto2Error as e:
            log.warning("Could not set capturetarget=Memory card: %s", e)

    def _loop(self) -> None:
        cam = self._camera
        assert cam is not None
        log.info("capture loop running")
        while not self._stop.is_set():
            try:
                event_type, event_data = cam.wait_for_event(1000)  # ms
            except gp.GPhoto2Error as e:
                log.error("wait_for_event raised: %s", e)
                self._set_state(CameraState.PTP_DEGRADED, f"wait_for_event: {e}")
                time.sleep(0.5)
                continue

            if event_type == gp.GP_EVENT_TIMEOUT:
                continue

            if event_type == gp.GP_EVENT_FILE_ADDED:
                folder, filename = event_data.folder, event_data.name
                if not filename.lower().endswith((".jpg", ".jpeg")):
                    log.debug("ignoring non-JPG file event: %s/%s", folder, filename)
                    continue
                self._download_jpg(folder, filename)

            elif event_type == gp.GP_EVENT_CAPTURE_COMPLETE:
                log.debug("capture complete event")
            elif event_type == gp.GP_EVENT_FOLDER_ADDED:
                log.debug("folder added: %s", event_data)
            else:
                log.debug("event type %s: %s", event_type, event_data)
        log.info("capture loop stopped")

    def _download_jpg(self, folder: str, filename: str) -> None:
        cam = self._camera
        assert cam is not None
        local_path = self.download_dir / filename
        try:
            cam_file = cam.file_get(folder, filename, gp.GP_FILE_TYPE_NORMAL)
            cam_file.save(str(local_path))
        except gp.GPhoto2Error as e:
            log.error("Failed to download %s/%s: %s", folder, filename, e)
            return

        log.info("downloaded %s (%d bytes)", local_path.name, local_path.stat().st_size)
        try:
            self._on_capture(
                CapturedFile(
                    path=local_path,
                    camera_folder=folder,
                    camera_filename=filename,
                    captured_at=time.time(),
                )
            )
        except Exception:
            log.exception("on_capture callback raised")


def _extract(blob: str, key: str) -> str:
    for line in blob.splitlines():
        if line.startswith(key):
            return line[len(key):].strip()
    return ""
