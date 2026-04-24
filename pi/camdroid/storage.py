"""
Image storage with the v0 contract's identity scheme and on-capture variant
generation. Layout under <root>:

    <root>/
      index.sqlite
      full/<id>.jpg       # byte-identical to the camera's JPG
      preview/<id>.jpg    # ~2048px long edge, EXIF stripped
      thumb/<id>.jpg      # ~256px long edge, EXIF stripped

ID format: <YYYYMMDDTHHMMSS>_<NNN>, e.g. "20260424T123456_001".

The storage instance is safe to call from the camera capture thread and
from the API server thread(s) — SQLite operations are guarded by a lock,
and image files are write-once-read-many.
"""
from __future__ import annotations

import json
import logging
import shutil
import sqlite3
import threading
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Optional

from PIL import Image

from camdroid.camera import CapturedFile
from camdroid.exif import ShootingExif, read_shooting_exif

log = logging.getLogger(__name__)

PREVIEW_LONG_EDGE = 2048
THUMB_LONG_EDGE = 256
PREVIEW_QUALITY = 85
THUMB_QUALITY = 80


@dataclass
class ImageRecord:
    id: str
    captured_at: float  # unix epoch seconds, UTC
    captured_at_iso: str
    camera_filename: str
    width: int
    height: int
    full_size: int
    preview_size: int
    thumb_size: int
    exif: ShootingExif

    def to_dict(self) -> dict:
        d = asdict(self)
        d["exif"] = asdict(self.exif)
        return d


