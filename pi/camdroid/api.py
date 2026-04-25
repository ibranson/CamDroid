"""
FastAPI app implementing the v0 API contract (docs/api-contract-v0.md).

Public surface:
  GET  /api/v0/status
  GET  /api/v0/camera
  GET  /api/v0/images
  GET  /api/v0/images/{id}
  GET  /api/v0/images/{id}/thumb.jpg
  GET  /api/v0/images/{id}/preview.jpg
  GET  /api/v0/images/{id}/full.jpg
  WS   /api/v0/events
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import asdict
from datetime import datetime, timezone
from typing import Any, Optional

from fastapi import FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel

from camdroid.camera import Camera, CameraState
from camdroid.events import EventBus
from camdroid.storage import Flag, ImageRecord, ImageStorage

log = logging.getLogger(__name__)

API_PREFIX = "/api/v0"
IMAGE_CACHE_HEADERS = {"Cache-Control": "public, max-age=31536000, immutable"}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def rec_to_summary(rec: ImageRecord) -> dict[str, Any]:
    return {
        "id": rec.id,
        "ts": rec.captured_at_iso,
        "width": rec.width,
        "height": rec.height,
        "thumb_url": f"{API_PREFIX}/images/{rec.id}/thumb.jpg",
        "preview_url": f"{API_PREFIX}/images/{rec.id}/preview.jpg",
        "full_url": f"{API_PREFIX}/images/{rec.id}/full.jpg",
        "exif": asdict(rec.exif),
        "favorite": rec.favorite,
        "flag": rec.flag.value,
    }


def camera_snapshot(cam: Camera) -> dict[str, Any]:
    return {
        "state": cam.state.value,
        "info": asdict(cam.info) if cam.info is not None else None,
    }


def image_captured_event(rec: ImageRecord) -> dict[str, Any]:
    return {
        "type": "image_captured",
        **rec_to_summary(rec),
    }


def camera_state_event(old: CameraState, new: CameraState, reason: str) -> dict[str, Any]:
    return {
        "type": "camera_state",
        "from": old.value,
        "to": new.value,
        "reason": reason,
        "ts": now_iso(),
    }


def favorite_changed_event(rec: ImageRecord) -> dict[str, Any]:
    return {
        "type": "favorite_changed",
        "id": rec.id,
        "favorite": rec.favorite,
        "ts": now_iso(),
    }


def flag_changed_event(rec: ImageRecord) -> dict[str, Any]:
    return {
        "type": "flag_changed",
        "id": rec.id,
        "flag": rec.flag.value,
        "ts": now_iso(),
    }


class FavoriteUpdate(BaseModel):
    favorite: bool


class FlagUpdate(BaseModel):
    flag: Flag


def build_app(
    storage: ImageStorage,
    camera: Camera,
    bus: EventBus,
    session_id: str,
) -> FastAPI:
    app = FastAPI(title="CamDroid", version="0.0.1")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get(f"{API_PREFIX}/status")
    def status(limit: int = Query(50, ge=1, le=500)) -> dict[str, Any]:
        recents = storage.list_recent(limit=limit)
        return {
            "api_version": "v0",
            "session_id": session_id,
            "server_time": now_iso(),
            "camera": camera_snapshot(camera),
            "recent_images": [rec_to_summary(r) for r in recents],
        }

    @app.get(f"{API_PREFIX}/camera")
    def camera_endpoint() -> dict[str, Any]:
        return camera_snapshot(camera)

    @app.get(f"{API_PREFIX}/images")
    def images(
        limit: int = Query(50, ge=1, le=500),
        before: Optional[str] = None,
    ) -> dict[str, Any]:
        records = storage.list_recent(limit=limit, before=before)
        return {"images": [rec_to_summary(r) for r in records]}

    @app.get(f"{API_PREFIX}/images/{{image_id}}")
    def image_metadata(image_id: str) -> dict[str, Any]:
        rec = storage.get(image_id)
        if rec is None:
            raise HTTPException(status_code=404, detail="image not found")
        return rec_to_summary(rec)

    @app.get(f"{API_PREFIX}/images/{{image_id}}/thumb.jpg")
    def image_thumb(image_id: str) -> FileResponse:
        return _serve_image(storage.path_thumb(image_id))

    @app.get(f"{API_PREFIX}/images/{{image_id}}/preview.jpg")
    def image_preview(image_id: str) -> FileResponse:
        return _serve_image(storage.path_preview(image_id))

    @app.get(f"{API_PREFIX}/images/{{image_id}}/full.jpg")
    def image_full(image_id: str) -> FileResponse:
        return _serve_image(storage.path_full(image_id))

    @app.put(f"{API_PREFIX}/images/{{image_id}}/favorite")
    def set_favorite(image_id: str, body: FavoriteUpdate) -> dict[str, Any]:
        rec = storage.set_favorite(image_id, body.favorite)
        if rec is None:
            raise HTTPException(status_code=404, detail="image not found")
        bus.publish_threadsafe(favorite_changed_event(rec))
        return rec_to_summary(rec)

    @app.put(f"{API_PREFIX}/images/{{image_id}}/flag")
    def set_flag(image_id: str, body: FlagUpdate) -> dict[str, Any]:
        rec = storage.set_flag(image_id, body.flag)
        if rec is None:
            raise HTTPException(status_code=404, detail="image not found")
        bus.publish_threadsafe(flag_changed_event(rec))
        return rec_to_summary(rec)

    @app.websocket(f"{API_PREFIX}/events")
    async def events_ws(ws: WebSocket) -> None:
        await ws.accept()
        queue = bus.subscribe()
        log.info("ws client connected (now %d)", bus.client_count)
        try:
            await ws.send_json(
                {
                    "type": "hello",
                    "server_time": now_iso(),
                    "session_id": session_id,
                    "api_version": "v0",
                    "camera": camera_snapshot(camera),
                }
            )

            async def send_loop() -> None:
                while True:
                    event = await queue.get()
                    await ws.send_json(event)

            async def recv_loop() -> None:
                while True:
                    msg = await ws.receive_json()
                    mtype = msg.get("type")
                    if mtype == "ping":
                        await ws.send_json({"type": "pong", "ts": msg.get("ts")})
                    elif mtype == "subscribe":
                        # v0: ignore channel filtering, send everything
                        pass

            send_task = asyncio.create_task(send_loop())
            recv_task = asyncio.create_task(recv_loop())
            done, pending = await asyncio.wait(
                {send_task, recv_task}, return_when=asyncio.FIRST_COMPLETED
            )
            for t in pending:
                t.cancel()
        except WebSocketDisconnect:
            pass
        except Exception:
            log.exception("ws error")
        finally:
            bus.unsubscribe(queue)
            log.info("ws client disconnected (now %d)", bus.client_count)

    return app


def _serve_image(path) -> FileResponse:
    if not path.exists():
        raise HTTPException(status_code=404, detail="image not found")
    return FileResponse(path, media_type="image/jpeg", headers=IMAGE_CACHE_HEADERS)
