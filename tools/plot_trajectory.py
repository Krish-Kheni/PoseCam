# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "matplotlib"]
# ///
"""Plot and summarize a PoseCam trajectory.

Usage:
    uv run tools/plot_trajectory.py data/capture-XXXX            # opens a window
    uv run tools/plot_trajectory.py data/capture-XXXX --save     # writes trajectory.png into the session

ARCore world frame: +Y up (gravity), X/Z horizontal. The origin is where the ARCore
session started (app launch), not where recording started.
"""

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np

# posecam-1 had these columns; posecam-2 appends "image".
COLUMNS = ["frame_index", "timestamp_ns", "tx", "ty", "tz", "qx", "qy", "qz", "qw", "tracking_state"]


def load_poses(session: Path):
    with open(session / "poses.csv", newline="") as f:
        reader = csv.reader(f)
        header = next(reader)
        if header[: len(COLUMNS)] != COLUMNS:
            sys.exit(f"Unexpected poses.csv header: {header}")
        rows = list(reader)
    index = np.array([int(r[0]) for r in rows])
    ts = np.array([int(r[1]) for r in rows], dtype=np.int64)
    states = [r[9] for r in rows]
    tracked = np.array([s == "TRACKING" for s in states])
    pos = np.full((len(rows), 3), np.nan)
    for i, r in enumerate(rows):
        if tracked[i]:
            pos[i] = [float(v) for v in r[2:5]]
    return index, ts, pos, tracked, states


def summarize(session: Path, index, ts, pos, tracked, states) -> list[str]:
    lines = [f"session: {session.name}"]
    manifest_path = session / "manifest.json"
    if manifest_path.exists():
        m = json.loads(manifest_path.read_text())
        dev = m.get("device", {})
        cam = m.get("camera_config", {})
        lines.append(f"device: {dev.get('manufacturer')} {dev.get('model')}, ARCore {m.get('arcore_version')}, "
                     f"image {cam.get('cpu_image_size')} @ {cam.get('fps_range')} fps")
        if not m.get("complete"):
            lines.append("WARNING: manifest says recording did not stop cleanly")

    n = len(ts)
    if n < 2:
        return lines + [f"only {n} rows"]
    if not np.array_equal(index, np.arange(n)):
        lines.append("WARNING: frame_index is not 0..N-1")

    dt = np.diff(ts) / 1e9
    duration = (ts[-1] - ts[0]) / 1e9
    median = float(np.median(dt))
    gaps = int(np.sum(dt > 2 * median))
    lines.append(f"rows: {n}, duration {duration:.2f} s, mean rate {(n - 1) / duration:.1f} fps, "
                 f"median dt {median * 1e3:.1f} ms, max dt {dt.max() * 1e3:.1f} ms, gaps >2x median: {gaps}")
    if np.any(dt <= 0):
        lines.append(f"WARNING: {int(np.sum(dt <= 0))} non-increasing timestamps")

    untracked = {}
    for s in states:
        if s != "TRACKING":
            untracked[s] = untracked.get(s, 0) + 1
    lines.append(f"tracked: {int(tracked.sum())}/{n} ({100 * tracked.mean():.1f}%)"
                 + (f", untracked: {untracked}" if untracked else ""))

    p = pos[tracked]
    if len(p) >= 2:
        steps = np.linalg.norm(np.diff(p, axis=0), axis=1)
        lines.append(f"path length {steps.sum():.2f} m, start→end distance {np.linalg.norm(p[-1] - p[0]):.3f} m, "
                     f"largest step {steps.max() * 100:.1f} cm")
        extent = p.max(axis=0) - p.min(axis=0)
        lines.append(f"extent x {extent[0]:.2f} m, y (up) {extent[1]:.2f} m, z {extent[2]:.2f} m")
    return lines


def plot(session: Path, ts, pos, tracked, title_lines, save: bool):
    import matplotlib
    if save:
        matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    p = pos[tracked]
    t = (ts[tracked] - ts[0]) / 1e9
    fig = plt.figure(figsize=(18, 6))
    fig.suptitle("\n".join(title_lines[:2]), fontsize=9)

    ax3d = fig.add_subplot(1, 3, 1, projection="3d")
    # Plot with Y (up) on the vertical axis.
    ax3d.plot(p[:, 0], p[:, 2], p[:, 1], lw=1)
    ax3d.scatter(*p[0, [0, 2, 1]], c="green", label="start")
    ax3d.scatter(*p[-1, [0, 2, 1]], c="red", label="end")
    ax3d.set_xlabel("x [m]")
    ax3d.set_ylabel("z [m]")
    ax3d.set_zlabel("y (up) [m]")
    ax3d.set_box_aspect(np.maximum(np.ptp(p[:, [0, 2, 1]], axis=0), 1e-3))
    ax3d.legend()
    ax3d.set_title("3D")

    top = fig.add_subplot(1, 3, 2)
    sc = top.scatter(p[:, 0], p[:, 2], c=t, s=4, cmap="viridis")
    top.plot(p[0, 0], p[0, 2], "go")
    top.plot(p[-1, 0], p[-1, 2], "ro")
    top.set_aspect("equal", adjustable="datalim")
    top.invert_yaxis()  # -Z is "forward" at session start; show it pointing up.
    top.set_xlabel("x [m]")
    top.set_ylabel("z [m] (forward = up)")
    top.set_title("Top-down (color = time)")
    fig.colorbar(sc, ax=top, label="s")

    ax_t = fig.add_subplot(1, 3, 3)
    for k, name in enumerate("xyz"):
        ax_t.plot(t, p[:, k], label=name)
    ax_t.set_xlabel("time [s]")
    ax_t.set_ylabel("position [m]")
    ax_t.legend()
    ax_t.set_title("Position vs time")

    fig.subplots_adjust(left=0.02, right=0.97, wspace=0.5)
    if save:
        out = session / "trajectory.png"
        fig.savefig(out, dpi=120)
        print(f"wrote {out}")
    else:
        plt.show()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("session", type=Path, help="capture folder containing poses.csv")
    parser.add_argument("--save", action="store_true", help="write trajectory.png instead of opening a window")
    args = parser.parse_args()

    index, ts, pos, tracked, states = load_poses(args.session)
    lines = summarize(args.session, index, ts, pos, tracked, states)
    print("\n".join(lines))
    if tracked.sum() >= 2:
        plot(args.session, ts, pos, tracked, lines, args.save)


if __name__ == "__main__":
    main()
