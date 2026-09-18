# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow"]
# ///
"""Check a gripper-mounted recording before recording at volume: are both jaws fully in
frame, and does the background confuse the jaw colour?

Usage:
    uv run tools/check_gripper_view.py data/capture-XXXX [--hue-lo 340 --hue-hi 22] [--rotate 0]

The training pipeline recovers gripper aperture by colour-segmenting the jaws and needs
both jaw blobs whole: a jaw cut by the frame edge shifts the aperture calibration. It
also picks the two largest blobs, so same-coloured background patches compete with the
jaws when a jaw leaves the frame.

Hues are degrees on a 0-360 wheel (red wraps: lo > hi means the union of [lo,360) and
[0,hi]). Defaults match the red/orange NYU-style jaws measured on the Tecno takes.
"""

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from posecam_io import frame_path, load_poses  # noqa: E402


def jaw_mask(img: Image.Image, lo: float, hi: float, sat: int, val: int) -> np.ndarray:
    hsv = np.asarray(img.convert("HSV"), dtype=np.int32)
    hue = hsv[..., 0] * 360 / 255
    in_band = ((hue >= lo) | (hue <= hi)) if lo > hi else ((hue >= lo) & (hue <= hi))
    return in_band & (hsv[..., 1] > sat) & (hsv[..., 2] > val)


def blobs(mask: np.ndarray, min_px: int) -> list[tuple[int, int, int, int, int]]:
    """Connected components (4-neighbour) as (area, x0, y0, x1, y1), largest first."""
    h, w = mask.shape
    labels = np.zeros((h, w), np.int32)
    out, n = [], 0
    ys, xs = np.nonzero(mask)
    for sy, sx in zip(ys, xs):
        if labels[sy, sx]:
            continue
        n += 1
        stack, pts = [(sy, sx)], []
        labels[sy, sx] = n
        while stack:
            y, x = stack.pop()
            pts.append((y, x))
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not labels[ny, nx]:
                    labels[ny, nx] = n
                    stack.append((ny, nx))
        if len(pts) >= min_px:
            py, px = zip(*pts)
            out.append((len(pts), min(px), min(py), max(px), max(py)))
    return sorted(out, reverse=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("session", type=Path)
    parser.add_argument("--hue-lo", type=float, default=340.0)
    parser.add_argument("--hue-hi", type=float, default=22.0)
    parser.add_argument("--sat", type=int, default=110, help="min saturation 0-255")
    parser.add_argument("--val", type=int, default=40, help="min value 0-255")
    parser.add_argument("--min-blob", type=int, default=800, help="pixels; smaller blobs are ignored")
    parser.add_argument("--rotate", type=int, default=0, choices=(0, 90, 180, 270),
                        help="same rotation the export will use; edges are reported in that orientation")
    parser.add_argument("--every", type=int, default=20, help="sample every Nth frame")
    args = parser.parse_args()

    poses = load_poses(args.session)
    rows = [i for i in range(len(poses.rows)) if poses.rows[i].get("image", "saved") == "saved"][::args.every]
    stats = {"frames": 0, "two_jaws": 0, "jaw_touches_left": 0, "jaw_touches_right": 0, "jaw_touches_top": 0,
             "jaw_touches_bottom": 0, "extra_blobs": 0}
    extra_sizes = []
    for i in rows:
        img = Image.open(frame_path(args.session, int(poses.frame_index[i]), int(poses.timestamp_ns[i]))).convert("RGB")
        if args.rotate:
            img = img.rotate(-args.rotate, expand=True)  # PIL rotates counter-clockwise
        w, h = img.size
        found = blobs(jaw_mask(img, args.hue_lo, args.hue_hi, args.sat, args.val), args.min_blob)
        stats["frames"] += 1
        if len(found) >= 2:
            stats["two_jaws"] += 1
        for area, x0, y0, x1, y1 in found[:2]:
            stats["jaw_touches_left"] += x0 <= 1
            stats["jaw_touches_right"] += x1 >= w - 2
            stats["jaw_touches_top"] += y0 <= 1
            stats["jaw_touches_bottom"] += y1 >= h - 2
        if len(found) > 2:
            stats["extra_blobs"] += 1
            extra_sizes.append(found[2][0])

    n = stats["frames"]
    if n == 0:
        sys.exit("no frames")
    jaws = 2 * n
    print(f"{args.session.name}: {n} sampled frames (every {args.every}th), rotation {args.rotate}")
    print(f"two jaw blobs found: {100 * stats['two_jaws'] / n:.0f}% of frames")
    print(f"jaw blobs touching the frame edge: left {100 * stats['jaw_touches_left'] / jaws:.0f}%, "
          f"right {100 * stats['jaw_touches_right'] / jaws:.0f}%, top {100 * stats['jaw_touches_top'] / jaws:.0f}%, "
          f"bottom {100 * stats['jaw_touches_bottom'] / jaws:.0f}% (bottom is expected: the jaws enter there)")
    print(f"extra same-colour blobs >= {args.min_blob} px: {100 * stats['extra_blobs'] / n:.0f}% of frames"
          + (f", largest {max(extra_sizes)} px" if extra_sizes else ""))
    problems = []
    if stats["two_jaws"] / n < 0.9:
        problems.append("both jaws are not reliably visible")
    for side in ("left", "right", "top"):
        if stats[f"jaw_touches_{side}"] / jaws > 0.1:
            problems.append(f"a jaw is cut off by the {side} edge: move the mount so both jaws sit inside with margin")
    if stats["extra_blobs"] / n > 0.3:
        problems.append("the background has jaw-coloured patches: use a plain surface")
    for p in problems:
        print(f"FAIL: {p}")
    print("OK" if not problems else f"{len(problems)} problem(s)")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
