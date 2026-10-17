"""Where is the query camera, relative to one of several recorded reference views?"""

import time
from dataclasses import dataclass

import cv2
import numpy as np

from vps.geometry import Intrinsics, PoseFix, backproject, decode_depth, solve_pose
from vps.matchers import Matcher


@dataclass
class Reference:
    image: np.ndarray  # RGB
    k: Intrinsics
    depth_m: np.ndarray | None  # same view as the image, any resolution


@dataclass
class Attempt:
    reference: int
    matches: int
    depth: str  # "arcore", "model" or "none": where the reference points' 3D came from
    fix: PoseFix | None
    ms: float

    def as_json(self) -> dict:
        out = {"ref": self.reference, "matches": self.matches, "depth": self.depth, "ms": round(self.ms, 1)}
        if self.fix:
            out |= {
                "pose": self.fix.pose.as_json(),
                "inliers": self.fix.inliers,
                "reprojection_px": round(self.fix.reprojection_px, 2),
            }
        return out


def decode_image(data: bytes) -> np.ndarray:
    bgr = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if bgr is None:
        raise ValueError("not a JPEG or PNG image")
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


def reference_from(image: bytes, meta: dict, depth: bytes | None) -> Reference:
    d = None
    if depth is not None:
        d = decode_depth(depth, int(meta["depth_width"]), int(meta["depth_height"]))
    return Reference(decode_image(image), intrinsics(meta), d)


def intrinsics(meta: dict) -> Intrinsics:
    return Intrinsics(float(meta["fx"]), float(meta["fy"]), float(meta["cx"]), float(meta["cy"]))


def localize(matcher: Matcher, query: np.ndarray, query_k: Intrinsics, refs: list[Reference], depth_source: str = "auto"):
    """Tries every reference and returns (best attempt or None, all attempts).

    depth_source: "arcore" uses the phone's depth only, "model" the matcher's own 3D only,
    "auto" the phone's depth when the reference has it, else the model's.
    """
    attempts = []
    qh, qw = query.shape[:2]
    for i, ref in enumerate(refs):
        start = time.perf_counter()
        m = matcher.match(ref.image, query)
        points = pixels = None
        source = "none"
        if ref.depth_m is not None and depth_source in ("auto", "arcore"):
            rh, rw = ref.image.shape[:2]
            points, mask = backproject(m.reference, ref.depth_m, (rw, rh), ref.k)
            pixels = m.query[mask]
            source = "arcore"
        elif m.reference_points is not None and depth_source in ("auto", "model"):
            points, pixels = m.reference_points, m.query
            source = "model"
        fix = solve_pose(points, pixels, query_k, (qw, qh)) if points is not None else None
        attempts.append(Attempt(i, len(m.query), source, fix, (time.perf_counter() - start) * 1000))
    solved = [a for a in attempts if a.fix]
    best = max(solved, key=lambda a: a.fix.inliers, default=None)
    return best, attempts
