"""Writes one light's pass at some frames as a strip of crops around the name (display curve, fixed gain)."""
import sys
import numpy as np
from PIL import Image
import exr
import comp
src, group, gain, out = sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4]
tiles = []
for f in [int(a) for a in sys.argv[5:]]:
    layers = exr.read_layers('%s/f%04d.exr' % (src, f))
    img = layers['ViewLayer.Combined_' + group][650:1250] * gain
    y = comp.film(img)
    tiles.append((np.clip(y, 0, 1) * 255 + 0.5).astype(np.uint8)[::2, ::2])
Image.fromarray(np.concatenate(tiles, axis=0)).save(out)
