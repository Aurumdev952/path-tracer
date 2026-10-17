# Path Tracer localization server

Self-hosted replacement for Google Cloud Anchors, used to find where a route starts from the camera view alone, with no sign needed. Two matchers run side by side behind the same API so they can be compared:

| Service | Port | Matcher | Licence |
| --- | --- | --- | --- |
| `lightglue` | 8001 | [ALIKED](https://github.com/Shiaoming/ALIKED) keypoints + [LightGlue](https://github.com/cvg/LightGlue) | Apache-2.0 |
| `mast3r` | 8002 | [MASt3R](https://github.com/naver/mast3r) dense matching | CC BY-NC-SA 4.0, **non-commercial only** |

## How it works

1. While recording, the phone saves a few **camera views** at the start of each leg: the full camera image, its intrinsics, ARCore's depth image and where the camera was in the leg.
2. While following, the phone sends its current camera image and those views to `POST /localize`.
3. The server finds the same points in both images. The recorder's depth turns each matched point into a 3D point, and PnP (with RANSAC) gives where the follower's camera is relative to the recorded one. Without phone depth, MASt3R can use its own predicted 3D points instead.
4. The answer is a pose in ARCore camera axes. The phone combines it with the saved view pose and its own camera pose to place the route.

## Run with Docker (GPU)

Needs the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html).

```bash
cd server
docker compose up -d --build
curl localhost:8001/health   # {"matcher":"lightglue","ready":true,...}
curl localhost:8002/health   # MASt3R downloads ~2.7 GB of weights on first start
```

Weights are kept in the `hf` and `torch` volumes. To reuse what this machine already downloaded (wheels from uv, model weights from local runs), put this in `server/.env`:

```
UV_HOST_CACHE=/home/you/.cache/uv
HF_HOST_CACHE=/home/you/.cache/huggingface
TORCH_HOST_CACHE=/home/you/.cache/torch
```

If `docker run --gpus all` fails with `libEGL_nvidia.so.<old version>: no such file`, the CDI spec is stale after a driver update. Regenerate it with `sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml`.

## Run locally with uv

```bash
cd server
uv sync --all-extras
./scripts/fetch_mast3r.sh                  # MASt3R isn't a package; this pins a checkout in third_party/
MATCHER=lightglue uv run uvicorn vps.app:app --port 8001
MATCHER=mast3r    uv run uvicorn vps.app:app --port 8002
uv run pytest -s                           # matcher tests need the GPU and print accuracy and timing
```

## Connect the phone

- **USB:** `adb reverse tcp:8001 tcp:8001 && adb reverse tcp:8002 tcp:8002`, then keep the app's default URLs (`http://127.0.0.1:8001` and `:8002`).
- **Wi-Fi:** use the laptop's IP, like `http://192.168.1.20:8001`.

In the app, turn on debug mode (long-press the title), tap **Localization server**, pick LightGlue, MASt3R or Both, and test the connection. Then:

- **Recording** saves camera views for every leg, and **No good sign? Use the scene** starts a leg with no sign at all.
- **Following** asks the server(s) about once a second while looking for a stretch's start and locks on when two answers agree (or one is very confident). In debug mode it keeps asking for 20 s after locking on and logs `vps ... vs_lock_m=` and `vs_lock_deg=`: how far each server's answer is from the lock-on. When the lock came from a sign, that is the server's error.

## API

`POST /localize`, multipart form:

| Field | Content |
| --- | --- |
| `meta` | JSON: `{"query": {fx, fy, cx, cy}, "refs": [{fx, fy, cx, cy, depth_width?, depth_height?}], "depth_source": "auto" \| "arcore" \| "model"}` |
| `query` | JPEG of the phone's camera image |
| `ref0`, `ref1`, … | JPEG of each recorded view |
| `ref0_depth`, … | Optional raw ARCore DEPTH16: little-endian uint16 millimeters, rows tightly packed |

Intrinsics are in pixels of the uploaded images. The answer has `ok`, and in `best`: `ref` (which view matched), `pose` (`t` in meters and `q` as x, y, z, w, for the query camera in that view's camera frame, ARCore axes), `inliers`, `reprojection_px` and `depth` (where the 3D came from). `attempts` lists every view tried.

`GET /health` says which matcher runs, on which GPU, and whether it has finished loading.