class ImageStorage:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.full_dir = root / "full"
        self.preview_dir = root / "preview"
        self.thumb_dir = root / "thumb"
        for d in (self.full_dir, self.preview_dir, self.thumb_dir):
            d.mkdir(parents=True, exist_ok=True)

        self._db_path = root / "index.sqlite"
        self._db_lock = threading.Lock()
        self._init_db()

        self._seq_lock = threading.Lock()
        self._last_second_key = ""
        self._last_seq = 0

    # --- public API ---

    def add(self, captured: CapturedFile) -> ImageRecord:
        """Take a downloaded JPG file and process it into the storage layout.
        Generates variants, extracts EXIF, persists to the index, and returns
        the record. The source file is moved (not copied) into the layout."""
        ts = captured.captured_at
        record_id = self._next_id(ts)
        captured_iso = datetime.fromtimestamp(ts, tz=timezone.utc).isoformat()

        full_path = self.full_dir / f"{record_id}.jpg"
        preview_path = self.preview_dir / f"{record_id}.jpg"
        thumb_path = self.thumb_dir / f"{record_id}.jpg"

        shutil.move(str(captured.path), str(full_path))

        # Decode once, resample twice.
        with Image.open(full_path) as img:
            img.load()
            preview = img.copy()
            preview.thumbnail((PREVIEW_LONG_EDGE, PREVIEW_LONG_EDGE), Image.Resampling.LANCZOS)
            preview.save(preview_path, "JPEG", quality=PREVIEW_QUALITY, optimize=True)
            thumb = img.copy()
            thumb.thumbnail((THUMB_LONG_EDGE, THUMB_LONG_EDGE), Image.Resampling.LANCZOS)
            thumb.save(thumb_path, "JPEG", quality=THUMB_QUALITY, optimize=True)

        exif = read_shooting_exif(full_path)
        rec = ImageRecord(
            id=record_id,
            captured_at=ts,
            captured_at_iso=captured_iso,
            camera_filename=captured.camera_filename,
            width=exif.width or 0,
            height=exif.height or 0,
            full_size=full_path.stat().st_size,
            preview_size=preview_path.stat().st_size,
            thumb_size=thumb_path.stat().st_size,
            exif=exif,
        )
        self._insert(rec)
        log.info(
            "stored %s (full=%dKB preview=%dKB thumb=%dKB)",
            record_id, rec.full_size // 1024, rec.preview_size // 1024, rec.thumb_size // 1024,
        )
        return rec

    def get(self, image_id: str) -> Optional[ImageRecord]:
        with self._db_lock, sqlite3.connect(self._db_path) as conn:
            row = conn.execute(
                "SELECT id, captured_at, captured_at_iso, camera_filename, width, height, "
                "full_size, preview_size, thumb_size, metadata_json "
                "FROM images WHERE id = ?",
                (image_id,),
            ).fetchone()
        if row is None:
            return None
        return _row_to_record(row)

    def list_recent(self, limit: int = 50, before: Optional[str] = None) -> List[ImageRecord]:
        sql = (
            "SELECT id, captured_at, captured_at_iso, camera_filename, width, height, "
            "full_size, preview_size, thumb_size, metadata_json "
            "FROM images "
        )
        params: tuple = ()
        if before is not None:
            sql += "WHERE id < ? "
            params = (before,)
        sql += "ORDER BY id DESC LIMIT ?"
        params = params + (limit,)
        with self._db_lock, sqlite3.connect(self._db_path) as conn:
            rows = conn.execute(sql, params).fetchall()
        return [_row_to_record(r) for r in rows]

    def count(self) -> int:
        with self._db_lock, sqlite3.connect(self._db_path) as conn:
            return conn.execute("SELECT COUNT(*) FROM images").fetchone()[0]

    def path_full(self, image_id: str) -> Path:
        return self.full_dir / f"{image_id}.jpg"

    def path_preview(self, image_id: str) -> Path:
        return self.preview_dir / f"{image_id}.jpg"

    def path_thumb(self, image_id: str) -> Path:
        return self.thumb_dir / f"{image_id}.jpg"

    # --- internals ---

    def _init_db(self) -> None:
        with self._db_lock, sqlite3.connect(self._db_path) as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS images (
                    id TEXT PRIMARY KEY,
                    captured_at REAL NOT NULL,
                    captured_at_iso TEXT NOT NULL,
                    camera_filename TEXT NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    full_size INTEGER NOT NULL,
                    preview_size INTEGER NOT NULL,
                    thumb_size INTEGER NOT NULL,
                    metadata_json TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_captured_at ON images(captured_at DESC);
                """
            )

    def _insert(self, rec: ImageRecord) -> None:
        meta_json = json.dumps(rec.to_dict())
        with self._db_lock, sqlite3.connect(self._db_path) as conn:
            conn.execute(
                "INSERT INTO images "
                "(id, captured_at, captured_at_iso, camera_filename, width, height, "
                "full_size, preview_size, thumb_size, metadata_json) "
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                (
                    rec.id,
                    rec.captured_at,
                    rec.captured_at_iso,
                    rec.camera_filename,
                    rec.width,
                    rec.height,
                    rec.full_size,
                    rec.preview_size,
                    rec.thumb_size,
                    meta_json,
                ),
            )

    def _next_id(self, ts: float) -> str:
        # Stable, sortable ID. Sequence resets each second so two captures
        # in the same second get _001, _002.
        second_key = time.strftime("%Y%m%dT%H%M%S", time.gmtime(ts))
        with self._seq_lock:
            if second_key != self._last_second_key:
                self._last_second_key = second_key
                self._last_seq = 0
            self._last_seq += 1
            seq = self._last_seq
        return f"{second_key}_{seq:03d}"


def _row_to_record(row) -> ImageRecord:
    (id_, captured_at, captured_at_iso, camera_filename, width, height,
     full_size, preview_size, thumb_size, metadata_json) = row
    meta = json.loads(metadata_json)
    return ImageRecord(
        id=id_,
        captured_at=captured_at,
        captured_at_iso=captured_at_iso,
        camera_filename=camera_filename,
        width=width,
        height=height,
        full_size=full_size,
        preview_size=preview_size,
        thumb_size=thumb_size,
        exif=ShootingExif(**meta["exif"]),
    )
