# CamDroid API Contract — v0

The contract between the Pi daemon (server) and the native Android review app (client).

## Design decisions (baked in)

- **Versioned base path: `/api/v0/`.** Bump to v1 only on breaking change.
- **WebSocket carries events only; HTTP carries everything else** (image bytes, snapshots, metadata).
- **No binary frames on the WebSocket.** Image payloads always go over HTTP.
- **Image identity: `<iso8601-compact>_<seq>`**, e.g. `20260424T123456_001`. Stable, sortable, survives daemon restart.
- **Pi pre-generates `thumb` and `preview` variants at capture time.** Costs ~100ms per shot, makes tablet UX smooth and lets HTTP caching just work.
- **Original camera JPG is never re-encoded.** Served as-is for `full` requests.
- **All timestamps ISO 8601 with timezone.**
- **JSON for everything except image bodies.**
- **No per-request auth in v0.** The Pi's Wi-Fi AP (with WPA2/WPA3 passphrase) is the authentication boundary. Anyone on the AP has full API access.
- **Daemon binds the API to the AP interface only** (e.g., `wlan0`) — belt-and-suspenders to prevent accidental exposure if the Pi ever ends up on another network.
- **Image responses include `Cache-Control: public, max-age=31536000, immutable`** since image content is keyed by ID and never changes. Android's HTTP cache then does the right thing for free.

## WebSocket events

**Path:** `/api/v0/events`

Server → client, JSON text frames. One JSON object per frame.

| Type | When | Payload (key fields) |
|---|---|---|
| `hello` | Sent immediately on connect | `server_time`, `session_id`, `api_version`, `camera` snapshot |
| `image_captured` | New file pair pulled from camera | `id`, `ts`, `thumb_url`, `preview_url`, `full_url`, `exif` (iso, shutter, aperture, focal_length), `favorite`, `flag` |
| `camera_state` | State machine transition | `from`, `to`, `reason` |
| `battery` | Camera battery level changed (or every ~60s) | `level_pct` |
| `card_full` | Camera reports memory card full | `card_slot`, `free_bytes` |
| `disk_full` | Pi local storage near/at capacity | `path`, `free_bytes`, `threshold_bytes` |
| `usb_disconnected` | USB camera unexpectedly went away | `reason` |
| `favorite_changed` | Star toggled on or off for an image | `id`, `favorite`, `ts` |
| `flag_changed` | Flag value changed for an image | `id`, `flag`, `ts` |
| `error` | Recoverable error during capture/transfer | `code`, `message`, `recoverable` |
| `pong` | Reply to client `ping` | `ts` |

Client → server:

| Type | Purpose | Payload |
|---|---|---|
| `ping` | Liveness probe (~5s timeout for liveness) | `ts` |
| `subscribe` (optional) | Filter event channels | `channels` (array; default = all) |

Note: `image_captured` includes shooting-essential EXIF (ISO, shutter, aperture, focal length). Full EXIF lives at `GET /api/v0/images/{id}` and is fetched only on demand. The Android app has a touch toggle to show/hide the EXIF overlay; whether or not it's displayed, the data ships in the event so the toggle is instant.

## HTTP endpoints

| Method + Path | Purpose |
|---|---|
| `GET /api/v0/status` | Snapshot for app startup or post-reconnect reconciliation. Returns: current camera state, camera info, last 50 image IDs (with thumb/preview/full URLs and minimal metadata), session info. Override count with `?limit=N`. |
| `GET /api/v0/camera` | Camera model, firmware, serial, battery, current settings (read-only in v0). |
| `GET /api/v0/images?limit=50&before=<id>` | Paginated image list, newest first. For the grid view scrolling back into history. |
| `GET /api/v0/images/{id}` | Full metadata for one image (full EXIF, file sizes, all variant URLs, capture timestamp, original filename on card). |
| `GET /api/v0/images/{id}/thumb.jpg` | ~256px long edge — grid view. |
| `GET /api/v0/images/{id}/preview.jpg` | ~2048px long edge — full-screen review and pinch-zoom. |
| `GET /api/v0/images/{id}/full.jpg` | Original camera JPG, byte-identical to what the Z7/whatever sent over PTP. |
| `PUT /api/v0/images/{id}/favorite` | Body: `{"favorite": true \| false}`. Sets star state. Returns updated image summary. Broadcasts `favorite_changed`. |
| `PUT /api/v0/images/{id}/flag` | Body: `{"flag": "none" \| "pick" \| "reject"}`. Sets flag value. Returns updated image summary. Broadcasts `flag_changed`. |

## Image summary shape

The shape returned by `/images`, `/images/{id}`, the `recent_images` array in `/status`, and embedded in the `image_captured` event:

```json
{
  "id": "20260424T123456_001",
  "ts": "2026-04-24T12:34:56.789+00:00",
  "width": 8256,
  "height": 5504,
  "thumb_url": "/api/v0/images/.../thumb.jpg",
  "preview_url": "/api/v0/images/.../preview.jpg",
  "full_url": "/api/v0/images/.../full.jpg",
  "exif": { "iso": 200, "shutter": "1/250", "aperture": 4.0, "focal_length": 70 },
  "favorite": false,
  "flag": "none"
}
```

## Camera connection state machine

Values exposed by the API:

```
no_usb → usb_present → ptp_opening → ptp_ready → ptp_degraded → ptp_failed
```

Plus `unknown` for the brief startup window before the daemon's first probe.

State transitions are emitted as `camera_state` events with `from`, `to`, and a human-readable `reason`. The current state is also part of `GET /api/v0/status` and `GET /api/v0/camera`.

## What's deliberately deferred to later versions

- **Admin endpoints** under `/api/v0/admin/...` — designed when the admin UI work starts.
- **Live view** — gphoto2 supports it, but it has its own protocol and reliability story. Later milestone.
- **Settings control** (set ISO, aperture, etc.) — read-only is enough for the review-app v0.
- **HTTPS** — not needed on a private AP.
- **Multi-client coordination** — assume one tablet for now.
- **Per-request auth** — re-evaluate only if the AP-as-boundary model ever stops being sufficient.

## Implementation notes (server side)

- The daemon must bind `0.0.0.0:80` only on the AP interface (`wlan0`), not on all interfaces.
- Image variant generation (`thumb`, `preview`) happens synchronously in the capture event handler — small enough to not block the next shot at typical shooting cadence.
- The capture path and the HTTP/WebSocket admin path must run on separate threads/tasks. A hung HTTP request must never starve PTP.
- All state-machine transitions log to `journalctl` with structured fields for post-mortem debugging.
