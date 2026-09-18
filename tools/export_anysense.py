# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy"]
# ///
"""Export a PoseCam session as an AnySense-style recording folder.

Usage:
    uv run tools/export_anysense.py data/capture-XXXX [--out exports] [--all] [--size 720x960]

Produces what NYU's AnySense iPhone app writes (NYU-robot-learning/AnySense,
ARViewContainer.swift), minus depth/tactile:

    <out>/<yyyy-MM-dd-HH_mm_ss>/
        RGB_<yyyy-MM-dd-HH_mm_ss>.mp4     H.264, portrait (camera frame rotated), ~30 fps
        AR_Pose_<yyyy-MM-dd-HH_mm_ss>.txt one line per video frame:
                                          "<epoch_ms>" ,qx,qy,qz,qw,tx,ty,tz
        posecam_export.json               provenance (not part of AnySense's format)

Pose line N corresponds to video frame N, as in AnySense, and the consumer
(cap_tools/convert.py) aligns strictly by index. Never delete interior frames: that
turns a gap into one huge apparent motion, which the consumer treats as a jump and
truncates the take. Instead, short tracking gaps (<= --hold-max-frames) get poses
linearly interpolated between the surrounding tracked frames (rows listed in
posecam_export.json), and longer gaps end the segment. Leading and trailing untracked
frames are dropped. By default only the longest segment (split at ARCore pose jumps and
long gaps) is exported; --segment picks another.

The consumer's gripper detector assumes the jaws point UP in the image. --rotate sets
the rotation applied to the sensor image (default 90 = upright portrait for a phone held
upright); check one exported frame and adjust if the mount differs.

The pose is Camera.getPose() unchanged: ARKit and ARCore both give the camera-sensor
pose in a gravity-aligned, Y-up world with the camera looking down -Z, and AnySense's
pose is likewise the sensor transform even though its video is portrait.

Needs ffmpeg and ffprobe on PATH.
"""

import argparse
import json
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from posecam_io import frame_path, load_json, load_poses  # noqa: E402

# Same thresholds as the app's PoseJumpDetector and check_sync.py.
JUMP_SPEED_M_S = 3.0
JUMP_RATE_RAD_S = 10.0


def jump_rows(poses) -> list[int]:
    """Row indices where a jump from the previous tracked row was detected."""
    rows = []
    for i in range(1, len(poses.rows)):
        if not (poses.tracked[i] and poses.tracked[i - 1]):
            continue
        dt = (poses.timestamp_ns[i] - poses.timestamp_ns[i - 1]) / 1e9
        step = float(np.linalg.norm(poses.position[i] - poses.position[i - 1]))
        dot = abs(float(np.dot(poses.quat_xyzw[i], poses.quat_xyzw[i - 1])))
        angle = 2 * np.arccos(min(1.0, dot))
        if step / dt > JUMP_SPEED_M_S or angle / dt > JUMP_RATE_RAD_S:
            rows.append(i)
    return rows


def fill_short_gaps(poses, hold_max: int) -> tuple[np.ndarray, np.ndarray, list[int]]:
    """Returns (position, quat, filled_rows): copies where untracked runs of at most
    [hold_max] rows between two tracked rows are linearly interpolated (translation lerp,
    quaternion nlerp with sign alignment). Longer runs stay NaN."""
    pos, quat = poses.position.copy(), poses.quat_xyzw.copy()
    filled = []
    tracked = np.flatnonzero(poses.tracked)
    for a, b in zip(tracked, tracked[1:]):
        gap = b - a - 1
        if gap == 0 or gap > hold_max:
            continue
        qa, qb = quat[a], quat[b]
        if np.dot(qa, qb) < 0:
            qb = -qb
        for j in range(a + 1, b):
            w = (j - a) / (b - a)
            pos[j] = (1 - w) * pos[a] + w * pos[b]
            q = (1 - w) * qa + w * qb
            quat[j] = q / np.linalg.norm(q)
            filled.append(j)
    return pos, quat, filled


def image_rows(poses, hold_max: int) -> tuple[list[int | None], list[int]]:
    """For each row, the row whose JPEG to use: itself when saved, else the nearest earlier
    saved row if within [hold_max] frames (a dropped image is replaced by the previous one,
    33 ms stale, rather than breaking the take). None when there is nothing to reuse."""
    source: list[int | None] = []
    held = []
    last_saved = None
    for i, r in enumerate(poses.rows):
        if r.get("image", "saved") == "saved":
            last_saved = i
            source.append(i)
        elif last_saved is not None and i - last_saved <= hold_max:
            source.append(last_saved)
            held.append(i)
        else:
            source.append(None)
    return source, held


