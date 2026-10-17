"""Checks running servers end to end, over HTTP, the way the phone calls them.

    uv run --all-extras python scripts/smoke.py http://localhost:8001 http://localhost:8002

Sends a photo seen as a flat wall 2.5 m away, plus the same wall from a camera that moved 30 cm
right, 20 cm closer and turned 5 degrees, then prints how far each server's answer is from that.
"""

import json
import sys
import time
import urllib.request
import uuid
from pathlib import Path

import cv2
import numpy as np

SCENE = Path(__file__).resolve().parents[1] / "tests" / "data" / "scene.jpg"
WALL_M = 2.5
FLIP = np.diag([1.0, -1.0, -1.0])


def rot_y(deg):
    a = np.radians(deg)
    return np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])


def request(url: str, fields: dict[str, bytes]) -> dict:
    boundary = uuid.uuid4().hex
    body = b""
    for name, data in fields.items():
        file = "" if name == "meta" else f'; filename="{name}"'
        body += f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"{file}\r\n\r\n'.encode()
        body += data + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        url.rstrip("/") + "/localize", data=body, headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def main(urls: list[str]):
    image = cv2.imread(str(SCENE))
    h, w = image.shape[:2]
    k = np.array([[0.9 * w, 0, w / 2], [0, 0.9 * w, h / 2], [0, 0, 1]])
    center, rotation = np.array([0.3, 0.0, 0.2]), rot_y(5.0)
    r = rotation.T
    homography = k @ (r + np.outer(-r @ center, [0, 0, 1]) / WALL_M) @ np.linalg.inv(k)
    query = cv2.warpPerspective(image, homography, (w, h))
    depth = (np.full((h // 4, w // 4), WALL_M * 1000)).astype("<u2")
    intr = {"fx": k[0, 0], "fy": k[1, 1], "cx": k[0, 2], "cy": k[1, 2]}
    meta = {"query": intr, "refs": [intr | {"depth_width": w // 4, "depth_height": h // 4}]}
    fields = {
        "meta": json.dumps(meta).encode(),
        "query": cv2.imencode(".jpg", query)[1].tobytes(),
        "ref0": cv2.imencode(".jpg", image)[1].tobytes(),
        "ref0_depth": depth.tobytes(),
    }
    expected = FLIP @ center
    failed = False
    for url in urls:
        start = time.perf_counter()
        try:
            answer = request(url, fields)
        except Exception as e:
            print(f"{url}: FAILED {e}")
            failed = True
            continue
        ms = (time.perf_counter() - start) * 1000
        if not answer["ok"]:
            print(f"{url}: no pose")
            failed = True
            continue
        best = answer["best"]
        error = np.linalg.norm(np.array(best["pose"]["t"]) - expected) * 100
        print(
            f"{url} [{answer['matcher']}]: {best['inliers']} inliers, error {error:.1f} cm, "
            f"server {answer['ms']:.0f} ms, round trip {ms:.0f} ms"
        )
        failed |= error > 10
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main(sys.argv[1:] or ["http://localhost:8001", "http://localhost:8002"])
