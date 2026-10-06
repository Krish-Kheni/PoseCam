"""Checks PoseCam's backend (backend/) against cloud/path-rules.json, the same file the Kotlin tests use.

Usage:

    cd backend && python ../cloud/check_backend_rules.py [path-rules.json]

Exit status is non-zero if the app and the backend disagree about any session id or path.
"""

import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.getcwd())

from app.errors import ApiError  # noqa: E402
from app.services import paths  # noqa: E402

rules_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name("path-rules.json")
rules = json.loads(rules_path.read_text())
problems: list[str] = []


def rejects(fn, value) -> bool:
    try:
        fn(value)
    except ApiError:
        return True
    return False


for sid in rules["sessionIds"]["valid"]:
    if rejects(paths.normalize_session_id, sid):
        problems.append(f"backend rejects a valid session id: {sid!r}")
for sid in rules["sessionIds"]["invalid"]:
    if not rejects(paths.normalize_session_id, sid):
        problems.append(f"backend accepts an invalid session id: {sid!r}")

for entry in rules["paths"]["allowed"]:
    path = entry["path"]
    try:
        cp = paths.classify_relative_path(path)
    except ApiError:
        problems.append(f"backend rejects an allowed path: {path!r}")
        continue
    if cp.subdir != entry["subdir"] or cp.size_class != entry["sizeClass"]:
        problems.append(f"{path!r}: backend says subdir={cp.subdir!r} size={cp.size_class!r}, rules say "
                        f"subdir={entry['subdir']!r} size={entry['sizeClass']!r}")
for path in rules["paths"]["rejected"]:
    if not rejects(paths.classify_relative_path, path):
        problems.append(f"backend accepts a rejected path: {path!r}")

n_ids = len(rules["sessionIds"]["valid"]) + len(rules["sessionIds"]["invalid"])
n_paths = len(rules["paths"]["allowed"]) + len(rules["paths"]["rejected"])
for p in problems:
    print("FAIL:", p)
print(f"{'OK' if not problems else str(len(problems)) + ' problem(s)'}: checked {n_ids} session ids and {n_paths} paths")
sys.exit(1 if problems else 0)
