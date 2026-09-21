# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "opencv-python-headless"]
# ///
"""Check an exported demo before it is delivered to the training pipeline.

Usage:
    uv run tools/check_export.py exports/2026-09-18-14_48_39-06f6db-s1 [more folders...]
    uv run tools/check_export.py exports/*            # a whole delivery

This is the last gate: it looks at the delivered MP4 and pose file themselves, not at the
recording they came from. It verifies what the consumer assumes and cannot check for you:

  - one pose line per video frame, quaternions unit-norm, 30 fps
  - per-frame motion below the dataloader's 0.05 m/step rejection threshold
  - the gripper jaws are visible, point UP in the image, and stay inside the frame
  - the gripper actually opens and closes (the aperture label is useless without it)

Jaw colour defaults to the red/orange NYU-style jaws; pass --hue-lo/--hue-hi in degrees
on a 0-360 wheel for other jaws (lo > hi wraps through red).
"""

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np

SAFE_STEP_M = 0.05          # dataloaders/pose_data.py safe_action_threshold
MIN_TWO_JAW_FRACTION = 0.90
MIN_APERTURE_RANGE = 0.35   # fraction of the clip's own aperture span that must be used


def load_poses(path: Path):
    rows = []
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        stamp, _, values = line.partition('" ,')
        rows.append([float(v) for v in values.split(",")])
    return np.array(rows)


def jaw_blobs(frame_bgr, lo: float, hi: float, sat: int, val: int, min_px: int):
    """Returns (stats, centroids) of components in the jaw colour, largest first."""
    hsv = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2HSV)
    hue = hsv[..., 0].astype(np.int32) * 2          # OpenCV hue is 0-179
    in_band = ((hue >= lo) | (hue <= hi)) if lo > hi else ((hue >= lo) & (hue <= hi))
    mask = (in_band & (hsv[..., 1] > sat) & (hsv[..., 2] > val)).astype(np.uint8)
    count, _, stats, centroids = cv2.connectedComponentsWithStats(mask, connectivity=4)
    order = [i for i in range(1, count) if stats[i, cv2.CC_STAT_AREA] >= min_px]
    order.sort(key=lambda i: -stats[i, cv2.CC_STAT_AREA])
    return [(stats[i], centroids[i]) for i in order]


