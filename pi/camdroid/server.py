"""
CamDroid daemon entry point.

Brings up storage, camera, event bus, and the FastAPI app, in that order.
The capture path runs in the camera's own thread; the API runs on uvicorn's
asyncio loop. The two communicate exclusively through EventBus and the
read-only ImageStorage interface.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from tempfile import mkdtemp

import uvicorn
from fastapi import FastAPI

from camdroid.api import build_app, camera_state_event, image_captured_event
from camdroid.camera import Camera, CameraState, CapturedFile
from camdroid.events import EventBus
from camdroid.storage import ImageStorage

log = logging.getLogger(__name__)


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="camdroid")
    p.add_argument("storage_root", type=Path, help="Root directory for image storage")
    p.add_argument("--host", default="0.0.0.0", help="Bind address (default: 0.0.0.0)")
    p.add_argument("--port", type=int, default=8080, help="Bind port (default: 8080)")
    p.add_argument("--log-level", default="info", help="Log level (default: info)")
    return p.parse_args(argv)


RECONNECT_INTERVAL_SECONDS = 5


def _build_lifespan(*, storage: ImageStorage, staging_dir: Path, session_id: str):
    """Returns a lifespan ctx manager that owns the bus + camera and mounts
    the API routes once the asyncio loop is up."""

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        loop = asyncio.get_running_loop()
        bus = EventBus(loop)

        def on_capture(captured: CapturedFile) -> None:
            try:
                rec = storage.add(captured)
            except Exception:
                log.exception("storage.add failed for %s", captured.path)
                return
            bus.publish_threadsafe(image_captured_event(rec))

        def on_state(old: CameraState, new: CameraState, reason: str) -> None:
            bus.publish_threadsafe(camera_state_event(old, new, reason))

        camera = Camera(
            download_dir=staging_dir,
            on_capture=on_capture,
            on_state_change=on_state,
        )

        # First connect attempt. If it fails, keep serving anyway — the
        # reconnect_loop task below will keep trying every few seconds.
        try:
            camera.connect()
            camera.start_capture_loop()
        except Exception:
            log.info("camera not available at startup; will keep retrying")

        async def reconnect_loop() -> None:
            """Periodically re-attempt connection while the camera is in a
            non-working state (failed, missing, or unknown). Lets the user
            power the camera on or plug it in without cycling the daemon."""
            recoverable = {
                CameraState.PTP_FAILED,
                CameraState.NO_USB,
                CameraState.UNKNOWN,
            }
            while True:
                try:
                    await asyncio.sleep(RECONNECT_INTERVAL_SECONDS)
                    if camera.state not in recoverable:
                        continue
                    log.debug("reconnect attempt; current state=%s", camera.state.value)
                    try:
                        # disconnect() is a no-op if there's nothing to clean up;
                        # safe to call before each retry to clear stale handles.
                        camera.disconnect()
                    except Exception:
                        pass
                    try:
                        camera.connect()
                        camera.start_capture_loop()
                        log.info("camera reconnected: %s", camera.info.model if camera.info else "?")
                    except Exception as e:
                        log.debug("reconnect attempt failed: %s", e)
                except asyncio.CancelledError:
                    raise
                except Exception:
                    log.exception("unexpected error in reconnect_loop")

        reconnect_task = asyncio.create_task(reconnect_loop(), name="camdroid-reconnect")

        # Mount the routed app onto the live FastAPI shell now that bus + camera
        # exist. (build_app needs them at construction time because the route
        # closures reference them.)
        routed = build_app(storage=storage, camera=camera, bus=bus, session_id=session_id)
        for r in routed.routes:
            app.router.routes.append(r)
        app.user_middleware = routed.user_middleware
        app.middleware_stack = app.build_middleware_stack()

        log.info("API ready on the asyncio loop")
        try:
            yield
        finally:
            reconnect_task.cancel()
            try:
                await reconnect_task
            except asyncio.CancelledError:
                pass
            camera.stop_capture_loop()
            camera.disconnect()

    return lifespan


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    logging.basicConfig(
        level=args.log_level.upper(),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )

    storage_root = args.storage_root.expanduser().resolve()
    storage = ImageStorage(storage_root)
    session_id = str(uuid.uuid4())
    staging_dir = Path(mkdtemp(prefix="camdroid-staging-"))

    log.info("storage root: %s", storage_root)
    log.info("staging dir: %s", staging_dir)
    log.info("session id: %s", session_id)

    app = FastAPI(
        title="CamDroid",
        version="0.0.1",
        lifespan=_build_lifespan(
            storage=storage,
            staging_dir=staging_dir,
            session_id=session_id,
        ),
    )

    uvicorn.run(app, host=args.host, port=args.port, log_level=args.log_level)
    return 0


if __name__ == "__main__":
    sys.exit(main())
