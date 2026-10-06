from concurrent.futures import ThreadPoolExecutor

from tests.helpers import meta, presign


def test_parallel_presigns_count_each_file_once(client, session_id):
    data = b"payload"

    def call(_):
        return presign(client, session_id, "manifest.json", data).status_code

    with ThreadPoolExecutor(max_workers=8) as pool:
        codes = list(pool.map(call, range(24)))
    assert set(codes) == {200}
    m = meta(client, session_id)
    assert (m["totalFiles"], m["totalBytes"]) == (1, len(data))
