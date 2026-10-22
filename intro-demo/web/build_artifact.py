#!/usr/bin/env python3
"""Packs the intro into one self-contained page (shaders, SDF textures and sound inlined).

The name fields are stored as 16-bit fixed point, delta coded along rows and split into byte
planes before gzip, which roughly halves their size compared with gzipping the raw half floats.
The page decodes them back to floats and uploads them as R16F, exactly like the Android build.

usage: build_artifact.py OUT.html [--standalone]

Without --standalone the output is page content for the Artifact host, which wraps it in a document
skeleton. --standalone adds the same skeleton, for a file that opens straight in a browser.
"""
import base64
import gzip
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
SHADERS = ["common.glsl", "fullscreen.vert", "scene.frag", "particles.vert", "particles.frag", "bright.frag",
           "down.frag", "up.frag", "streak.frag", "rays.frag", "final.frag"]


# What the Artifact host wraps around a page: charset, viewport and a small reset (the page's icons rely on
# its [hidden] rule, which SVG elements don't get from the browser).
SKELETON = ('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
            '<style>:root{color-scheme:light;padding-top:env(safe-area-inset-top,0px);'
            'padding-bottom:env(safe-area-inset-bottom,0px)}body{margin:0;font:14px system-ui}'
            'img{max-width:100%}[hidden]{display:none!important}</style>\n</head>\n<body>\n')


def b64(data):
    return base64.b64encode(data).decode("ascii")


def pack_field(path, w, h):
    v = np.fromfile(path, dtype=np.float16).reshape(h, w).astype(np.float32)
    q = np.round(np.clip(v, -1.0, 1.0) * 32767.0).astype(np.int32)
    d = np.diff(q, axis=1, prepend=0).astype(np.int16)          # wraps modulo 2^16, decoder wraps too
    pairs = d.astype("<i2").view(np.uint8).reshape(-1, 2)
    planes = pairs[:, 0].tobytes() + pairs[:, 1].tobytes()
    return b64(gzip.compress(planes, 9, mtime=0))


def main(out_path, standalone=False):
    man = json.load(open(os.path.join(ROOT, "assets", "sdf_manifest.json")))
    shaders = {s: open(os.path.join(ROOT, "shaders", s)).read() for s in SHADERS}
    P, L, LB = man["portrait"], man["landscape"], man["labels"]
    data = {
        "portrait": pack_field(os.path.join(ROOT, "assets", "name_portrait.r16f"), P["w"], P["h"]),
        "landscape": pack_field(os.path.join(ROOT, "assets", "name_landscape.r16f"), L["w"], L["h"]),
        "labels": b64(gzip.compress(open(os.path.join(ROOT, "assets", "labels.r8"), "rb").read(), 9, mtime=0)),
        "sound": b64(open(os.path.join(ROOT, "assets", "intro_sound.ogg"), "rb").read()),
    }
    page = open(os.path.join(HERE, "artifact_template.html")).read()
    page = page.replace("/*__SHADERS__*/null", json.dumps(shaders))
    page = page.replace("/*__MANIFEST__*/null", json.dumps(man, separators=(",", ":")))
    page = page.replace("/*__DATA__*/null", json.dumps(data, separators=(",", ":")))
    for marker in ("__SHADERS__", "__MANIFEST__", "__DATA__"):
        assert marker not in page, marker
    if standalone:
        page = SKELETON + page + "\n</body>\n</html>\n"
    with open(out_path, "w") as fh:
        fh.write(page)
    sizes = {k: len(v) for k, v in data.items()}
    print("wrote", out_path, len(page), "bytes", sizes)


if __name__ == "__main__":
    main(sys.argv[1], "--standalone" in sys.argv[2:])
