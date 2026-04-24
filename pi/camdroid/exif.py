"""
EXIF helper. Extracts the four shooting essentials (ISO, shutter, aperture,
focal length) from a JPG. Anything richer is kept in metadata_json and
returned by GET /api/v0/images/{id}.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from PIL import Image

log = logging.getLogger(__name__)

_TAG_EXIF_IFD_POINTER = 0x8769  # 34665
_TAG_EXPOSURE_TIME = 33434
_TAG_FNUMBER = 33437
_TAG_ISO = 34855
_TAG_FOCAL_LENGTH = 37386


@dataclass
class ShootingExif:
    iso: Optional[int] = None
    shutter: Optional[str] = None  # human-readable, e.g. "1/250" or "2s"
    aperture: Optional[float] = None  # f-number, e.g. 4.0
    focal_length: Optional[float] = None  # mm
    width: Optional[int] = None
    height: Optional[int] = None


def read_shooting_exif(jpg_path: Path) -> ShootingExif:
    out = ShootingExif()
    try:
        with Image.open(jpg_path) as img:
            out.width, out.height = img.width, img.height
            ex = img.getexif()
            if ex is None:
                return out

            # Shooting params live in the EXIF sub-IFD, not the main IFD.
            try:
                exif_ifd = ex.get_ifd(_TAG_EXIF_IFD_POINTER)
            except Exception:
                exif_ifd = {}

            iso = exif_ifd.get(_TAG_ISO)
            if iso is not None:
                try:
                    out.iso = int(iso) if not isinstance(iso, tuple) else int(iso[0])
                except (TypeError, ValueError):
                    pass

            shutter = exif_ifd.get(_TAG_EXPOSURE_TIME)
            if shutter is not None:
                out.shutter = _format_shutter(shutter)

            fnum = exif_ifd.get(_TAG_FNUMBER)
            if fnum is not None:
                try:
                    out.aperture = float(fnum)
                except (TypeError, ValueError):
                    pass

            fl = exif_ifd.get(_TAG_FOCAL_LENGTH)
            if fl is not None:
                try:
                    out.focal_length = float(fl)
                except (TypeError, ValueError):
                    pass
    except Exception:
        log.exception("EXIF read failed for %s", jpg_path)
    return out


def _format_shutter(value) -> Optional[str]:
    try:
        f = float(value)
    except (TypeError, ValueError):
        return None
    if f <= 0:
        return None
    if f >= 1:
        return f"{f:g}s"
    denom = round(1 / f)
    return f"1/{denom}"
