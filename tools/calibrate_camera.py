# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "opencv-python-headless"]
# ///
"""Calibrate the recorded CPU image from a checkerboard session and compare with ARCore.

Usage:
    uv run tools/calibrate_camera.py data/capture-XXXX --pattern 9x6 --square 0.025

Record ~60 s of a checkerboard, holding each pose ~1 s (moving frames are skewed by the
rolling shutter and are filtered out using the gyro). What matters most:

  * STRONG TILTS: view the board from the side/above so it looks like a trapezoid, not a
    rectangle. Frontal views cannot separate focal length from distortion, however many
    you take.
  * The board should fill roughly half the image in the close views.
  * Cover the image corners and edges too, with the whole board always inside the frame. Calibrate with the same resolution AND focus mode as the
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
from posecam_io import frame_path, load_imu, load_json, load_poses  # noqa: E402


MAX_RELIABLE_RMS_PX = 1.0
MAX_FOCAL_SPREAD_PCT = 2.0
MIN_TILT_DEG = 25.0


def max_shift(k: np.ndarray, dist: np.ndarray, w: int, h: int) -> float:
    """Largest pixel shift distortion causes anywhere in the image (not just the corner)."""
    xs, ys = np.meshgrid(np.linspace(0, w - 1, 33), np.linspace(0, h - 1, 25))
    pix = np.stack([xs.ravel(), ys.ravel()], 1)
    rays = np.stack([(pix[:, 0] - k[0, 2]) / k[0, 0], (pix[:, 1] - k[1, 2]) / k[1, 1], np.ones(len(pix))], 1)
    proj, _ = cv2.projectPoints(rays, np.zeros(3), np.zeros(3), k, dist)
    return float(np.linalg.norm(proj.reshape(-1, 2) - pix, axis=1).max())


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


def angular_rates(session: Path, poses, indices: list[int]) -> dict[int, float]:
    """Mean |gyro| over each frame's readout, rad/s. Frames captured while the phone moves are
    skewed by the ~29 ms rolling shutter and bias the fit, so they are filtered out."""
    try:
        gt, gw, gbias = load_imu(session, "gyro_uncal")
        if len(gt) == 0:
            gt, gw, gbias = load_imu(session, "gyro")
        if gbias is not None:
            gw = gw - gbias
    except FileNotFoundError:
        return {}
    if len(gt) == 0:
        return {}
    magnitude = np.linalg.norm(gw, axis=1)
    out = {}
    for i in indices:
        t0 = int(poses.timestamp_ns[i])
        window = (gt >= t0) & (gt < t0 + 40_000_000)  # one frame's readout
        out[i] = float(magnitude[window].mean()) if window.any() else float("nan")
    return out


def view_tilt(corners: np.ndarray, k: np.ndarray, board: np.ndarray) -> tuple[float, float]:
    """Rough (tilt angle, tilt direction) of the board, using an approximate K just for
    choosing a diverse subset of views."""
    ok, rvec, _ = cv2.solvePnP(board, corners, k, None, flags=cv2.SOLVEPNP_IPPE)
    if not ok:
        return 0.0, 0.0
    normal = cv2.Rodrigues(rvec)[0][:, 2]
    tilt = np.degrees(np.arccos(min(1.0, abs(normal[2]))))
    return tilt, np.degrees(np.arctan2(normal[1], normal[0]))


def choose_views(found, rates, tilts, centres, want: int) -> list[int]:
    """Greedy pick: prefer still frames, then spread over tilt angle, tilt direction and
    board position, so focal length and distortion are separately constrained."""
    def feature(n):
        tilt, direction = tilts[n]
        return np.array([tilt / 15.0, np.cos(np.radians(direction)), np.sin(np.radians(direction)),
                         centres[n][0] / 100.0, centres[n][1] / 100.0])
    order = sorted(range(len(found)), key=lambda n: rates[n])
    chosen = [order[0]]
    for _ in range(min(want, len(found)) - 1):
        best, best_score = None, -1.0
        for n in order:
            if n in chosen:
                continue
            distance = min(float(np.linalg.norm(feature(n) - feature(m))) for m in chosen)
            if distance > best_score:
                best, best_score = n, distance
        if best is None:
            break
        chosen.append(best)
    return chosen


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
    parser.add_argument("--max-rate", type=float, default=0.15,
                        help="max mean gyro rate (rad/s) for a usable view; rolling shutter skews moving frames")
    args = parser.parse_args()

    cols, rows = (int(v) for v in args.pattern.lower().split("x"))
    found, size = detect(args.session, (cols, rows), args.attempts)
    print(f"checkerboard found in {len(found)} sampled frames")
    if len(found) < 10:
        sys.exit("need at least 10 detections: check --pattern (inner corners) and lighting")
    board = np.zeros((cols * rows, 3), np.float32)
    board[:, :2] = np.mgrid[0:cols, 0:rows].T.reshape(-1, 2) * args.square
    w, h = size

    poses = load_poses(args.session)
    rates = angular_rates(args.session, poses, [i for i, _ in found])
    still = [n for n, (i, _) in enumerate(found)
             if not rates or not np.isfinite(rates.get(i, np.nan)) or rates[i] <= args.max_rate]
    print(f"{len(still)} of {len(found)} detections were taken with the phone nearly still "
          f"(<= {args.max_rate} rad/s)")
    if len(still) < 12:
        print("WARNING: few still frames; hold each pose for ~1 s before moving on")
        still = list(range(len(found)))

    guess = np.array([[500.0, 0, w / 2], [0, 500.0, h / 2], [0, 0, 1]])
    tilts = {n: view_tilt(found[n][1], guess, board) for n in still}
    centres = {n: found[n][1].reshape(-1, 2).mean(0) for n in still}
    rate_of = {n: rates.get(found[n][0], 0.0) for n in still}
    chosen = choose_views([found[n] for n in still], [rate_of[n] for n in still],
                          [tilts[n] for n in still], [centres[n] for n in still], args.max_views)
    found = [found[still[n]] for n in chosen]
    tilt_values = [tilts[still[n]][0] for n in chosen]
    areas = [cv2.contourArea(cv2.convexHull(c.reshape(-1, 2).astype(np.float32))) / (w * h) for _, c in found]
    print(f"selected {len(found)} views, board tilt {min(tilt_values):.0f}-{max(tilt_values):.0f} deg "
          f"(median {np.median(tilt_values):.0f}), board covers {100 * np.median(areas):.0f}% of the image "
          f"(max {100 * max(areas):.0f}%)")
    if max(tilt_values) < MIN_TILT_DEG:
        print(f"WARNING: all views are within {max(tilt_values):.0f} deg of straight-on. Focal length and "
              "distortion cannot be separated from frontal views: the board must look like a trapezoid "
              "(clearly keystoned), not a rectangle.")
    if np.median(areas) < 0.25:
        print("WARNING: the board is small in the frame; get closer so it fills roughly half the image.")
    object_points = [board] * len(found)
    image_points = [c for _, c in found]
    rms, k, dist, _, _ = cv2.calibrateCamera(object_points, image_points, size, None, None)
    dist = dist.ravel()[:5]
    print(f"\ncalibrated from {len(found)} views of {w}x{h}: reprojection RMS {rms:.3f} px")
    print(f"  fx {k[0, 0]:.2f}  fy {k[1, 1]:.2f}  cx {k[0, 2]:.2f}  cy {k[1, 2]:.2f}")
    print(f"  distortion (OpenCV k1,k2,p1,p2,k3): {np.round(dist, 4)}")
    print(f"  corner displacement from distortion: {corner_displacement(k, dist, w, h):.1f} px")
    print(f"  largest distortion shift anywhere in the image: {max_shift(k, dist, w, h):.1f} px")

    # Coverage: corners seen, as a fraction of the image area (10x10 cells).
    cells = np.zeros((10, 10), bool)
    for c in image_points:
        for u, v in c.reshape(-1, 2):
            cells[min(9, int(v / h * 10)), min(9, int(u / w * 10))] = True
    coverage = 100 * cells.mean()

    # Conditioning: if the reprojection error barely moves when the distortion model
    # changes but the focal length does, focal length and distortion are trading off
    # and neither is pinned down. More tilt and wider coverage fix this, not more frames.
    variants = {
        "no tangential": cv2.CALIB_ZERO_TANGENT_DIST,
        "k1,k2 only": cv2.CALIB_ZERO_TANGENT_DIST | cv2.CALIB_FIX_K3,
        "k1 only": cv2.CALIB_ZERO_TANGENT_DIST | cv2.CALIB_FIX_K2 | cv2.CALIB_FIX_K3,
        "no distortion": cv2.CALIB_ZERO_TANGENT_DIST | cv2.CALIB_FIX_K1 | cv2.CALIB_FIX_K2 | cv2.CALIB_FIX_K3,
    }
    focals = [k[0, 0]]
    print("\nstability across distortion models (fx should barely move):")
    for label, flags in variants.items():
        v_rms, v_k, _, _, _ = cv2.calibrateCamera(object_points, image_points, size, None, None, flags=flags)
        # "no distortion" is a probe of how much distortion matters, not a candidate model,
        # so it is reported but kept out of the conditioning test.
        if label != "no distortion":
            focals.append(v_k[0, 0])
        print(f"  {label:16s} RMS {v_rms:.3f} px, fx {v_k[0, 0]:.1f}")
    spread = 100 * (max(focals) - min(focals)) / np.mean(focals)
    print(f"image coverage {coverage:.0f}% of cells, fx spread across models {spread:.1f}%")
    if spread > MAX_FOCAL_SPREAD_PCT:
        print(f"\nverdict: UNRELIABLE (fx spread {spread:.1f}% > {MAX_FOCAL_SPREAD_PCT}%). The views do not "
              "separate focal length from distortion. Record again with more tilt (30-45 degrees in "
              "several directions, not just straight on), the board filling more of the frame, and a "
              "range of distances. Coverage of the image corners matters more than frame count.")
        sys.exit(1)

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
