import numpy as np

from conftest import quat_angle_deg
from vps.geometry import Intrinsics, backproject, decode_depth, rotation_to_quaternion, solve_pose

FLIP = np.diag([1.0, -1.0, -1.0])


def project(points, rotation, center, k: Intrinsics):
    """Pixels of reference-frame points seen by a camera at `center` turned by `rotation` (OpenCV axes)."""
    in_query = (points - center) @ rotation  # rotation.T @ (p - c) for each row
    return np.stack([k.fx * in_query[:, 0] / in_query[:, 2] + k.cx, k.fy * in_query[:, 1] / in_query[:, 2] + k.cy], 1)


def test_recovers_camera_motion_in_arcore_axes(motion):
    center, rotation = motion
    rng = np.random.default_rng(0)
    points = np.stack([rng.uniform(-2, 2, 300), rng.uniform(-1, 1, 300), rng.uniform(2, 6, 300)], 1)
    k = Intrinsics(500, 500, 320, 240)
    pixels = project(points, rotation, center, k) + rng.normal(0, 0.3, (300, 2))

    fix = solve_pose(points, pixels, k, (640, 480))

    assert fix is not None and fix.inliers > 250
    # ARCore: right is +x, forward is -z.
    np.testing.assert_allclose(fix.pose.t, [0.3, 0.0, -0.2], atol=0.02)
    assert quat_angle_deg(fix.pose.q, rotation_to_quaternion(FLIP @ rotation @ FLIP)) < 0.3


def test_rejects_too_few_points():
    k = Intrinsics(500, 500, 320, 240)
    assert solve_pose(np.ones((5, 3)), np.ones((5, 2)), k, (640, 480)) is None


def test_backprojects_through_depth_and_skips_edges():
    k = Intrinsics(100, 100, 50, 40)
    depth = np.full((20, 25), 2.0, dtype=np.float32)
    depth[:, 15:] = 5.0  # a step: pixels near it are unreliable
    keypoints = np.array([[50.0, 40.0], [58.0, 40.0], [90.0, 10.0]])  # center, at the step, far side

    points, mask = backproject(keypoints, depth, (100, 80), k)

    assert mask.tolist() == [True, False, True]
    np.testing.assert_allclose(points[0], [0, 0, 2.0], atol=1e-6)
    np.testing.assert_allclose(points[1], [(90 - 50) / 100 * 5, (10 - 40) / 100 * 5, 5.0], atol=1e-6)


def test_decodes_arcore_depth16():
    raw = np.array([[1000, 2500], [0, 65535]], dtype="<u2").tobytes()
    np.testing.assert_allclose(decode_depth(raw, 2, 2), [[1.0, 2.5], [0.0, 65.535]])
