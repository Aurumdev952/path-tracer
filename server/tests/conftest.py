import numpy as np
import pytest


def rot_y(deg: float) -> np.ndarray:
    a = np.radians(deg)
    return np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])


def quat_angle_deg(q1, q2) -> float:
    """Angle between two rotations given as quaternions (x, y, z, w)."""
    d = abs(float(np.dot(np.asarray(q1) / np.linalg.norm(q1), np.asarray(q2) / np.linalg.norm(q2))))
    return float(np.degrees(2 * np.arccos(min(1.0, d))))


@pytest.fixture
def motion():
    """The query camera, in the reference camera's OpenCV frame: 30 cm right, 20 cm closer, turned 5 degrees."""
    center = np.array([0.3, 0.0, 0.2])
    rotation = rot_y(5.0)
    return center, rotation
