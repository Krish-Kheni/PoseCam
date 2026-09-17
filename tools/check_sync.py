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
            if images.get("image_frame_timestamp_mismatches"):
                errors.append(f"manifest: {images['image_frame_timestamp_mismatches']} images whose own timestamp "
                              "differs from the ARCore frame timestamp")

    for e in errors:
        print(f"FAIL: {e}")
    print("OK" if not errors else f"{len(errors)} problem(s)")
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
