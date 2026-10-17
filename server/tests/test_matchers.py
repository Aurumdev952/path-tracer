"""Real images: a photo seen as a flat wall 2.5 m away, and the same wall from a camera that moved.

The query image is the reference warped by the plane's homography, so the true motion is known.
"""

import os
from pathlib import Path

import cv2
import numpy as np
import pytest
import torch

from conftest import quat_angle_deg
from vps.geometry import Intrinsics, rotation_to_quaternion
from vps.localize import Reference, localize
from vps.matchers import load

SCENE = Path(__file__).parent / "data" / "scene.jpg"
WALL_M = 2.5
FLIP = np.diag([1.0, -1.0, -1.0])
MAST3R = Path(os.environ.get("MAST3R_PATH", Path(__file__).resolve().parents[1] / "third_party" / "mast3r"))

needs_gpu = pytest.mark.skipif(not torch.cuda.is_available(), reason="needs a CUDA GPU")


def scene_pair(center, rotation):
    image = cv2.cvtColor(cv2.imread(str(SCENE)), cv2.COLOR_BGR2RGB)
    h, w = image.shape[:2]
    k = Intrinsics(0.9 * w, 0.9 * w, w / 2, h / 2)
    r = rotation.T  # reference camera points into the query camera: x_q = r x + t
    t = -r @ center
    homography = k.matrix @ (r + np.outer(t, [0, 0, 1]) / WALL_M) @ np.linalg.inv(k.matrix)
    query = cv2.warpPerspective(image, homography, (w, h))
    depth = np.full((h // 4, w // 4), WALL_M, dtype=np.float32)
    return Reference(image, k, depth), query, k


@pytest.fixture(scope="module")
def lightglue():
    return load("lightglue", "cuda")


@pytest.fixture(scope="module")
def mast3r():
    if not MAST3R.joinpath("mast3r").is_dir():
        pytest.skip("MASt3R not fetched; run scripts/fetch_mast3r.sh")
    return load("mast3r", "cuda")


def check(best, center, rotation, tol_m, tol_deg):
    assert best is not None, "no pose"
    expected_t = FLIP @ center
    expected_q = rotation_to_quaternion(FLIP @ rotation @ FLIP)
    error_m = float(np.linalg.norm(np.asarray(best.fix.pose.t) - expected_t))
    error_deg = quat_angle_deg(best.fix.pose.q, expected_q)
    print(f"{best.depth}: {best.fix.inliers}/{best.matches} inliers, {error_m * 100:.1f} cm, {error_deg:.2f} deg, {best.ms:.0f} ms")
    assert error_m < tol_m and error_deg < tol_deg


@needs_gpu
def test_lightglue_with_phone_depth(lightglue, motion):
    ref, query, k = scene_pair(*motion)
    best, _ = localize(lightglue, query, k, [ref])
    check(best, *motion, tol_m=0.05, tol_deg=1.0)


@needs_gpu
def test_mast3r_with_phone_depth(mast3r, motion):
    ref, query, k = scene_pair(*motion)
    best, _ = localize(mast3r, query, k, [ref])
    check(best, *motion, tol_m=0.08, tol_deg=1.5)


@needs_gpu
def test_mast3r_without_phone_depth_still_answers(mast3r, motion):
    ref, query, k = scene_pair(*motion)
    ref.depth_m = None
    best, _ = localize(mast3r, query, k, [ref])
    # Its own 3D sees the room in the photo, not a flat wall, so only check it answers.
    assert best is not None and best.depth == "model"


@needs_gpu
def test_picks_the_reference_that_matches(lightglue, motion):
    ref, query, k = scene_pair(*motion)
    noise = Reference(np.random.default_rng(1).integers(0, 255, ref.image.shape, dtype=np.uint8), k, ref.depth_m)
    best, attempts = localize(lightglue, query, k, [noise, ref])
    assert best.reference == 1 and len(attempts) == 2
