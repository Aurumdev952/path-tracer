"""ALIKED keypoints matched with LightGlue. Fast and precise on textured scenes. Apache-2.0."""

import numpy as np
import torch
from lightglue import ALIKED, LightGlue
from lightglue.utils import rbd

from vps.matchers import Matches


class LightGlueMatcher:
    name = "lightglue"

    def __init__(self, device: str, max_keypoints: int = 2048, resize: int = 1024):
        self.device = device
        self.resize = resize
        self.extractor = ALIKED(max_num_keypoints=max_keypoints).eval().to(device)
        self.matcher = LightGlue(features="aliked").eval().to(device)

    def _tensor(self, image: np.ndarray) -> torch.Tensor:
        return torch.from_numpy(image).permute(2, 0, 1).float().div(255).to(self.device)

    @torch.inference_mode()
    def match(self, reference: np.ndarray, query: np.ndarray) -> Matches:
        f0 = self.extractor.extract(self._tensor(reference), resize=self.resize)
        f1 = self.extractor.extract(self._tensor(query), resize=self.resize)
        m = self.matcher({"image0": f0, "image1": f1})
        f0, f1, m = rbd(f0), rbd(f1), rbd(m)
        pairs = m["matches"]
        return Matches(
            reference=f0["keypoints"][pairs[:, 0]].cpu().numpy(),
            query=f1["keypoints"][pairs[:, 1]].cpu().numpy(),
        )
