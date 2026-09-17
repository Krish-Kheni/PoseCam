# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow"]
# ///
"""Project a world-fixed axis triad and floor grid onto recorded frames.

Usage:
    uv run tools/overlay_check.py data/capture-XXXX                   # contact sheet of 12 frames
    uv run tools/overlay_check.py data/capture-XXXX --gif 5 10        # animation from 5 s to 10 s

The triad (X red, Y green = up, Z blue) is placed --distance metres in front of the
camera at the anchor frame, with a horizontal grid through it. Set --distance to the
real distance of a surface in the middle of the anchor frame: a triad floating in the
air shows parallax against the background as the camera translates. Then:

  * If pose and image are aligned, the overlay stays glued to the same spot in the scene
    in every frame. A consistent offset or lag that grows with motion means misalignment.
  * If the overlay wobbles or shimmers against the scene during motion (use --gif), the
    optical axis is moving per frame: active OIS/EIS. Check ois_mode in frame_metadata.csv.

Not a distortion test: distortion is smallest near the image centre. Use calibrate_camera.py.
"""

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
from posecam_io import frame_path, load_json, load_poses, project  # noqa: E402

COLORS = {"x": (255, 60, 60), "y": (60, 255, 60), "z": (80, 140, 255), "grid": (255, 255, 0)}


def build_scene(anchor: np.ndarray, size: float, grid_half: float, grid_step: float):
    """Returns a list of (start, end, color, width) world-space segments."""
    segments = []
    for axis, name in zip(np.eye(3), "xyz"):
        segments.append((anchor, anchor + size * axis, COLORS[name], 4))
    ticks = np.arange(-grid_half, grid_half + 1e-9, grid_step)
    for a in ticks:
        # Horizontal (gravity-aligned) grid in the X-Z plane through the anchor.
        segments.append((anchor + [a, 0, -grid_half], anchor + [a, 0, grid_half], COLORS["grid"], 1))
        segments.append((anchor + [-grid_half, 0, a], anchor + [grid_half, 0, a], COLORS["grid"], 1))
    return segments


def render(session: Path, poses, i: int, rot, k: dict, segments, t0_ns: int) -> Image.Image:
    img = Image.open(frame_path(session, int(poses.frame_index[i]), int(poses.timestamp_ns[i]))).convert("RGB")
    draw = ImageDraw.Draw(img)
    starts = np.array([s[0] for s in segments])
    ends = np.array([s[1] for s in segments])
    uv0, ok0 = project(starts, poses.position[i], rot[i], k)
    uv1, ok1 = project(ends, poses.position[i], rot[i], k)
    for (_, _, color, width), a, b, ok in zip(segments, uv0, uv1, ok0 & ok1):
        if ok and np.all(np.abs(np.concatenate([a, b])) < 1e5):
            draw.line([tuple(a), tuple(b)], fill=color, width=width)
    label = f"#{poses.frame_index[i]}  t={(poses.timestamp_ns[i] - t0_ns) / 1e9:.2f}s"
    draw.rectangle([0, 0, 8 * len(label), 16], fill=(0, 0, 0))
    draw.text((3, 2), label, fill=(255, 255, 255))
    return img


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("session", type=Path)
    parser.add_argument("--frames", type=int, default=12, help="frames in the contact sheet")
    parser.add_argument("--anchor-frame", type=int, help="frame index to place the triad from (default: first tracked)")
    parser.add_argument("--distance", type=float, default=1.0, help="metres in front of the anchor camera")
    parser.add_argument("--size", type=float, default=0.2, help="triad axis length, metres")
    parser.add_argument("--grid", type=float, default=0.5, help="grid half-width, metres (0 to disable)")
    parser.add_argument("--gif", type=float, nargs=2, metavar=("START_S", "END_S"),
                        help="write overlay.gif over this time range instead of a contact sheet")
    args = parser.parse_args()

    k = load_json(args.session, "intrinsics.json")
    if not k.get("fx"):
        sys.exit("intrinsics.json missing (posecam-3 or later required)")
    if k.get("changed_during_recording"):
        print("WARNING: intrinsics changed during recording; using the start values")
    poses = load_poses(args.session)
    rot = poses.rotation_matrices()
    tracked = np.flatnonzero(poses.tracked)
    if len(tracked) == 0:
        sys.exit("no tracked frames")
    t0 = int(poses.timestamp_ns[0])

    a = tracked[0] if args.anchor_frame is None else args.anchor_frame
    if not poses.tracked[a]:
        sys.exit(f"anchor frame {a} is not tracked")
    anchor = poses.position[a] + rot[a] @ np.array([0.0, 0.0, -args.distance])
    grid_step = args.grid / 2 if args.grid > 0 else 1.0
    segments = build_scene(anchor, args.size, args.grid, grid_step) if args.grid > 0 else build_scene(anchor, args.size, 0, 1)[:3]
    print(f"anchor frame {a}: triad at world {np.round(anchor, 3)} ({args.distance} m ahead)")

    if args.gif:
        lo, hi = (t0 + s * 1e9 for s in args.gif)
        chosen = [i for i in tracked if lo <= poses.timestamp_ns[i] <= hi]
        if not chosen:
            sys.exit("no tracked frames in that range")
        frames = [render(args.session, poses, i, rot, k, segments, t0) for i in chosen]
        out = args.session / "overlay.gif"
        frames[0].save(out, save_all=True, append_images=frames[1:], duration=33, loop=0)
        print(f"wrote {out} ({len(frames)} frames)")
        return

    chosen = tracked[np.linspace(0, len(tracked) - 1, min(args.frames, len(tracked))).round().astype(int)]
    tiles = [render(args.session, poses, i, rot, k, segments, t0) for i in chosen]
    w, h = tiles[0].size
    cols = 4
    rows = (len(tiles) + cols - 1) // cols
    scale = 0.5
    tw, th = int(w * scale), int(h * scale)
    sheet = Image.new("RGB", (cols * tw, rows * th))
    for n, tile in enumerate(tiles):
        sheet.paste(tile.resize((tw, th)), ((n % cols) * tw, (n // cols) * th))
    out = args.session / "overlay.png"
    sheet.save(out)
    print(f"wrote {out} ({len(tiles)} frames)")


if __name__ == "__main__":
    main()