def segments(poses, has_pose: np.ndarray, image_source: list[int | None], breaks: set[int]) -> list[list[int]]:
    """Runs of consecutive rows with a pose and an image, split at [breaks]."""
    out, current = [], []
    for i in range(len(poses.rows)):
        usable = has_pose[i] and image_source[i] is not None
        if not usable or i in breaks:
            if current:
                out.append(current)
            current = []
        if usable:
            current.append(i)
    if current:
        out.append(current)
    return out


def epoch_ms(manifest: dict, timestamp_ns: np.ndarray) -> np.ndarray:
    """Frame timestamps (elapsedRealtimeNanos) -> wall-clock epoch milliseconds, via the
    Record tap, which the manifest stamps on both clocks."""
    start = datetime.strptime(manifest["start_wall_time_utc"], "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
    start_ms = start.timestamp() * 1000
    press_ns = manifest.get("record_pressed_elapsed_realtime_ns")
    if press_ns is None:  # posecam-3 and earlier: first frame ~100 ms before the tap
        press_ns = manifest["first_timestamp_ns"] + 100_000_000
    return np.rint(start_ms + (timestamp_ns - press_ns) / 1e6).astype(np.int64)


def swift_float(v: float) -> str:
    """Swift's String(Float) prints the shortest form that round-trips as float32."""
    return np.format_float_positional(np.float32(v), unique=True, trim="0")


ROTATE_FILTERS = {0: [], 90: ["transpose=1"], 180: ["transpose=1,transpose=1"], 270: ["transpose=2"]}


def encode_video(files: list[Path], out: Path, fps: float, size: tuple[int, int] | None,
                 vfr_durations: list[float] | None, rotate: int):
    listing = out.with_suffix(".txt")
    with open(listing, "w") as f:
        for n, p in enumerate(files):
            f.write(f"file '{p.resolve()}'\n")
            if vfr_durations is not None:
                f.write(f"duration {vfr_durations[n]:.6f}\n")
    # 90 = transpose=1 = clockwise: sensor landscape -> upright portrait for a back camera
    # with SENSOR_ORIENTATION 90 (verified visually on the S20 FE).
    filters = list(ROTATE_FILTERS[rotate])
    if size:
        filters.append(f"scale={size[0]}:{size[1]}:flags=lanczos")
    # JPEG sources are full-range; convert to the limited-range yuv420p every decoder expects.
    filters.append("scale=in_range=pc:out_range=tv,format=yuv420p")
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0"]
    if vfr_durations is None:
        cmd += ["-r", str(fps)]
    cmd += ["-i", str(listing), "-vf", ",".join(filters),
            "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-movflags", "+faststart"]
    if vfr_durations is not None:
        # passthrough keeps every listed frame at its listed time; "vfr" mode drops frames
        # whose real interval is shorter than the demuxer's default 1/25 s image duration.
        cmd += ["-fps_mode", "passthrough"]
    else:
        cmd += ["-r", str(fps)]
    cmd.append(str(out))
    subprocess.run(cmd, check=True)
    listing.unlink()


def count_frames(video: Path) -> int:
    r = subprocess.run(["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                        "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(video)],
                       capture_output=True, text=True, check=True)
    return int(r.stdout.strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("session", type=Path)
    parser.add_argument("--out", type=Path, default=Path("exports"), help="parent folder for exports")
    parser.add_argument("--all", action="store_true", help="export every frame with a pose in one file, jumps and long gaps included")
    parser.add_argument("--hold-max-frames", type=int, default=15,
                        help="interpolate tracking gaps up to this many frames (0.5 s at 30 fps); longer gaps end the segment")
    parser.add_argument("--rotate", type=int, default=90, choices=(0, 90, 180, 270),
                        help="clockwise rotation applied to the sensor image; jaws must point up in the result")
    parser.add_argument("--segment", type=int, help="export this segment index (0-based, in time order) instead of the longest")
    parser.add_argument("--size", default=None, help="output WxH after rotation, e.g. 720x960 to match AnySense; default keeps native (480x640)")
    parser.add_argument("--fps", type=float, default=30.0, help="nominal frame rate written to the MP4")
    parser.add_argument("--vfr", action="store_true", help="keep each frame's real timing instead of a constant rate")
    parser.add_argument("--min-seconds", type=float, default=2.0, help="refuse to export segments shorter than this")
    args = parser.parse_args()

    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            sys.exit(f"{tool} not found on PATH")

    poses = load_poses(args.session)
    manifest = load_json(args.session, "manifest.json")
    if not manifest.get("complete", True):
        sys.exit("manifest says the recording did not stop cleanly")

    jumps = jump_rows(poses)
    pos, quat, filled = fill_short_gaps(poses, args.hold_max_frames)
    has_pose = ~np.isnan(pos[:, 0])
    image_source, held = image_rows(poses, args.hold_max_frames)
    segs = segments(poses, has_pose, image_source, set() if args.all else set(jumps))
    if not segs:
        sys.exit("no tracked frames with images")
    if args.all:
        chosen = [i for s in segs for i in s]
        label = "all tracked frames"
    elif args.segment is not None:
        chosen = segs[args.segment]
        label = f"segment {args.segment} of {len(segs)}"
    else:
        chosen = max(segs, key=len)
        label = f"longest of {len(segs)} segment(s)"
    seconds = (poses.timestamp_ns[chosen[-1]] - poses.timestamp_ns[chosen[0]]) / 1e9
    if seconds < args.min_seconds:
        sys.exit(f"{label} is only {seconds:.1f} s (< --min-seconds {args.min_seconds})")
    filled_in = [j for j in filled if chosen[0] <= j <= chosen[-1]]
    held_in = [j for j in held if chosen[0] <= j <= chosen[-1]]
    print(f"{args.session.name}: {len(poses.rows)} rows, {len(jumps)} jump(s), {len(filled)} gap frame(s) "
          f"interpolated; exporting {label}: rows {chosen[0]}-{chosen[-1]}, {len(chosen)} frames, {seconds:.1f} s, "
          f"{len(filled_in)} interpolated pose(s), {len(held_in)} reused image(s)")

    ms = epoch_ms(manifest, poses.timestamp_ns[chosen])
    stamp = datetime.fromtimestamp(ms[0] / 1000).strftime("%Y-%m-%d-%H_%M_%S")  # local time, as AnySense
    out_dir = args.out / stamp
    out_dir.mkdir(parents=True, exist_ok=True)
    video = out_dir / f"RGB_{stamp}.mp4"
    pose_txt = out_dir / f"AR_Pose_{stamp}.txt"

    size = tuple(int(v) for v in args.size.lower().split("x")) if args.size else None
    files = [frame_path(args.session, int(poses.frame_index[image_source[i]]), int(poses.timestamp_ns[image_source[i]]))
             for i in chosen]
    durations = None
    if args.vfr:
        t = poses.timestamp_ns[chosen] / 1e9
        durations = list(np.append(np.diff(t), np.median(np.diff(t))))
    encode_video(files, video, args.fps, size, durations, args.rotate)

    n_frames = count_frames(video)
    if n_frames != len(chosen):
        sys.exit(f"MP4 has {n_frames} frames but {len(chosen)} poses were selected; not writing pose file")

    with open(pose_txt, "w") as f:
        for i, t_ms in zip(chosen, ms):
            q = quat[i]
            p = pos[i]
            values = [q[0], q[1], q[2], q[3], p[0], p[1], p[2]]
            f.write(f'"<{t_ms}>" ,' + ",".join(swift_float(v) for v in values) + "\n")

    provenance = {
        "source_session": args.session.name,
        "source_format": manifest.get("format_version"),
        "rows_exported": [int(chosen[0]), int(chosen[-1])],
        "frames": len(chosen),
        "selection": label,
        "pose_jumps_in_source": jumps,
        "interpolated_pose_rows": filled_in,
        "reused_previous_image_rows": held_in,
        "measured_fps": manifest.get("measured_fps"),
        "video": {"rotation_deg_clockwise": args.rotate, "size": size or "native rotated",
                  "fps": "variable (real timing)" if args.vfr else args.fps},
        "intrinsics_landscape": {k: manifest.get("camera_config", {}).get(k) for k in ("cpu_image_size",)},
        "note": "AnySense writes no intrinsics; see the source session's intrinsics.json (landscape orientation)",
    }
    (out_dir / "posecam_export.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(f"wrote {video} ({n_frames} frames) and {pose_txt}")


if __name__ == "__main__":
    main()
