# syntax=docker/dockerfile:1.7
# One image per matcher: docker build --build-arg MATCHER=lightglue|mast3r .
# The heavy shared layer (PyTorch with CUDA) comes first so both images reuse it.
FROM python:3.12-slim-bookworm

COPY --from=ghcr.io/astral-sh/uv:0.12.5 /uv /uvx /bin/
RUN apt-get update \
    && apt-get install -y --no-install-recommends git libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

ENV UV_LINK_MODE=copy \
    UV_COMPILE_BYTECODE=1 \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    UV_CACHE_DIR=/uvcache
WORKDIR /app
COPY pyproject.toml uv.lock ./

# The "uvcache" build context is an empty folder by default. Point it at a host uv cache
# (UV_HOST_CACHE=~/.cache/uv docker compose build) to reuse wheels already downloaded there.
RUN --mount=type=bind,from=uvcache,target=/uvcache,rw \
    uv sync --frozen --no-dev --no-install-project

ARG MATCHER=lightglue
RUN --mount=type=bind,from=uvcache,target=/uvcache,rw \
    uv sync --frozen --no-dev --no-install-project --extra ${MATCHER}

COPY scripts ./scripts
RUN if [ "${MATCHER}" = "mast3r" ]; then ./scripts/fetch_mast3r.sh /opt/mast3r; fi

COPY vps ./vps
RUN --mount=type=bind,from=uvcache,target=/uvcache,rw \
    uv sync --frozen --no-dev --extra ${MATCHER}

# Model weights download on first start into /models; mount a volume there to keep them.
ENV MATCHER=${MATCHER} \
    MAST3R_PATH=/opt/mast3r \
    HF_HOME=/models/hf \
    TORCH_HOME=/models/torch \
    PATH=/opt/venv/bin:$PATH
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10m \
    CMD python -c "import json,sys,urllib.request; sys.exit(0 if json.load(urllib.request.urlopen('http://localhost:8000/health'))['ready'] else 1)"
CMD ["uvicorn", "vps.app:app", "--host", "0.0.0.0", "--port", "8000"]
