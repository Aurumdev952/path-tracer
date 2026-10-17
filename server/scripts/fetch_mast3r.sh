#!/usr/bin/env sh
# Fetches MASt3R (with DUSt3R and CroCo) at a pinned commit into third_party/mast3r.
# It isn't a Python package, so the server adds it to sys.path (see MAST3R_PATH).
set -eu
REV=f5209afc300cec36239a7ac992263f36847bbba0
DEST="${1:-$(dirname "$0")/../third_party/mast3r}"
if [ ! -d "$DEST/.git" ]; then
    git clone --recursive https://github.com/naver/mast3r "$DEST"
fi
git -C "$DEST" fetch --quiet origin "$REV" || true
git -C "$DEST" checkout --quiet "$REV"
git -C "$DEST" submodule update --init --recursive --quiet
echo "MASt3R at $REV in $DEST"
