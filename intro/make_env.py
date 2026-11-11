"""Writes the whole sky's soft glow as an EXR for the 3D scene: what the letters and the water reflect.

    python3 make_env.py OUT.exr
"""
import sys

import exr
import sky

img = sky.equirect(4096, 2048)
exr.write_rgb(sys.argv[1], img)
print('env', img.shape, float(img.mean()), float(img.max()))
