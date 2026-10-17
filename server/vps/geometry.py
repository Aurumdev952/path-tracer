"""Camera geometry shared by every matcher.

Conventions:
- Pixel coordinates are (x, y) in the uploaded image, origin top-left.
- Intrinsics (fx, fy, cx, cy) are in pixels of that same image.
- OpenCV camera axes: x right, y down, z forward. ARCore camera axes: x right, y up, z backward.
  Results are returned in ARCore's convention so the phone can compose them with ARCore poses.
"""

from dataclasses import dataclass

import cv2
import numpy as np

# ARCore camera axes from OpenCV ones: flip y and z.
_FLIP = np.diag([1.0, -1.0, -1.0])

MIN_DEPTH_M = 0.2
MAX_DEPTH_M = 12.0
MIN_INLIERS = 15


@dataclass(frozen=True)
class Intrinsics:
    fx: float
    fy: float
    cx: float
    cy: float

    @property
    def matrix(self) -> np.ndarray:
        return np.array([[self.fx, 0, self.cx], [0, self.fy, self.cy], [0, 0, 1]], dtype=np.float64)


@dataclass(frozen=True)
class Pose:
    """Where the query camera is inside the reference camera's frame, in ARCore camera axes."""

    t: np.ndarray  # (3,) meters
    q: np.ndarray  # (4,) quaternion x, y, z, w

    def as_json(self) -> dict:
        return {"t": [float(v) for v in self.t], "q": [float(v) for v in self.q]}


@dataclass(frozen=True)
class PoseFix:
    pose: Pose
    inliers: int
    matches: int
    reprojection_px: float


def decode_depth(raw: bytes, width: int, height: int) -> np.ndarray:
    """ARCore DEPTH16: little-endian uint16 millimeters, rows tightly packed. Returns meters."""
    expected = width * height * 2
    if len(raw) != expected:
        raise ValueError(f"depth has {len(raw)} bytes, expected {expected} for {width}x{height}")
    return np.frombuffer(raw, dtype="<u2").reshape(height, width).astype(np.float32) / 1000.0


def backproject(
    keypoints: np.ndarray, depth_m: np.ndarray, image_size: tuple[int, int], k: Intrinsics
) -> tuple[np.ndarray, np.ndarray]:
    """3D points (OpenCV camera axes) for keypoints of an image, from a depth map covering the same view.

    Returns (points, mask): points for the keypoints where the depth is usable, and which ones they are.
    Depth at object edges is unreliable, so a keypoint whose 3x3 depth neighborhood disagrees is dropped.
    """
    w, h = image_size
    dh, dw = depth_m.shape
    u = np.clip(np.round((keypoints[:, 0] + 0.5) * dw / w - 0.5).astype(int), 1, dw - 2)
    v = np.clip(np.round((keypoints[:, 1] + 0.5) * dh / h - 0.5).astype(int), 1, dh - 2)
    windows = np.stack([depth_m[v + dy, u + dx] for dy in (-1, 0, 1) for dx in (-1, 0, 1)], axis=1)
    d = np.median(windows, axis=1)
    spread = windows.max(axis=1) - windows.min(axis=1)
    mask = (d > MIN_DEPTH_M) & (d < MAX_DEPTH_M) & (windows.min(axis=1) > 0) & (spread < 0.1 * d)
    kp = keypoints[mask]
    d = d[mask]
    points = np.stack([(kp[:, 0] - k.cx) / k.fx * d, (kp[:, 1] - k.cy) / k.fy * d, d], axis=1)
    return points, mask


def solve_pose(points: np.ndarray, pixels: np.ndarray, k: Intrinsics, image_size: tuple[int, int]) -> PoseFix | None:
    """Pose of the query camera from reference-camera 3D points and where they appear in the query image."""
    if len(points) < MIN_INLIERS:
        return None
    threshold = 0.008 * max(image_size)
    ok, rvec, tvec, inliers = cv2.solvePnPRansac(
        points.astype(np.float64),
        pixels.astype(np.float64),
        k.matrix,
        None,
        iterationsCount=2000,
        reprojectionError=threshold,
        confidence=0.999,
        flags=cv2.SOLVEPNP_SQPNP,
    )
    if not ok or inliers is None or len(inliers) < MIN_INLIERS:
        return None
    idx = inliers[:, 0]
    rvec, tvec = cv2.solvePnPRefineLM(points[idx], pixels[idx], k.matrix, None, rvec, tvec)
    projected, _ = cv2.projectPoints(points[idx], rvec, tvec, k.matrix, None)
    error = float(np.sqrt(np.mean(np.sum((projected[:, 0, :] - pixels[idx]) ** 2, axis=1))))

    # PnP maps reference-camera points into the query camera: x_q = R x_r + t.
    r, _ = cv2.Rodrigues(rvec)
    rotation = r.T
    translation = -r.T @ tvec[:, 0]
    return PoseFix(
        pose=Pose(t=_FLIP @ translation, q=rotation_to_quaternion(_FLIP @ rotation @ _FLIP)),
        inliers=int(len(idx)),
        matches=int(len(points)),
        reprojection_px=error,
    )


def rotation_to_quaternion(r: np.ndarray) -> np.ndarray:
    """Rotation matrix to unit quaternion (x, y, z, w)."""
    m = r
    trace = m[0, 0] + m[1, 1] + m[2, 2]
    if trace > 0:
        s = 2.0 * np.sqrt(trace + 1.0)
        q = [(m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s, 0.25 * s]
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = 2.0 * np.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2])
        q = [0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s, (m[2, 1] - m[1, 2]) / s]
    elif m[1, 1] > m[2, 2]:
        s = 2.0 * np.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2])
        q = [(m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s, (m[0, 2] - m[2, 0]) / s]
    else:
        s = 2.0 * np.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1])
        q = [(m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s, (m[1, 0] - m[0, 1]) / s]
    q = np.array(q)
    return q / np.linalg.norm(q)
