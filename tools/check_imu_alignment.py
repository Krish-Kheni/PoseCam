# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy"]
# ///
"""Verify the camera↔IMU axis mapping and time offset on a phone, from one recording.

Usage:
    uv run tools/check_imu_alignment.py data/capture-XXXX

Record ~20 s rotating the phone about all three axes (tilt, pan, roll) while tracking.

It fits gyro rates to the angular velocity implied by poses.csv (independent of translation),
reports the nearest axis permutation, how well it explains the data, and the time offset
between camera and IMU clocks. Then it checks gravity: the accelerometer rotated into the
world frame should point straight up (+Y) at ~9.8 m/s^2.

Exit status 0 means the mapping equals the documented one (posecam_io.CAM_FROM_IMU)
with a small residual. Otherwise, use the reported mapping for that phone model.
"""

import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from posecam_io import CAM_FROM_IMU, load_imu, load_json, load_poses  # noqa: E402


def rotvec_from_matrix(m: np.ndarray) -> np.ndarray:
    """(N,3,3) rotation matrices -> (N,3) rotation vectors."""
    cos = np.clip((np.trace(m, axis1=1, axis2=2) - 1) / 2, -1, 1)
    angle = np.arccos(cos)
    axis = np.stack([m[:, 2, 1] - m[:, 1, 2], m[:, 0, 2] - m[:, 2, 0], m[:, 1, 0] - m[:, 0, 1]], 1)
    with np.errstate(invalid="ignore", divide="ignore"):
        scale = np.where(angle > 1e-9, angle / (2 * np.sin(angle)), 0.5)
    return axis * scale[:, None]


def interval_means(ts: np.ndarray, values: np.ndarray, starts: np.ndarray, ends: np.ndarray) -> np.ndarray:
    """Mean of samples with starts <= ts < ends, per interval (NaN when empty)."""
    csum = np.vstack([np.zeros((1, values.shape[1])), np.cumsum(values, 0)])
    lo = np.searchsorted(ts, starts)
    hi = np.searchsorted(ts, ends)
    count = (hi - lo)[:, None]
    with np.errstate(invalid="ignore", divide="ignore"):
        return (csum[hi] - csum[lo]) / count


