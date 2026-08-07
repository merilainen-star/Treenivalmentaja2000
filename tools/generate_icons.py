#!/usr/bin/env python3
"""Regenerate the app's launcher and splash assets from the master artwork.

The master is a 2048x2048 PNG of the finished icon: the logo mark on the icon's own
dark background, inside a rounded square, on a white margin. This script lifts the mark
off that background with an alpha key and writes every raster the app needs, at every
density bucket.

    python tools/generate_icons.py path/to/Icon.png

Writes, relative to the repository root:

    app/src/main/res/mipmap-<density>/ic_launcher_foreground.webp   adaptive icon foreground
    app/src/main/res/mipmap-<density>/ic_launcher_monochrome.webp   themed-icon silhouette
    app/src/main/res/drawable-<density>/splash_logo.webp            splash mark

Nothing lands in -nodpi: that disables density stripping and ships every size to every
device (see AGENTS.md). Everything is written as lossless WebP.

Why this script exists at all: the original assets were destroyed by being written
through a text encoding, which replaced every byte >= 0x80 with U+FFFD, and no intact
copy survived in any branch. Keeping the derivation in the repo means the next loss is
a one-command fix rather than an archaeology exercise.
"""

import sys
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

# The flat background the mark sits on inside the rounded square.
BACKGROUND = np.array([35.0, 35.0, 35.0])
# Distance from BACKGROUND at which a pixel counts as fully opaque mark.
KEY_THRESHOLD = 70.0
# Alpha below this is treated as noise from the key rather than real edge softness.
ALPHA_FLOOR = 12

# An adaptive icon is a 108dp canvas whose art must stay inside the centre 72dp; the rest
# can be cropped to any mask shape. 66.67% keeps the mark clear of every launcher shape.
SAFE_ZONE = 72 / 108

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
FOREGROUND_DP = 108
SPLASH_DP = 200  # splash_icon_layer.xml draws the mark at 200dp; the composable uses 120dp.


def extract_mark(master: Path) -> Image.Image:
    """Return the logo mark alone, on transparency, tightly cropped."""
    rgb = np.asarray(Image.open(master).convert("RGB")).astype(np.float64)
    height, width, _ = rgb.shape

    # The white margin around the rounded square is found by flooding in from the corners,
    # so that white *inside* the mark is never mistaken for background.
    near_white = rgb.min(axis=2) > 235
    outside = np.zeros((height, width), bool)
    queue = deque()
    for corner in ((0, 0), (0, width - 1), (height - 1, 0), (height - 1, width - 1)):
        if near_white[corner]:
            outside[corner] = True
            queue.append(corner)
    while queue:
        y, x = queue.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < height and 0 <= nx < width and near_white[ny, nx] and not outside[ny, nx]:
                outside[ny, nx] = True
                queue.append((ny, nx))

    # Grow the margin so the anti-aliased rim of the rounded square goes with it; without
    # this the square's outline stays behind as a ghost on any other background.
    grown = np.asarray(
        Image.fromarray((outside * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(15))
    ) > 0

    alpha = np.clip(np.abs(rgb - BACKGROUND).max(axis=2) / KEY_THRESHOLD, 0, 1)
    alpha[grown] = 0.0
    # The generator stamped a small sparkle watermark into the bottom-right corner.
    alpha[int(0.86 * height):, int(0.86 * width):] = 0.0
    alpha[alpha * 255 < ALPHA_FLOOR] = 0.0

    # Unpremultiply so edge pixels keep their own colour instead of a blend with the
    # background they were lifted off.
    safe = np.maximum(alpha, 1e-6)[..., None]
    colour = np.clip(BACKGROUND + (rgb - BACKGROUND) / safe, 0, 255)

    mark = Image.fromarray(np.dstack([colour, alpha * 255]).astype(np.uint8))
    return mark.crop(mark.getbbox())


def fit(mark: Image.Image, canvas_px: int, content_fraction: float) -> Image.Image:
    """Centre the mark on a square transparent canvas, scaled to the given fraction."""
    target = int(round(canvas_px * content_fraction))
    scaled = mark.copy()
    scaled.thumbnail((target, target), Image.LANCZOS)
    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    canvas.paste(
        scaled,
        ((canvas_px - scaled.size[0]) // 2, (canvas_px - scaled.size[1]) // 2),
        scaled,
    )
    return canvas


def monochrome(image: Image.Image) -> Image.Image:
    """Silhouette for themed icons: shape only, in the single colour Android will tint."""
    alpha = image.getchannel("A")
    flat = Image.new("RGBA", image.size, (255, 255, 255, 0))
    flat.putalpha(alpha)
    return flat


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    master = Path(sys.argv[1])
    if not master.is_file():
        print(f"master artwork not found: {master}")
        return 1

    res = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
    mark = extract_mark(master)
    print(f"mark extracted: {mark.size[0]}x{mark.size[1]}")

    written = 0
    for density, scale in DENSITIES.items():
        foreground_px = int(round(FOREGROUND_DP * scale))
        splash_px = int(round(SPLASH_DP * scale))

        foreground = fit(mark, foreground_px, SAFE_ZONE)
        targets = [
            (res / f"mipmap-{density}" / "ic_launcher_foreground.webp", foreground),
            (res / f"mipmap-{density}" / "ic_launcher_monochrome.webp", monochrome(foreground)),
            (res / f"drawable-{density}" / "splash_logo.webp", fit(mark, splash_px, 1.0)),
        ]
        for path, image in targets:
            path.parent.mkdir(parents=True, exist_ok=True)
            image.save(path, "WEBP", lossless=True, quality=100, method=6)
            print(f"  {path.relative_to(res.parent.parent.parent.parent)}  {path.stat().st_size:,} bytes")
            written += 1

    print(f"{written} files written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