def check(folder: Path, args) -> list[str]:
    print(f"== {folder.name}")
    videos = sorted(folder.glob("RGB_*.mp4"))
    poses = sorted(folder.glob("AR_Pose_*.txt"))
    if len(videos) != 1 or len(poses) != 1:
        return [f"expected one RGB_*.mp4 and one AR_Pose_*.txt, found {len(videos)} and {len(poses)}"]
    if videos[0].stem[4:] != poses[0].stem[8:]:
        return [f"video and pose file do not share a stem: {videos[0].name} vs {poses[0].name}"]

    errors = []
    pose = load_poses(poses[0])
    if pose.shape[1] != 7:
        return [f"pose lines have {pose.shape[1]} values, expected 7 (qx,qy,qz,qw,tx,ty,tz)"]
    norms = np.linalg.norm(pose[:, :4], axis=1)
    if np.abs(norms - 1).max() > 1e-3:
        errors.append(f"quaternions are not unit-norm (worst {np.abs(norms - 1).max():.4f})")
    steps = np.linalg.norm(np.diff(pose[:, 4:7], axis=0), axis=1)
    if len(steps):
        over = int((steps > SAFE_STEP_M).sum())
        print(f"poses {len(pose)}, step p50 {np.median(steps) * 1000:.1f} mm, max {steps.max() * 1000:.1f} mm")
        if over:
            errors.append(f"{over} frame-to-frame step(s) above the {SAFE_STEP_M * 100:.0f} cm the dataloader accepts")

    capture = cv2.VideoCapture(str(videos[0]))
    fps = capture.get(cv2.CAP_PROP_FPS)
    frames_meta = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    two_jaws = 0
    edges = {"top": 0, "bottom": 0, "left": 0, "right": 0}
    sampled = 0
    apertures = []
    index = 0
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        if index % args.every == 0:
            height, width = frame.shape[:2]
            blobs = jaw_blobs(frame, args.hue_lo, args.hue_hi, args.sat, args.val, args.min_blob)
            sampled += 1
            if len(blobs) >= 2:
                two_jaws += 1
                (a_stats, a_centre), (b_stats, b_centre) = blobs[0], blobs[1]
                # Jaws enter from the bottom of the image and their tips point up: that is
                # the "canonical view" the consumer's aperture detector assumes.
                for stats, _ in blobs[:2]:
                    left, top = stats[cv2.CC_STAT_LEFT], stats[cv2.CC_STAT_TOP]
                    right = left + stats[cv2.CC_STAT_WIDTH]
                    bottom = top + stats[cv2.CC_STAT_HEIGHT]
                    if top <= 1:
                        edges["top"] += 1
                    if bottom >= height - 2:
                        edges["bottom"] += 1
                    if left <= 1:
                        edges["left"] += 1
                    if right >= width - 2:
                        edges["right"] += 1
                size = np.sqrt((a_stats[cv2.CC_STAT_AREA] + b_stats[cv2.CC_STAT_AREA]) / 2)
                apertures.append(abs(a_centre[0] - b_centre[0]) / max(size, 1e-6))
        index += 1
    capture.release()

    if index != len(pose):
        errors.append(f"video has {index} frames but the pose file has {len(pose)} lines")
    if abs(fps - 30) > 0.6:
        errors.append(f"video is {fps:.2f} fps, not 30 (the consumer's action stride is 8 frames at 30 fps)")
    print(f"video {index} frames at {fps:.2f} fps ({frames_meta} in the header), sampled every {args.every}")

    if sampled:
        fraction = two_jaws / sampled
        print(f"two jaw blobs in {100 * fraction:.0f}% of sampled frames")
        if fraction < MIN_TWO_JAW_FRACTION:
            errors.append(f"jaws visible in only {100 * fraction:.0f}% of frames "
                          "(wrong jaw colour, jaws out of frame, or the video is rotated the wrong way)")
        jaws = max(2 * two_jaws, 1)   # two blobs counted per frame
        share = {k: v / jaws for k, v in edges.items()}
        print("jaw blobs touching an edge: " + ", ".join(f"{k} {100 * v:.0f}%" for k, v in share.items())
              + "  (bottom is where they enter, so high is expected there)")
        if share["top"] > 0.5 and share["bottom"] < 0.5:
            errors.append("the jaws run off the TOP and not the bottom: the video is upside down for the "
                          "consumer's detector — re-export with a different --rotate")
        elif share["top"] > 0.25:
            errors.append(f"a jaw runs off the top edge in {100 * share['top']:.0f}% of frames: move the phone back")
        for side in ("left", "right"):
            if share[side] > 0.2:
                errors.append(f"a jaw is cut off by the {side} edge in {100 * share[side]:.0f}% of frames: "
                              "the aperture is measured from a truncated jaw — move the mount sideways")
    if len(apertures) >= 10:
        trace = np.array(apertures)
        span = np.percentile(trace, 95) - np.percentile(trace, 5)
        relative = span / max(np.percentile(trace, 95), 1e-6)
        print(f"gripper aperture spans {span:.2f} ({100 * relative:.0f}% of its widest)")
        if relative < MIN_APERTURE_RANGE:
            errors.append(f"the gripper barely moves ({100 * relative:.0f}% of its widest): "
                          "each demo must open and close fully at least once")
    elif sampled:
        errors.append("too few frames with two jaws to judge whether the gripper opens and closes")

    for e in errors:
        print(f"FAIL: {e}")
    print("OK" if not errors else f"{len(errors)} problem(s)")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folders", type=Path, nargs="+")
    parser.add_argument("--hue-lo", type=float, default=340.0, help="jaw hue band start, degrees (wraps if > --hue-hi)")
    parser.add_argument("--hue-hi", type=float, default=22.0)
    parser.add_argument("--sat", type=int, default=110)
    parser.add_argument("--val", type=int, default=40)
    parser.add_argument("--min-blob", type=int, default=800, help="pixels, at the video's own size")
    parser.add_argument("--every", type=int, default=5, help="sample every Nth frame for the jaw checks")
    args = parser.parse_args()
    failed = False
    for folder in args.folders:
        if not folder.is_dir():
            print(f"not a folder: {folder}")
            failed = True
            continue
        failed |= bool(check(folder, args))
        print()
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
