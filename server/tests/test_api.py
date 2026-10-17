import io
import json

import cv2
import numpy as np
from fastapi.testclient import TestClient

from vps import app as server
from vps.matchers import Matches


class GridMatcher:
    """Pretends the same grid of pixels matched in both images."""

    name = "fake"

    def match(self, reference, query):
        g = np.stack(np.meshgrid(np.arange(20, 300, 20.0), np.arange(20, 220, 20.0)), -1).reshape(-1, 2)
        return Matches(reference=g, query=g.copy())


def jpeg(w=320, h=240) -> bytes:
    rng = np.random.default_rng(0)
    return cv2.imencode(".jpg", rng.integers(0, 255, (h, w, 3), dtype=np.uint8))[1].tobytes()


def test_localizes_from_a_multipart_request():
    server.State.matcher = GridMatcher()
    client = TestClient(server.app)
    k = {"fx": 300, "fy": 300, "cx": 160, "cy": 120}
    # Rows vary in depth so PnP has a real 3D structure to work with.
    depth = (np.linspace(1500, 3000, 60)[:, None] * np.ones((60, 80))).astype("<u2").tobytes()
    meta = {"query": k, "refs": [k | {"depth_width": 80, "depth_height": 60}]}

    r = client.post(
        "/localize",
        data={"meta": json.dumps(meta)},
        files={"query": ("q.jpg", io.BytesIO(jpeg())), "ref0": ("r.jpg", io.BytesIO(jpeg())), "ref0_depth": ("d", io.BytesIO(depth))},
    )

    assert r.status_code == 200, r.text
    body = r.json()
    assert body["ok"] and body["best"]["depth"] == "arcore"
    # Same pixels in both images: the camera didn't move.
    np.testing.assert_allclose(body["best"]["pose"]["t"], [0, 0, 0], atol=1e-3)


def test_rejects_missing_reference():
    server.State.matcher = GridMatcher()
    client = TestClient(server.app)
    meta = {"query": {"fx": 1, "fy": 1, "cx": 0, "cy": 0}, "refs": [{"fx": 1, "fy": 1, "cx": 0, "cy": 0}]}
    r = client.post("/localize", data={"meta": json.dumps(meta)}, files={"query": ("q.jpg", io.BytesIO(jpeg()))})
    assert r.status_code == 400
