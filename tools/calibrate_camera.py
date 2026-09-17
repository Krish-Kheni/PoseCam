# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "opencv-python-headless"]
# ///
"""Calibrate the recorded CPU image from a checkerboard session and compare with ARCore.

Usage:
    uv run tools/calibrate_camera.py data/capture-XXXX --pattern 9x6 --square 0.025

Record ~30 s slowly moving a printed checkerboard's view across the whole image,
especially the corners and edges, at several tilts (30-45 degrees), with the whole
board in view and in focus. Calibrate with the same resolution AND focus mode as the
data it applies to. --pattern counts INNER corners
(a board of 10x7 squares has 9x6 inner corners).

Answers two questions:
  1. Is ARCore's CPU image already undistorted? Fitted k1/k2 near zero (and a small
     corner displacement) means yes; values close to the factory LENS_DISTORTION in
     device.json mean no.
  2. Are ARCore's fx/fy/cx/cy (intrinsics.json) right? Compared with the fit, in %.
"""

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from posecam_io import frame_path, load_json, load_poses  # noqa: E402


MAX_RELIABLE_RMS_PX = 1.0


def camera2_to_opencv(d: list[float]) -> np.ndarray:
    """Camera2 LENS_DISTORTION [k1, k2, k3, p1, p2] -> OpenCV [k1, k2, p1, p2, k3].
    Both map undistorted normalized coordinates to distorted ones with the same terms."""
    k1, k2, k3, p1, p2 = d
    return np.array([k1, k2, p1, p2, k3], dtype=float)


def corner_displacement(k: np.ndarray, dist: np.ndarray, w: int, h: int) -> float:
    """Pixel shift that distortion causes for a ray that a pinhole camera images at the
    image corner (0,0). Forward model only, so it is well defined for any coefficients."""
    fx, fy, cx, cy = k[0, 0], k[1, 1], k[0, 2], k[1, 2]
    ray = np.array([[(0 - cx) / fx, (0 - cy) / fy, 1.0]])
    distorted, _ = cv2.projectPoints(ray, np.zeros(3), np.zeros(3), k, dist)
    return float(np.linalg.norm(distorted.ravel()))


def factory_intrinsics(camera: dict, w: int, h: int) -> np.ndarray | None:
    """Camera2 LENS_INTRINSIC_CALIBRATION is for the full pixel array. Assumes the CPU image
    is the array scaled to the image width and centre-cropped vertically (typical, unverified)."""
    cal, array = camera.get("lens_intrinsic_calibration"), camera.get("sensor_pixel_array_size")
    if not cal or not array or not cal[0]:
        return None
    s = w / array[0]
    crop_y = (array[1] * s - h) / 2
    return np.array([[cal[0] * s, 0, cal[2] * s], [0, cal[1] * s, cal[3] * s - crop_y], [0, 0, 1]])


def detect(session: Path, pattern: tuple[int, int], max_attempts: int):
    poses = load_poses(session)
    n = len(poses.rows)
    step = max(1, n // max_attempts)
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 1e-3)
    found, size = [], None
    for i in range(0, n, step):
        if poses.rows[i].get("image", "saved") != "saved":
            continue
        img = cv2.imread(str(frame_path(session, int(poses.frame_index[i]), int(poses.timestamp_ns[i]))), cv2.IMREAD_GRAYSCALE)
        if img is None:
            continue
        size = img.shape[::-1]
        ok, corners = cv2.findChessboardCorners(img, pattern, cv2.CALIB_CB_ADAPTIVE_THRESH | cv2.CALIB_CB_NORMALIZE_IMAGE)
        if ok:
            corners = cv2.cornerSubPix(img, corners, (5, 5), (-1, -1), criteria)
            found.append((i, corners))
    return found, size


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("session", type=Path)
    parser.add_argument("--pattern", default="9x6", help="inner corners, COLSxROWS")
    parser.add_argument("--square", type=float, default=0.025, help="square size in metres")
    parser.add_argument("--attempts", type=int, default=150, help="frames to try detecting in")
    parser.add_argument("--max-views", type=int, default=40, help="detections used for calibration")
    args = parser.parse_args()

    cols, rows = (int(v) for v in args.pattern.lower().split("x"))
    found, size = detect(args.session, (cols, rows), args.attempts)
    print(f"checkerboard found in {len(found)} of up to {args.attempts} sampled frames")
    if len(found) < 10:
        sys.exit("need at least 10 detections: check --pattern (inner corners) and lighting")
    if len(found) > args.max_views:
        found = [found[i] for i in np.linspace(0, len(found) - 1, args.max_views).round().astype(int)]

    board = np.zeros((cols * rows, 3), np.float32)
    board[:, :2] = np.mgrid[0:cols, 0:rows].T.reshape(-1, 2) * args.square
    w, h = size
    rms, k, dist, _, _ = cv2.calibrateCamera([board] * len(found), [c for _, c in found], size, None, None)
    dist = dist.ravel()[:5]
    print(f"\ncalibrated from {len(found)} views of {w}x{h}: reprojection RMS {rms:.3f} px")
    print(f"  fx {k[0, 0]:.2f}  fy {k[1, 1]:.2f}  cx {k[0, 2]:.2f}  cy {k[1, 2]:.2f}")
    print(f"  distortion (OpenCV k1,k2,p1,p2,k3): {np.round(dist, 4)}")
    print(f"  corner displacement from distortion: {corner_displacement(k, dist, w, h):.1f} px")

    arcore = load_json(args.session, "intrinsics.json")
    if arcore.get("fx"):
        if (arcore["width"], arcore["height"]) != (w, h):
            print(f"\nWARNING: intrinsics.json is for {arcore['width']}x{arcore['height']}, frames are {w}x{h}")
        print("\nARCore intrinsics.json vs calibration:")
        for name, fitted in (("fx", k[0, 0]), ("fy", k[1, 1]), ("cx", k[0, 2]), ("cy", k[1, 2])):
            print(f"  {name} {arcore[name]:8.2f} vs {fitted:8.2f}  ({100 * (arcore[name] - fitted) / fitted:+.1f}%)")

    camera = load_json(args.session, "device.json").get("camera", {})
    if camera.get("lens_distortion"):
        factory = camera2_to_opencv(camera["lens_distortion"])
        fk = factory_intrinsics(camera, w, h)
        print("\nfactory Camera2 calibration (full sensor array, scaled to this image):")
        print(f"  distortion (OpenCV order): {np.round(factory, 4)}")
        if fk is not None:
            print(f"  fx {fk[0, 0]:.2f}  fy {fk[1, 1]:.2f}  cx {fk[0, 2]:.2f}  cy {fk[1, 2]:.2f}")
            print(f"  corner displacement from factory distortion: {corner_displacement(fk, factory, w, h):.1f} px")

    fitted_px = corner_displacement(k, dist, w, h)
    if rms > MAX_RELIABLE_RMS_PX:
        print(f"\nverdict: UNRELIABLE (reprojection RMS {rms:.2f} px > {MAX_RELIABLE_RMS_PX} px). Usual causes: "
              "blurry board (out of focus or motion), board partly outside the frame, too few tilted views, "
              "corners and edges of the image not covered. Record again; ignore the numbers above.")
        sys.exit(1)
    print("\nverdict:", "image looks UNDISTORTED (corner shift < 1 px)" if fitted_px < 1.0 else
          f"image is DISTORTED ({fitted_px:.1f} px at the corner): undistort with the fitted coefficients")


if __name__ == "__main__":
    main()
