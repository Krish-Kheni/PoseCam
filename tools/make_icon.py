# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow"]
# ///
"""Render PoseCam's launcher icon into app/src/main/res/.

Usage:
    uv run tools/make_icon.py [--res app/src/main/res]

A camera looking along an arc: the app records where the camera went. Drawn once on a
108x108 dp grid (adaptive-icon geometry), supersampled and downsampled, then written at
every density: legacy square and round PNGs for Android 7-7.1, adaptive foreground and
monochrome layers plus the two anydpi-v26 XMLs for Android 8 and later.

Everything that must stay legible sits inside the 66dp safe circle, the tightest shape a
launcher can mask the adaptive icon to.
"""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw

GRID = 108          # dp, the adaptive icon canvas
SAFE_DIAMETER = 66  # dp, guaranteed-visible circle
SS = 16             # supersampling factor

NAVY = (16, 27, 46, 255)
BODY = (245, 247, 250, 255)
LENS = (46, 211, 198, 255)
TRAIL = (255, 176, 32, 255)
WHITE = (255, 255, 255, 255)

# density -> (legacy px, adaptive layer px)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def draw_art(draw: ImageDraw.ImageDraw, scale: float, monochrome: bool) -> None:
    """The camera and its trajectory, in dp units scaled by [scale]."""
    def xy(*values):
        return [v * scale for v in values]

    body_fill = WHITE if monochrome else BODY
    lens_fill = WHITE if monochrome else LENS
    trail_fill = WHITE if monochrome else TRAIL
    hole_fill = (0, 0, 0, 0) if monochrome else NAVY

    # Trajectory: an arc sweeping up to the right, with a dot at the leading end.
    draw.arc(xy(16, 24, 100, 108), start=185, end=320, fill=trail_fill, width=int(6 * scale))
    draw.ellipse(xy(86, 26, 100, 40), fill=trail_fill)

    # Camera body with a viewfinder hump on top.
    draw.rounded_rectangle(xy(31, 43, 77, 77), radius=9 * scale, fill=body_fill)
    draw.rounded_rectangle(xy(45, 36, 63, 47), radius=3 * scale, fill=body_fill)

    # Lens: a thick ring with a dark centre, so it reads as a lens at 48 px.
    draw.ellipse(xy(41, 47, 67, 73), fill=lens_fill)
    draw.ellipse(xy(47, 53, 61, 67), fill=hole_fill)
    if not monochrome:
        draw.ellipse(xy(49.5, 55.5, 54, 60), fill=BODY)


def render(size_px: int, shape: str, monochrome: bool = False, art_scale: float = 1.0) -> Image.Image:
    """[shape] is 'square' (legacy), 'round' (legacy round) or 'layer' (adaptive/monochrome)."""
    canvas = size_px * SS
    scale = canvas / GRID
    image = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    if shape == "square":
        draw.rounded_rectangle([0, 0, canvas - 1, canvas - 1], radius=canvas * 0.21, fill=NAVY)
    elif shape == "round":
        draw.ellipse([0, 0, canvas - 1, canvas - 1], fill=NAVY)

    if art_scale == 1.0:
        draw_art(draw, scale, monochrome)
    else:
        # Legacy icons are not masked, so the art can sit larger on its own background.
        art = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        draw_art(ImageDraw.Draw(art), scale, monochrome)
        grown = art.resize((int(canvas * art_scale), int(canvas * art_scale)), Image.LANCZOS)
        offset = (canvas - grown.width) // 2
        image.alpha_composite(grown, (offset, offset))

    return image.resize((size_px, size_px), Image.LANCZOS)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--res", type=Path, default=Path("app/src/main/res"))
    args = parser.parse_args()

    written = []
    for density, (legacy_px, layer_px) in DENSITIES.items():
        folder = args.res / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        for name, image in (
            ("ic_launcher.png", render(legacy_px, "square", art_scale=1.18)),
            ("ic_launcher_round.png", render(legacy_px, "round", art_scale=1.08)),
            ("ic_launcher_foreground.png", render(layer_px, "layer")),
            ("ic_launcher_monochrome.png", render(layer_px, "layer", monochrome=True)),
        ):
            image.save(folder / name)
            written.append(folder / name)

    anydpi = args.res / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (anydpi / name).write_text(xml)
        written.append(anydpi / name)

    colors = args.res / "values" / "ic_launcher_background.xml"
    colors.parent.mkdir(parents=True, exist_ok=True)
    colors.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <color name="ic_launcher_background">#{NAVY[0]:02X}{NAVY[1]:02X}{NAVY[2]:02X}</color>\n'
        "</resources>\n"
    )
    written.append(colors)
    print(f"wrote {len(written)} files under {args.res}")
    # A preview sheet at the sizes a launcher actually draws.
    preview = Image.new("RGBA", (48 + 72 + 96 + 144 + 20, 160), (60, 60, 60, 255))
    x = 4
    for px in (48, 72, 96, 144):
        preview.alpha_composite(render(px, "square", art_scale=1.18), (x, 4))
        preview.alpha_composite(render(px, "round", art_scale=1.08), (x, 150 - px))
        x += px + 4
    preview.save("/tmp/posecam-icon-preview.png")
    print("preview: /tmp/posecam-icon-preview.png")


if __name__ == "__main__":
    main()
