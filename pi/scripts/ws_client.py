"""
Minimal WebSocket test client for the v0 events stream.

Connects, prints every server event as pretty JSON, and sends a ping every
10 seconds so we can see the pong reply in action. Ctrl-C to exit.

Uses the `websockets` library, which is already installed as a transitive
dependency of uvicorn[standard].

Usage:
    python -m scripts.ws_client                                # defaults to ws://localhost:8080/...
    python -m scripts.ws_client ws://camdroid.local:8080/api/v0/events
"""
from __future__ import annotations

import asyncio
import json
import sys
import time

import websockets

DEFAULT_URL = "ws://localhost:8080/api/v0/events"


async def reader(ws) -> None:
    async for raw in ws:
        try:
            event = json.loads(raw)
        except json.JSONDecodeError:
            print(f"<non-json> {raw!r}")
            continue
        print(json.dumps(event, indent=2, sort_keys=True))
        print("-" * 60)


async def pinger(ws) -> None:
    while True:
        await asyncio.sleep(10)
        await ws.send(json.dumps({"type": "ping", "ts": time.time()}))


async def main() -> int:
    url = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_URL
    print(f"connecting to {url}")
    async with websockets.connect(url) as ws:
        print("connected — events will stream below. Ctrl-C to exit.\n")
        read_task = asyncio.create_task(reader(ws))
        ping_task = asyncio.create_task(pinger(ws))
        done, pending = await asyncio.wait(
            {read_task, ping_task}, return_when=asyncio.FIRST_COMPLETED
        )
        for t in pending:
            t.cancel()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(0)
