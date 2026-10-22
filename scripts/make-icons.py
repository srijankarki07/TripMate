#!/usr/bin/env python3
"""Regenerate TripMate's icon and social-preview assets.

    python3 scripts/make-icons.py

Writes into tripmate-web/src/main/webapp/img/. It is a plain static directory
rather than Faces' /resources, deliberately: the resource handler only serves
files under an .xhtml suffix with a rewritten MIME type, and a favicon or a web
manifest served as text/html is one a browser will not accept.

    favicon.svg              the mark, as a vector, for any size
    favicon.ico             16, 32 and 48 px, for browsers that ask for it
    apple-touch-icon.png    180 px, for iOS home screens
    icon-192.png            192 px, referenced by site.webmanifest
    icon-512.png            512 px, referenced by site.webmanifest
    og-image.png            1200x630, for link previews

The mark is a white map pin on a Rausch tile with the same corner radius the
design system uses for cards. It is drawn at eight times the target size and
downsampled, which is what gives the edges their antialiasing; PIL has no
vector renderer, so this is the cheapest way to get a clean edge at 16 px.

Requires Pillow. Nothing else in the project depends on Python.
"""

from __future__ import annotations

import pathlib
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required: python3 -m pip install --user Pillow")

# The single brand accent, from DESIGN.md. Nothing else may be this colour.
RAUSCH = (255, 56, 92, 255)
WHITE = (255, 255, 255, 255)

# The other two brand tones, used only in the social preview gradient, which
# mirrors --tm-gradient-brand. Neither is an accent: they appear nowhere else.
PLUS = (146, 23, 77, 255)
LUXE = (70, 4, 121, 255)

SUPERSAMPLE = 8
OUT = pathlib.Path(__file__).resolve().parent.parent / "tripmate-web/src/main/webapp/img"


def rounded_tile(size: int, radius_ratio: float = 0.22) -> Image.Image:
    """A Rausch rounded square, transparent outside the corners."""
    big = size * SUPERSAMPLE
    tile = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(tile).rounded_rectangle(
        (0, 0, big - 1, big - 1),
        radius=int(big * radius_ratio),
        fill=RAUSCH,
    )
    return tile.resize((size, size), Image.LANCZOS)


def draw_pin(tile: Image.Image, scale: float = 0.54) -> None:
    """Draw a white map pin, centred, onto an already-sized tile.

    The pin is a circle with a tail that converges to a point below it, and a
    punched-out centre in the tile colour so it reads as a pin rather than a
    blob at small sizes. Drawn at SUPERSAMPLE and composited down, because a
    pin drawn directly at 16 px is a smudge.
    """
    size = tile.width
    big = size * SUPERSAMPLE
    layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    body = big * scale
    head_d = body * 0.78
    cx, cy = big / 2, big / 2

    # The whole shape spans head_d/2 above the head's centre and 0.60 * body
    # below it, so its height is very nearly `body`. Offsetting the head's
    # centre by that much less half the height centres the pin exactly rather
    # than by eye.
    head_cy = cy - body * 0.105
    draw.ellipse(
        (cx - head_d / 2, head_cy - head_d / 2, cx + head_d / 2, head_cy + head_d / 2),
        fill=WHITE,
    )

    # Tail: a triangle from the head's lower flanks to a point below.
    tip_y = head_cy + body * 0.60
    half = head_d * 0.38
    draw.polygon(
        [(cx - half, head_cy + head_d * 0.18),
         (cx + half, head_cy + head_d * 0.18),
         (cx, tip_y)],
        fill=WHITE,
    )

    # Punch the centre back out, in the tile colour, so it reads as a pin.
    hole_d = head_d * 0.38
    draw.ellipse(
        (cx - hole_d / 2, head_cy - hole_d / 2, cx + hole_d / 2, head_cy + hole_d / 2),
        fill=RAUSCH,
    )

    tile.alpha_composite(layer.resize((size, size), Image.LANCZOS))


def icon(size: int) -> Image.Image:
    tile = rounded_tile(size)
    draw_pin(tile)
    return tile


def favicon_svg() -> str:
    """The same mark as vector, so it stays sharp at any size."""
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" role="img"
     aria-label="TripMate">
  <rect width="64" height="64" rx="14" fill="#ff385c"/>
  <path fill="#ffffff" d="M32 14c-6.6 0-12 5.4-12 12 0 8.4 12 24 12 24s12-15.6 12-24c0-6.6-5.4-12-12-12zm0 16.5a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z"/>
</svg>
"""


def og_image() -> Image.Image:
    """A 1200x630 link preview: brand gradient, wordmark, one-line pitch."""
    w, h = 1200, 630
    img = Image.new("RGB", (w, h), (255, 255, 255))

    # Horizontal wash through the same three stops as --tm-gradient-brand:
    # Rausch at the left, Plus magenta just past the middle, Luxe at the right.
    stops = [(0.0, RAUSCH), (0.55, PLUS), (1.0, LUXE)]
    for x in range(w):
        t = x / (w - 1)
        for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
            if t <= t1 or t1 == 1.0:
                span = t1 - t0 or 1.0
                f = min(max((t - t0) / span, 0.0), 1.0)
                colour = tuple(int(c0[i] + (c1[i] - c0[i]) * f) for i in range(3))
                break
        img.paste(colour, (x, 0, x + 1, h))

    draw = ImageDraw.Draw(img)

    def font(path: str, size: int):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            return ImageFont.load_default()

    bold = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    regular = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

    draw.text((80, 210), "TripMate", font=font(bold, 96), fill=WHITE)
    draw.text(
        (80, 340),
        "Plan the trip. Split the costs. Settle up.",
        font=font(regular, 40),
        fill=(255, 255, 255, 235),
    )
    draw.text(
        (80, 470),
        "Group trip planning and expense tracking for small, trusted groups.",
        font=font(regular, 26),
        fill=(255, 255, 255, 200),
    )

    # The pin mark, so the preview carries the favicon's identity.
    mark = icon(140)
    img.paste(mark, (w - 220, 80), mark)
    return img


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    (OUT / "favicon.svg").write_text(favicon_svg(), encoding="utf-8")

    # .ico holds several sizes; Windows and older browsers pick from it.
    icon(256).save(
        OUT / "favicon.ico",
        sizes=[(16, 16), (32, 32), (48, 48)],
        format="ICO",
    )

    icon(180).save(OUT / "apple-touch-icon.png")
    icon(192).save(OUT / "icon-192.png")
    icon(512).save(OUT / "icon-512.png")
    og_image().save(OUT / "og-image.png")

    for path in sorted(OUT.iterdir()):
        print(f"{path.name:24} {path.stat().st_size:>8} bytes")


if __name__ == "__main__":
    main()
