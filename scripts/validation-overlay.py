#!/usr/bin/env python3
"""Composite session-list screenshot at 50% opacity over validation gate image."""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_VALIDATION = Path('/Users/mac/Desktop/Screenshot 2026-07-03 at 01.14.53.png')
DEFAULT_CAPTURE = ROOT / 'web/test/output/session-list-v50-600.png'
DEFAULT_OUT = ROOT / 'web/test/output/validation-overlay-50pct.png'


def composite(base_path: Path, overlay_path: Path, out_path: Path, opacity: float = 0.5) -> None:
    base = Image.open(base_path).convert('RGBA')
    overlay = Image.open(overlay_path).convert('RGBA')

    # Scale overlay to ~42% of base width — aligns list area with WhatsApp cards in gate image.
    target_w = int(base.width * 0.42)
    scale = target_w / overlay.width
    target_h = int(overlay.height * scale)
    overlay = overlay.resize((target_w, target_h), Image.Resampling.LANCZOS)

    alpha = overlay.split()[3]
    alpha = alpha.point(lambda p: int(p * opacity))
    overlay.putalpha(alpha)

    # Position over the WhatsApp card stack (tuned for gate screenshot layout).
    x = int(base.width * 0.08)
    y = int(base.height * 0.22)
    x = max(0, min(x, base.width - target_w))
    y = max(0, min(y, base.height - target_h))

    composed = base.copy()
    composed.paste(overlay, (x, y), overlay)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    composed.convert('RGB').save(out_path, quality=92)
    print(f'wrote {out_path} ({composed.width}x{composed.height}) overlay at ({x},{y}) size {target_w}x{target_h}')

    # Full-frame: center 600×600 capture at 50% over validation (easier to eyeball).
    centered_out = out_path.with_name('validation-overlay-centered-50pct.png')
    overlay_full = Image.open(overlay_path).convert('RGBA')
    alpha_f = overlay_full.split()[3].point(lambda p: int(p * opacity))
    overlay_full.putalpha(alpha_f)
    cx = (base.width - overlay_full.width) // 2
    cy = (base.height - overlay_full.height) // 2
    centered = base.copy()
    centered.paste(overlay_full, (cx, cy), overlay_full)
    centered.convert('RGB').save(centered_out, quality=92)
    print(f'wrote {centered_out} centered at ({cx},{cy})')


if __name__ == '__main__':
    validation = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_VALIDATION
    capture = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_CAPTURE
    out = Path(sys.argv[3]) if len(sys.argv) > 3 else DEFAULT_OUT
    if not validation.is_file():
        sys.exit(f'missing validation image: {validation}')
    if not capture.is_file():
        sys.exit(f'missing capture: {capture} — run web/test/capture-session-list.mjs first')
    composite(validation, capture, out)
