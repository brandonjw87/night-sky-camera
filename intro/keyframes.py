"""Composes a list of frames (for looking at before the whole film is composed).

    python3 keyframes.py RENDER_DIR OUT_DIR frame [frame ...]
"""
import os
import sys
import time

from PIL import Image

import intro_common as C
import comp
import exr

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
for f in [int(a) for a in sys.argv[3:]]:
    t0 = time.time()
    layers = exr.read_layers(os.path.join(src, 'f%04d.exr' % f))
    h, w = layers['ViewLayer.Combined'].shape[:2]
    img = comp.compose(f / C.FPS, layers, w, h)
    Image.fromarray(comp.finish(img, f)).save(os.path.join(dst, 'f%04d.png' % f))
    print('frame', f, '%.1fs' % (time.time() - t0), flush=True)