def describe(mapping: np.ndarray) -> str:
    parts = []
    for cam_axis, row in zip("XYZ", mapping):
        j = int(np.argmax(np.abs(row)))
        parts.append(f"cam {cam_axis} = {'+' if row[j] > 0 else '-'}imu {'XYZ'[j]}")
    return ", ".join(parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("session", type=Path)
    args = parser.parse_args()
    s = args.session

    poses = load_poses(s)
    rot = poses.rotation_matrices()
    t = poses.timestamp_ns / 1e9
    # Pairs of consecutive tracked frames.
    idx = np.flatnonzero(poses.tracked[:-1] & poses.tracked[1:])
    if len(idx) < 30:
        sys.exit("not enough consecutive tracked frames")
    rel = np.einsum("nji,njk->nik", rot[idx], rot[idx + 1])  # R_i^T R_{i+1}: rotation in camera frame
    w_cam = rotvec_from_matrix(rel) / np.diff(t)[idx][:, None]

    device = load_json(s, "device.json")
    gyro_name = "gyro_uncal" if "gyro_uncal" in device.get("imu", {"gyro_uncal": 1}) else "gyro"
    gt, gw, gbias = load_imu(s, gyro_name)
    if len(gt) == 0:
        gyro_name = "gyro"
        gt, gw, gbias = load_imu(s, gyro_name)
    if len(gt) == 0:
        sys.exit("no gyroscope samples in imu.csv")
    if gbias is not None:
        gw = gw - gbias
    gt_s = gt / 1e9

    rms_cam = float(np.sqrt((w_cam ** 2).mean()))
    print(f"{s.name}: {len(idx)} frame pairs, gyro '{gyro_name}' {len(gt)} samples, "
          f"camera angular rate rms {rms_cam:.2f} rad/s")
    if rms_cam < 0.3:
        print("WARNING: little rotation; rotate the phone about all three axes for a reliable fit")

    # Pose jumps (ARCore relocalization) produce huge apparent rotation rates the gyro never
    # saw. Exclude pairs whose pose rotation rate is far beyond any the gyro measured nearby.
    w_gyro_mag = np.linalg.norm(interval_means(gt_s, gw, t[idx], t[idx + 1]), axis=1)
    w_cam_mag = np.linalg.norm(w_cam, axis=1)
    jumps = np.flatnonzero(w_cam_mag > np.maximum(3 * np.nan_to_num(w_gyro_mag), 0) + 2.0)
    for j in jumps:
        print(f"excluding pose jump at row {idx[j] + 1} (t={t[idx[j]] - t[0]:.2f} s): pose rate "
              f"{w_cam_mag[j]:.1f} rad/s vs gyro {w_gyro_mag[j]:.1f} rad/s")
    keep = np.ones(len(idx), bool)
    keep[jumps] = False
    idx, w_cam = idx[keep], w_cam[keep]
    rms_cam = float(np.sqrt((w_cam ** 2).mean()))

    def residual(offset_s: float, mapping: np.ndarray):
        w_imu = interval_means(gt_s, gw, t[idx] + offset_s, t[idx + 1] + offset_s)
        ok = ~np.isnan(w_imu).any(1)
        pred = w_imu[ok] @ mapping.T
        return float(np.sqrt(((pred - w_cam[ok]) ** 2).mean())), w_imu, ok

    # 1. Unconstrained linear fit at zero offset -> nearest signed permutation.
    _, w_imu, ok = residual(0.0, np.eye(3))
    fit, *_ = np.linalg.lstsq(w_imu[ok], w_cam[ok], rcond=None)
    fit = fit.T  # w_cam = fit @ w_imu
    mapping = np.zeros((3, 3))
    for r in range(3):
        j = int(np.argmax(np.abs(fit[r])))
        mapping[r, j] = np.sign(fit[r, j])
    valid_perm = np.allclose(np.abs(mapping).sum(0), 1)
    print("least-squares fit (cam = M @ imu):")
    print(np.array2string(fit, precision=3, suppress_small=True))

    # 2. Time offset scan with the permutation.
    offsets = np.arange(-0.060, 0.0605, 0.002)
    res = [residual(o, mapping)[0] for o in offsets]
    best = int(np.nanargmin(res))
    best_offset, best_res = offsets[best], res[best]
    ratio = best_res / rms_cam
    print(f"mapping: {describe(mapping)}{'' if valid_perm else '  (NOT a permutation: fit is ambiguous)'}")
    print(f"residual {best_res:.4f} rad/s = {100 * ratio:.1f}% of signal, "
          f"best IMU time offset {best_offset * 1e3:+.0f} ms (residual at 0 ms: {res[len(res) // 2]:.4f})")
    print("documented mapping:", describe(CAM_FROM_IMU))

    # 3. Gravity check with the fitted mapping.
    at, acc, _ = load_imu(s, "accel")
    tracked = np.flatnonzero(poses.tracked)
    acc_at = np.stack([np.interp(t[tracked], at / 1e9, acc[:, k]) for k in range(3)], 1)
    f_world = np.einsum("nij,nj->ni", rot[tracked], acc_at @ mapping.T)
    mean_f = f_world.mean(0)
    tilt = np.degrees(np.arccos(mean_f[1] / np.linalg.norm(mean_f)))
    print(f"mean specific force in world: {np.round(mean_f, 2)} (expect [0, +9.8, 0]); tilt from +Y {tilt:.1f} deg")

    problems = []
    if not valid_perm:
        problems.append("fit does not reduce to an axis permutation")
    if not np.array_equal(mapping, CAM_FROM_IMU):
        problems.append("mapping differs from the documented CAM_FROM_IMU")
    if ratio > 0.10:
        problems.append(f"residual {100 * ratio:.0f}% of signal (expected < 10%)")
    if abs(best_offset) > 0.010:
        problems.append(f"camera/IMU time offset {best_offset * 1e3:+.0f} ms (expected within ±10 ms)")
    if tilt > 5:
        problems.append(f"gravity is {tilt:.1f} deg off vertical")
    for p in problems:
        print(f"FAIL: {p}")
    print("OK" if not problems else f"{len(problems)} problem(s)")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
