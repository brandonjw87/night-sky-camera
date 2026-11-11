"""Prints how bright each light's pass is on the letters' faces at some frames (to set the compositor's gains)."""
import sys
import numpy as np
import intro_common as C
import comp
import exr

for f in [int(a) for a in sys.argv[2:]]:
    t = f / C.FPS
    layers = exr.read_layers('%s/f%04d.exr' % (sys.argv[1], f))
    a = layers['ViewLayer.Combined'][..., 3]
    key = layers['ViewLayer.Combined_key'] @ comp.LUMA
    h, w = a.shape
    dirs = C.pixel_dirs(t, w, h)
    sky = dirs[..., 2] > np.sin(np.radians(12.0))
    # the faces: opaque, in the sky part of the frame, and what the key card lights most
    m = (a > 0.99) & sky
    thr = np.percentile(key[m], 60)
    face = m & (key > thr)
    wts = C.weights(t)
    tot = np.zeros(face.sum())
    out = []
    for g in comp.GROUPS:
        v = (layers['ViewLayer.Combined_' + g] @ comp.LUMA)[face] * comp.GAIN[g] * wts.get(g, 1.0)
        tot += v
        out.append('%s %.3f/%.3f' % (g, np.median(v), np.percentile(v, 95)))
    print('frame %d t=%.3f faces %d px: ' % (f, t, face.sum()) + '  '.join(out) + '  | total med %.3f p95 %.3f' % (np.median(tot), np.percentile(tot, 95)))
