"""MASt3R dense matching. Copes with large view changes and plain scenes, and predicts metric 3D points
of its own, so it also works without phone depth. CC BY-NC-SA 4.0: non-commercial use only.

MASt3R isn't a Python package; MAST3R_PATH points at a checkout made by scripts/fetch_mast3r.sh.
"""

import os
import sys
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from vps.matchers import Matches

CHECKPOINT = os.environ.get("MAST3R_CHECKPOINT", "naver/MASt3R_ViTLarge_BaseDecoder_512_catmlpdpt_metric")
SIZE = 512
BORDER = 3


def _import_mast3r():
    path = Path(os.environ.get("MAST3R_PATH", Path(__file__).resolve().parents[2] / "third_party" / "mast3r"))
    if not (path / "mast3r").is_dir():
        raise RuntimeError(f"MASt3R not found at {path}; run scripts/fetch_mast3r.sh")
    sys.path.insert(0, str(path))
    import mast3r.utils.path_to_dust3r  # noqa: F401  (puts dust3r on sys.path)
    from dust3r.inference import inference
    from mast3r.fast_nn import fast_reciprocal_NNs
    from mast3r.model import AsymmetricMASt3R

    return AsymmetricMASt3R, inference, fast_reciprocal_NNs


class Mast3rMatcher:
    name = "mast3r"

    def __init__(self, device: str):
        model_class, self._inference, self._nearest = _import_mast3r()
        self.device = device
        self.model = model_class.from_pretrained(CHECKPOINT).to(device).eval()

    def _view(self, image: np.ndarray, index: int):
        """Resizes the long side to 512 and center-crops to multiples of 16, as MASt3R was trained.

        Returns the network input and (scale, x offset, y offset) to map pixels back to the original image.
        """
        h, w = image.shape[:2]
        scale = SIZE / max(w, h)
        rw, rh = round(w * scale), round(h * scale)
        resized = Image.fromarray(image).resize((rw, rh), Image.LANCZOS)
        cw, ch = rw // 16 * 16, rh // 16 * 16
        ox, oy = (rw - cw) // 2, (rh - ch) // 2
        crop = np.array(resized)[oy : oy + ch, ox : ox + cw]
        tensor = torch.from_numpy(crop).permute(2, 0, 1).float().div(127.5).sub(1).unsqueeze(0)
        view = {"img": tensor, "true_shape": np.int32([[ch, cw]]), "idx": index, "instance": str(index)}
        return view, (scale, ox, oy)

    @torch.inference_mode()
    def match(self, reference: np.ndarray, query: np.ndarray) -> Matches:
        v0, (s0, ox0, oy0) = self._view(reference, 0)
        v1, (s1, ox1, oy1) = self._view(query, 1)
        out = self._inference([(v0, v1)], self.model, self.device, batch_size=1, verbose=False)
        p0, p1 = out["pred1"], out["pred2"]
        d0 = p0["desc"].squeeze(0).detach()
        d1 = p1["desc"].squeeze(0).detach()
        m0, m1 = self._nearest(d0, d1, subsample_or_initxy1=8, device=self.device, dist="dot", block_size=2**13)

        h0, w0 = v0["true_shape"][0]
        h1, w1 = v1["true_shape"][0]
        keep = (
            (m0[:, 0] >= BORDER) & (m0[:, 0] < w0 - BORDER) & (m0[:, 1] >= BORDER) & (m0[:, 1] < h0 - BORDER)
            & (m1[:, 0] >= BORDER) & (m1[:, 0] < w1 - BORDER) & (m1[:, 1] >= BORDER) & (m1[:, 1] < h1 - BORDER)
        )
        m0, m1 = m0[keep], m1[keep]
        pts3d = p0["pts3d"].squeeze(0).detach().cpu().numpy()
        reference_points = pts3d[m0[:, 1], m0[:, 0]].astype(np.float64)
        # Pixel centers back into original image coordinates.
        ref_px = (m0.astype(np.float64) + 0.5 + [ox0, oy0]) / s0 - 0.5
        query_px = (m1.astype(np.float64) + 0.5 + [ox1, oy1]) / s1 - 0.5
        return Matches(reference=ref_px, query=query_px, reference_points=reference_points)
