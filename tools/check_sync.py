# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Validate a PoseCam session's timing and pose/image correspondence.

Usage:
    uv run tools/check_sync.py data/capture-XXXX [more sessions...]

Checks:
  - frame_index is 0..N-1 and timestamps strictly increase
  - gaps larger than 2x the median frame interval (dropped camera frames, stalls)
  - every row marked image=saved has exactly one JPEG with the same index and timestamp
  - no JPEG exists without a matching row, and no leftover .tmp files
  - manifest.json agrees with poses.csv
  - imu.csv: per-sensor rate, gaps, non-increasing timestamps, coverage of the frame span
  - intrinsics.json exists and did not change during the recording
  - camera and IMU timestamps look like the same clock
  - frame_metadata.csv: one row per frame, OIS state, focus distance, exposure
Exit status is non-zero if any check fails.
"""

import csv
import json
import re
import statistics
import sys
from pathlib import Path

FRAME_NAME = re.compile(r"^(\d{6,})_(\d+)\.jpg$")


def check(session: Path) -> list[str]:
    errors: list[str] = []
    print(f"== {session.name}")

    with open(session / "poses.csv", newline="") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
        has_images = "image" in (reader.fieldnames or [])
    n = len(rows)
    if n == 0:
        return errors + ["poses.csv has no rows"]

    index = [int(r["frame_index"]) for r in rows]
    ts = [int(r["timestamp_ns"]) for r in rows]
    if index != list(range(n)):
        errors.append("frame_index is not 0..N-1")
    non_increasing = sum(1 for a, b in zip(ts, ts[1:]) if b <= a)
    if non_increasing:
        errors.append(f"{non_increasing} non-increasing timestamps")

    dt = [(b - a) / 1e6 for a, b in zip(ts, ts[1:])]
    if dt:
        median = statistics.median(dt)
        gaps = [(i + 1, d) for i, d in enumerate(dt) if d > 2 * median]
        duration = (ts[-1] - ts[0]) / 1e9
        print(f"rows {n}, {duration:.2f} s, {(n - 1) / duration:.2f} fps, median dt {median:.1f} ms, "
              f"p99 dt {sorted(dt)[int(0.99 * (len(dt) - 1))]:.1f} ms, max dt {max(dt):.1f} ms")
        missed = sum(round(d / median) - 1 for _, d in gaps)
        print(f"gaps > 2x median: {len(gaps)} (~{missed} camera frames never reached the app)"
              + (f", worst at rows {[g[0] for g in sorted(gaps, key=lambda g: -g[1])[:5]]}" if gaps else ""))
        # Framerate over time, to spot thermal throttling on long takes.
        if duration > 60:
            window = []
            for start in range(0, int(duration), 30):
                lo, hi = ts[0] + start * 10**9, ts[0] + (start + 30) * 10**9
                window.append(sum(1 for t in ts if lo <= t < hi) / 30)
            print("fps per 30 s window: " + " ".join(f"{w:.1f}" for w in window))

    tracked = sum(1 for r in rows if r["tracking_state"] == "TRACKING")
    print(f"tracked {tracked}/{n} ({100 * tracked / n:.1f}%)")

    frames_dir = session / "frames"
    if has_images:
        statuses: dict[str, int] = {}
        for r in rows:
            statuses[r["image"]] = statuses.get(r["image"], 0) + 1
        print(f"image column: {statuses}")

        files = {}
        if frames_dir.is_dir():
            for p in frames_dir.iterdir():
                if p.suffix == ".tmp":
                    errors.append(f"leftover temp file {p.name}")
                    continue
                m = FRAME_NAME.match(p.name)
                if not m:
                    errors.append(f"unexpected file frames/{p.name}")
                    continue
                files[int(m.group(1))] = (int(m.group(2)), p)

        saved_rows = [r for r in rows if r["image"] == "saved"]
        missing = [int(r["frame_index"]) for r in saved_rows if int(r["frame_index"]) not in files]
        wrong_ts = [i for i, (fts, _) in files.items() if i < n and fts != ts[i]]
        orphans = [i for i in files if i >= n or rows[i]["image"] != "saved"]
        empty = [i for i, (_, p) in files.items() if p.stat().st_size == 0]
        print(f"jpeg files {len(files)}, rows marked saved {len(saved_rows)}")
        if missing:
            errors.append(f"{len(missing)} saved rows without a JPEG (first: {missing[:5]})")
        if wrong_ts:
            errors.append(f"{len(wrong_ts)} JPEG filenames whose timestamp differs from poses.csv (first: {wrong_ts[:5]})")
        if orphans:
            errors.append(f"{len(orphans)} JPEGs with no saved row (first: {orphans[:5]})")
        if empty:
            errors.append(f"{len(empty)} empty JPEG files (first: {empty[:5]})")
        if files:
            total = sum(p.stat().st_size for _, p in files.values())
            print(f"jpeg total {total / 1e6:.1f} MB, mean {total / len(files) / 1e3:.0f} kB")

    errors += check_frame_metadata(session, ts)
    errors += check_imu(session, ts)
    errors += check_intrinsics(session)

    manifest_path = session / "manifest.json"
    if not manifest_path.exists():
        errors.append("manifest.json missing")
    else:
        m = json.loads(manifest_path.read_text())
        if not m.get("complete"):
            errors.append("manifest: recording did not stop cleanly")
        if m.get("frame_count") != n:
            errors.append(f"manifest frame_count {m.get('frame_count')} != {n} rows")
        if m.get("first_timestamp_ns") != ts[0] or m.get("last_timestamp_ns") != ts[-1]:
            errors.append("manifest first/last timestamps disagree with poses.csv")
        images = m.get("images")
        if images:
            if images.get("write_failures"):
                errors.append(f"manifest: write failures {images['write_failures'][:5]} ({images.get('first_write_error')})")
            delta = images.get("image_minus_frame_timestamp_ns_range")
            if delta:
                print(f"image timestamp - frame timestamp: {delta[0] / 1e6:+.3f} .. {delta[1] / 1e6:+.3f} ms")
            # posecam-2 builds before the threshold existed counted any nonzero difference.
            if images.get("image_frame_timestamp_mismatches_over_5ms"):
                errors.append(f"manifest: {images['image_frame_timestamp_mismatches_over_5ms']} images more than "
                              "5 ms from their ARCore frame timestamp (probably a different frame)")

        clock = m.get("clock_check") or {}
        frame_age = clock.get("elapsed_realtime_minus_first_frame_timestamp_ns")
        imu_age = clock.get("elapsed_realtime_minus_last_imu_timestamp_ns")
        if frame_age is not None and imu_age is not None:
            print(f"clock check: frame age {frame_age / 1e6:.1f} ms, IMU sample age {imu_age / 1e6:.1f} ms")
            # Both are processing latencies on the same clock: expect 0..1 s. A clock mismatch
            # shows up as seconds to days.
            for name, age in (("frame", frame_age), ("IMU", imu_age)):
                if not -0.05e9 < age < 1e9:
                    errors.append(f"{name} timestamps are {age / 1e9:.3f} s from elapsedRealtime: "
                                  "camera and IMU may not share a clock")
        device_path = session / "device.json"
        if device_path.exists():
            source = json.loads(device_path.read_text()).get("camera", {}).get("timestamp_source")
            print(f"camera timestamp source: {source}")
            if source != "REALTIME":
                errors.append(f"camera timestamp source is {source}, not REALTIME: verify camera/IMU alignment")

    for e in errors:
        print(f"FAIL: {e}")
    print("OK" if not errors else f"{len(errors)} problem(s)")
    return errors


def check_imu(session: Path, frame_ts: list[int]) -> list[str]:
    path = session / "imu.csv"
    if not path.exists():
        return []  # posecam-1/2 sessions have no IMU
    errors = []
    by_sensor: dict[str, list[int]] = {}
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            by_sensor.setdefault(r["sensor"], []).append(int(r["timestamp_ns"]))
    if not by_sensor:
        return ["imu.csv has no samples"]
    for sensor, ts in by_sensor.items():
        dt = [(b - a) / 1e6 for a, b in zip(ts, ts[1:])]
        if not dt:
            errors.append(f"imu {sensor}: only {len(ts)} sample")
            continue
        median = statistics.median(dt)
        bad = sum(1 for d in dt if d <= 0)
        gaps = sum(1 for d in dt if d > 5 * median)
        rate = (len(ts) - 1) / ((ts[-1] - ts[0]) / 1e9)
        print(f"imu {sensor}: {len(ts)} samples, {rate:.1f} Hz, median dt {median:.2f} ms, "
              f"max dt {max(dt):.1f} ms, gaps >5x median: {gaps}, non-increasing: {bad}")
        if bad:
            errors.append(f"imu {sensor}: {bad} non-increasing timestamps")
        if ts[0] > frame_ts[0] + 50_000_000 or ts[-1] < frame_ts[-1] - 50_000_000:
            errors.append(f"imu {sensor} does not cover the frame time span "
                          f"(starts {(ts[0] - frame_ts[0]) / 1e6:+.0f} ms, ends {(ts[-1] - frame_ts[-1]) / 1e6:+.0f} ms "
                          "relative to frames)")
    return errors


def check_frame_metadata(session: Path, frame_ts: list[int]) -> list[str]:
    path = session / "frame_metadata.csv"
    if not path.exists():
        return []  # before posecam-4
    with open(path, newline="") as f:
        rows = list(csv.DictReader(f))
    errors = []
    if [int(r["timestamp_ns"]) for r in rows] != frame_ts:
        errors.append("frame_metadata.csv rows do not match poses.csv timestamps")

    def values(col, cast=float):
        return [cast(r[col]) for r in rows if r[col] != ""]

    ois = values("ois_mode", int)
    focus = values("focus_distance_diopters")
    exposure = values("exposure_time_ns", int)
    skew = values("rolling_shutter_skew_ns", int)
    print(f"frame metadata: {len(rows)} rows, OIS on in {sum(1 for v in ois if v)}/{len(ois)} frames")
    if focus:
        lo, hi = min(focus), max(focus)
        dist = "infinity" if hi == 0 else f"{1 / hi:.2f} m" if lo == hi else f"{1 / hi:.2f}..{(1 / lo if lo else float('inf')):.2f} m"
        print(f"focus distance: {lo:.3f}..{hi:.3f} diopters ({dist}; metric only if device.json calibration allows)")
        if hi - lo > 1e-3:
            errors.append(f"focus distance changed during recording ({lo:.3f}..{hi:.3f} diopters)")
    if exposure:
        print(f"exposure: {min(exposure) / 1e6:.2f}..{max(exposure) / 1e6:.2f} ms (median "
              f"{statistics.median(exposure) / 1e6:.2f} ms)" + (f", rolling shutter skew {statistics.median(skew) / 1e6:.1f} ms" if skew else ""))
    if any(ois):
        errors.append(f"optical stabilization was ON in {sum(1 for v in ois if v)} frames: intrinsics may vary per frame")
    return errors


def check_intrinsics(session: Path) -> list[str]:
    path = session / "intrinsics.json"
    if not path.exists():
        return []  # posecam-1/2 sessions have no intrinsics.json
    k = json.loads(path.read_text())
    if not k.get("fx"):
        return ["intrinsics.json has no values"]
    print(f"intrinsics: {k['width']}x{k['height']} fx {k['fx']:.2f} fy {k['fy']:.2f} "
          f"cx {k['cx']:.2f} cy {k['cy']:.2f} ({k.get('samples')} samples)")
    errors = []
    if not k.get("complete"):
        errors.append("intrinsics.json not finalized")
    if k.get("changed_during_recording"):
        errors.append(f"intrinsics changed during recording (first at frame {k.get('first_change_frame_index')}, "
                      f"end value {k.get('at_end')})")
    return errors


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    failed = False
    for arg in sys.argv[1:]:
        failed |= bool(check(Path(arg)))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
