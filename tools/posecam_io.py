"""Shared loaders for PoseCam session folders (imported by the tools in this directory)."""

import csv
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

# Camera frame (ARCore) from IMU frame (Android sensor axes), for back cameras mounted with
# SENSOR_ORIENTATION 90: v_cam = CAM_FROM_IMU @ v_imu. Verified on SM-G781B; check other
# models with check_imu_alignment.py.
CAM_FROM_IMU = np.array([[0, -1, 0], [1, 0, 0], [0, 0, 1]], dtype=float)


@dataclass
class Poses:
    frame_index: np.ndarray   # (N,)
    timestamp_ns: np.ndarray  # (N,) int64
    position: np.ndarray      # (N, 3), NaN when untracked
    quat_xyzw: np.ndarray     # (N, 4), NaN when untracked
    tracked: np.ndarray       # (N,) bool
    rows: list[dict]

    def rotation_matrices(self) -> np.ndarray:
        """(N, 3, 3) world-from-camera rotations (NaN rows when untracked)."""
        x, y, z, w = (self.quat_xyzw[:, i] for i in range(4))
        return np.stack([
            np.stack([1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)], -1),
            np.stack([2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)], -1),
            np.stack([2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)], -1),
        ], -2)


def load_poses(session: Path) -> Poses:
    with open(session / "poses.csv", newline="") as f:
        rows = list(csv.DictReader(f))
    n = len(rows)
    tracked = np.array([r["tracking_state"] == "TRACKING" for r in rows])
    position = np.full((n, 3), np.nan)
    quat = np.full((n, 4), np.nan)
    for i, r in enumerate(rows):
        if tracked[i]:
            position[i] = [float(r[k]) for k in ("tx", "ty", "tz")]
            quat[i] = [float(r[k]) for k in ("qx", "qy", "qz", "qw")]
    return Poses(
        frame_index=np.array([int(r["frame_index"]) for r in rows]),
        timestamp_ns=np.array([int(r["timestamp_ns"]) for r in rows], dtype=np.int64),
        position=position, quat_xyzw=quat, tracked=tracked, rows=rows,
    )


def load_imu(session: Path, sensor: str) -> tuple[np.ndarray, np.ndarray, np.ndarray | None]:
    """Returns (timestamp_ns, xyz, bias_xyz or None) for one sensor name, sorted by time."""
    ts, xyz, bias = [], [], []
    with open(session / "imu.csv", newline="") as f:
        for r in csv.DictReader(f):
            if r["sensor"] != sensor:
                continue
            ts.append(int(r["timestamp_ns"]))
            xyz.append([float(r[k]) for k in "xyz"])
            bias.append([float(r[f"bias_{k}"]) if r[f"bias_{k}"] else np.nan for k in "xyz"])
    ts = np.array(ts, dtype=np.int64)
    order = np.argsort(ts, kind="stable")
    bias = np.array(bias)
    has_bias = len(bias) and not np.isnan(bias).all()
    return ts[order], np.array(xyz)[order], (bias[order] if has_bias else None)


def load_json(session: Path, name: str) -> dict:
    path = session / name
    return json.loads(path.read_text()) if path.exists() else {}


def frame_path(session: Path, frame_index: int, timestamp_ns: int) -> Path:
    return session / "frames" / f"{frame_index:06d}_{timestamp_ns}.jpg"


def project(points_world: np.ndarray, position: np.ndarray, rot_wc: np.ndarray, k: dict):
    """Pinhole projection into the CPU image. ARCore camera looks along -Z with +Y up;
    image rows grow downward. Returns (uv (M,2), in_front (M,) bool)."""
    p_cam = (points_world - position) @ rot_wc  # = R^T (p - t) row-wise
    depth = -p_cam[:, 2]
    in_front = depth > 0.05
    with np.errstate(divide="ignore", invalid="ignore"):
        u = k["cx"] + k["fx"] * p_cam[:, 0] / depth
        v = k["cy"] - k["fy"] * p_cam[:, 1] / depth
    return np.stack([u, v], 1), in_front
