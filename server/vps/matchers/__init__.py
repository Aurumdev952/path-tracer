"""Image matchers. Each finds the same points in a reference and a query image."""

from dataclasses import dataclass
from typing import Protocol

import numpy as np


@dataclass(frozen=True)
class Matches:
    reference: np.ndarray  # (N, 2) pixels in the reference image
    query: np.ndarray  # (N, 2) pixels in the query image
    # (N, 3) the model's own metric 3D estimate of each reference point (OpenCV camera axes), if it makes one.
    reference_points: np.ndarray | None = None


class Matcher(Protocol):
    name: str

    def match(self, reference: np.ndarray, query: np.ndarray) -> Matches:
        """Both images are RGB uint8 arrays (H, W, 3)."""
        ...


def load(name: str, device: str) -> Matcher:
    if name == "lightglue":
        from vps.matchers.lightglue import LightGlueMatcher

        return LightGlueMatcher(device)
    if name == "mast3r":
        from vps.matchers.mast3r import Mast3rMatcher

        return Mast3rMatcher(device)
    raise ValueError(f"unknown matcher {name!r}, expected lightglue or mast3r")
