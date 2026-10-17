"""HTTP API. One matcher per process, picked by the MATCHER environment variable.

POST /localize (multipart form)
  meta   JSON: {"query": {fx, fy, cx, cy},
                "refs": [{fx, fy, cx, cy, depth_width?, depth_height?}, ...],
                "depth_source": "auto" | "arcore" | "model"}
  query  JPEG of the phone's current camera image
  ref0.. JPEG of each recorded view; ref0_depth.. raw ARCore DEPTH16 (optional)
  Intrinsics are in pixels of the uploaded images.

The answer's pose is where the query camera is inside the best reference camera's frame, in ARCore
camera axes (x right, y up, z backward), meters.
"""

import json
import logging
import os
import threading
import time
from contextlib import asynccontextmanager

import numpy as np
import torch
from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from starlette.datastructures import UploadFile

from vps import matchers
from vps.localize import decode_image, intrinsics, localize, reference_from

log = logging.getLogger("vps")
MATCHER = os.environ.get("MATCHER", "lightglue")
DEVICE = os.environ.get("DEVICE", "cuda" if torch.cuda.is_available() else "cpu")
MAX_REFS = 8


class State:
    matcher: matchers.Matcher | None = None
    error: str | None = None
    loaded_in_s: float | None = None
    gpu = threading.Lock()


def _load():
    start = time.perf_counter()
    try:
        matcher = matchers.load(MATCHER, DEVICE)
        # One throwaway match so CUDA kernels are ready before the first real request.
        texture = np.random.default_rng(0).integers(0, 255, (480, 640, 3), dtype=np.uint8)
        matcher.match(texture, texture)
        State.matcher = matcher
        State.loaded_in_s = round(time.perf_counter() - start, 1)
        log.warning("%s ready on %s in %.1fs", MATCHER, DEVICE, State.loaded_in_s)
    except Exception as e:  # reported by /health
        State.error = f"{type(e).__name__}: {e}"
        log.exception("loading %s failed", MATCHER)


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Loading can take a while (MASt3R downloads ~2.7 GB once), so /health answers meanwhile.
    threading.Thread(target=_load, daemon=True).start()
    yield


app = FastAPI(title="Path Tracer VPS", lifespan=lifespan)


@app.get("/health")
def health():
    return {
        "matcher": MATCHER,
        "ready": State.matcher is not None,
        "error": State.error,
        "device": DEVICE,
        "gpu": torch.cuda.get_device_name(0) if DEVICE.startswith("cuda") else None,
        "loaded_in_s": State.loaded_in_s,
    }


@app.post("/localize")
async def post_localize(request: Request):
    if State.matcher is None:
        raise HTTPException(503, State.error or f"{MATCHER} is still loading")
    form = await request.form()
    try:
        raw = form["meta"]
        meta = json.loads(await raw.read() if isinstance(raw, UploadFile) else raw)
        query = decode_image(await _file(form, "query"))
        refs_meta = meta["refs"][:MAX_REFS]
        refs = []
        for i, ref_meta in enumerate(refs_meta):
            depth = await _file(form, f"ref{i}_depth") if f"ref{i}_depth" in form else None
            refs.append(reference_from(await _file(form, f"ref{i}"), ref_meta, depth))
        query_k = intrinsics(meta["query"])
        depth_source = meta.get("depth_source", "auto")
    except (KeyError, ValueError, TypeError) as e:
        raise HTTPException(400, f"bad request: {e}") from e
    if not refs:
        raise HTTPException(400, "no reference views")

    def run():
        with State.gpu:
            return localize(State.matcher, query, query_k, refs, depth_source)

    start = time.perf_counter()
    best, attempts = await run_in_threadpool(run)
    return {
        "ok": best is not None,
        "matcher": MATCHER,
        "best": best.as_json() if best else None,
        "attempts": [a.as_json() for a in attempts],
        "ms": round((time.perf_counter() - start) * 1000, 1),
    }


async def _file(form, name: str) -> bytes:
    item = form[name]
    if not isinstance(item, UploadFile):
        raise ValueError(f"{name} must be a file")
    return await item.read()
