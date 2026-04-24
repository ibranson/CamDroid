"""
Bridge between the synchronous capture thread and async WebSocket clients.

The capture thread calls publish_threadsafe(event) — a plain dict — from
inside on_capture / on_state_change callbacks. EventBus schedules delivery
on the asyncio event loop, fanning out to every subscribed client queue.

If a client queue is full (slow consumer), the event is dropped for that
client only. The capture path must never block on a slow tablet.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

log = logging.getLogger(__name__)

CLIENT_QUEUE_MAX = 100


class EventBus:
    def __init__(self, loop: asyncio.AbstractEventLoop) -> None:
        self._loop = loop
        self._clients: set[asyncio.Queue[dict[str, Any]]] = set()

    def subscribe(self) -> asyncio.Queue[dict[str, Any]]:
        q: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=CLIENT_QUEUE_MAX)
        self._clients.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue[dict[str, Any]]) -> None:
        self._clients.discard(q)

    def publish_threadsafe(self, event: dict[str, Any]) -> None:
        """Safe to call from any thread (specifically the capture thread)."""
        self._loop.call_soon_threadsafe(self._publish_now, event)

    def _publish_now(self, event: dict[str, Any]) -> None:
        for q in list(self._clients):
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                log.warning("client queue full — dropping event %s", event.get("type"))

    @property
    def client_count(self) -> int:
        return len(self._clients)
