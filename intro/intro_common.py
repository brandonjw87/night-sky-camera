"""Shared numbers for the intro: frame size, timing, the camera's path and the lighting's timeline.

World: metres, Z up, the ground near z = 0. The name floats in the sky, tilted to face the place the camera ends at.
The camera looks along its -Z with +Y up (Blender's convention); cam_pose() gives its position and its axes.
"""
import math

import numpy as np

W, H = 1080, 2400
FPS = 60
DUR = 2.2
NF = int(round(DUR * FPS))  # 132 frames
HFOV = math.radians(37.0)
VFOV = 2.0 * math.atan(math.tan(HFOV / 2.0) * H / W)
T_FLASH = 0.56  # the flash fires

# the lettering block: its centre in the world, how far it leans back (it faces the camera's last place)
BLOCK_CENTRE = np.array([0.0, 0.0, 13.9])
BLOCK_WIDTH = 10.0
BLOCK_LEAN = math.radians(35.0)


def smooth(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * (3.0 - 2.0 * x)


def ease_out(x, p):
    x = min(max(x, 0.0), 1.0)
    return 1.0 - (1.0 - x) ** p


def block_axes():
    """The lettering's right, up and facing (towards the viewer) directions in the world."""
    right = np.array([1.0, 0.0, 0.0])
    up = np.array([0.0, math.sin(BLOCK_LEAN), math.cos(BLOCK_LEAN)])
    facing = np.array([0.0, -math.cos(BLOCK_LEAN), -math.sin(BLOCK_LEAN)])
    return right, up, facing


def cam_pose(t):
    """Camera position, and its right / up / back axes (columns of its rotation), at time t seconds."""
    u = min(max(t / DUR, 0.0), 1.0)
    # a fast swing round that settles (most of it done by the flash), then a slow drift in to the end
    s = 0.80 * ease_out(u / 0.62, 2.4) + 0.20 * smooth(u)
    az = math.radians(-33.0 + 30.5 * s)          # where the camera stands, seen from the block (0 = straight in front)
    dist = 19.6 - 2.3 * s + 0.0                  # along the ground
    height = 1.15 + 0.75 * s
    pos = np.array([dist * math.sin(az), -dist * math.cos(az), height])
    # aim: a point below the block's centre, so the block sits above the middle of the frame
    aim = BLOCK_CENTRE + np.array([0.35 * (1.0 - s), 0.0, -3.05 + 0.25 * (1.0 - s)])
    fwd = aim - pos
    fwd /= np.linalg.norm(fwd)
    roll = math.radians(-5.0 * (1.0 - s))
    zup = np.array([0.0, 0.0, 1.0])
    right = np.cross(fwd, zup)
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    cr, sr = math.cos(roll), math.sin(roll)
    right2 = cr * right + sr * up
    up2 = -sr * right + cr * up
    return pos, right2, up2, -fwd


def project(points, t, w=W, h=H):
    """World points (N, 3) to pixel x, y (y down) and depth along the view axis at time t."""
    pos, right, up, back = cam_pose(t)
    d = np.asarray(points, dtype=np.float64) - pos
    xc = d @ right
    yc = d @ up
    zc = -(d @ back)
    f = (w / 2.0) / math.tan(HFOV / 2.0)
    zs = np.where(zc > 1e-6, zc, 1e-6)
    return w / 2.0 + f * xc / zs, h / 2.0 - f * yc / zs, zc


def pixel_dirs(t, w=W, h=H):
    """Unit view directions in the world for every pixel centre, shape (h, w, 3)."""
    pos, right, up, back = cam_pose(t)
    f = (w / 2.0) / math.tan(HFOV / 2.0)
    xs = (np.arange(w) + 0.5 - w / 2.0) / f
    ys = (h / 2.0 - (np.arange(h) + 0.5)) / f
    X, Y = np.meshgrid(xs, ys)
    d = X[..., None] * right + Y[..., None] * up - back
    d /= np.linalg.norm(d, axis=-1, keepdims=True)
    return d


def flash(t):
    """The flash tube's light, 1 at its peak: instant on, a fast fall and a short tail."""
    x = t - T_FLASH
    if x < -1.0 / 120.0:
        return 0.0
    if x < 0.0:
        return 0.5
    return 0.86 * math.exp(-x / 0.045) + 0.14 * math.exp(-x / 0.22)


def weights(t):
    """How strongly each light group counts at time t (the picture is their weighted sum)."""
    x = t - T_FLASH
    after = smooth(x / 0.34) if x > 0 else 0.0     # the lit look the name settles into
    return {
        'env': 1.0,
        'rim': 1.0 - 0.25 * after,
        'flash': flash(t),
        'key': 0.035 + 0.965 * after,
        'sweep': 1.0,
        'made': 0.55 + 0.45 * after + 0.8 * flash(t),
    }
