# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow"]
# ///
"""Render a checkerboard to show fullscreen on a monitor for calibrate_camera.py.

Usage:
    uv run tools/make_checkerboard.py 1366 768 --out checkerboard.png

Draws 10x7 squares (9x6 inner corners) as large as fits with a one-square white border,
at the screen's exact pixel size so the viewer does not rescale it. Measure one square
with a ruler on the screen: that is --square for calibrate_camera.py (it only affects
the board's metric scale, not the intrinsics).
"""

import argparse

from PIL import Image, ImageDraw


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("width", type=int)
    parser.add_argument("height", type=int)
    parser.add_argument("--cols", type=int, default=10, help="squares across (inner corners = cols - 1)")
    parser.add_argument("--rows", type=int, default=7, help="squares down (inner corners = rows - 1)")
    parser.add_argument("--out", default="checkerboard.png")
    args = parser.parse_args()

    square = min(args.width // (args.cols + 2), args.height // (args.rows + 2))
    img = Image.new("L", (args.width, args.height), 255)
    draw = ImageDraw.Draw(img)
    x0 = (args.width - args.cols * square) // 2
    y0 = (args.height - args.rows * square) // 2
    for r in range(args.rows):
        for c in range(args.cols):
            if (r + c) % 2 == 0:
                draw.rectangle([x0 + c * square, y0 + r * square, x0 + (c + 1) * square - 1, y0 + (r + 1) * square - 1], fill=0)
    img.save(args.out)
    print(f"wrote {args.out}: {args.cols}x{args.rows} squares, {square} px each, "
          f"use --pattern {args.cols - 1}x{args.rows - 1}")


if __name__ == "__main__":
    main()
