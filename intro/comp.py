"""Puts a frame together: the sky behind, the rendered lettering and ground with each light weighted for the
moment, dust in the flash, the lens's glare, then exposure, the film curve and grain.

    python3 comp.py RENDER_DIR OUT_DIR [first last]      writes OUT_DIR/f0000.png ...
"""
import math
import os
import sys

import numpy as np
from PIL import Image
from scipy import ndimage

import intro_common as C
import exr
import sky

GROUPS = ['env', 'rim', 'flash', 'key', 'sweep', 'made']
# each light's strength in the picture (on top of the timeline's weights)
GAIN = {'env': 1.0, 'rim': 0.50, 'flash': 5.0, 'key': 2.5, 'sweep': 1.25, 'made': 0.85}
GROUND_FLASH = 0.055      # the flash on the shore, against its strength on the name
LUMA = np.array([0.2126, 0.7152, 0.0722], np.float32)


def film(x):
    """Scene light to display: a filmic shoulder and toe (the ACES fit), then the sRGB curve."""
    x = np.clip(x, 0.0, None)
    y = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)
    y = np.clip(y, 0.0, 1.0)
    return np.where(y <= 0.0031308, 12.92 * y, 1.055 * np.power(y, 1.0 / 2.4) - 0.055)


def gauss(img, sigma):
    out = np.empty_like(img)
    for c in range(img.shape[-1]):
        out[..., c] = ndimage.gaussian_filter(img[..., c], sigma, mode='nearest')
    return out


def halve(x):
    x = x[: x.shape[0] // 2 * 2, : x.shape[1] // 2 * 2]
    return 0.25 * (x[0::2, 0::2] + x[1::2, 0::2] + x[0::2, 1::2] + x[1::2, 1::2])


def grow(x, w, h):
    return np.stack([np.asarray(Image.fromarray(np.ascontiguousarray(x[..., c])).resize((w, h), Image.BILINEAR))
                     for c in range(x.shape[-1])], axis=-1)


def bloom(hot, scale, widths):
    """The lens's soft glow: the bright part of the picture spread over several widths (sigma px, weight)."""
    h, w = hot.shape[:2]
    out = np.zeros_like(hot)
    small = hot
    f = 1
    for sigma, wt in widths:
        s = sigma * scale
        while s / f > 4.0 and f < 32:
            small = halve(small)
            f *= 2
        b = gauss(small, s / f)
        out += wt * (grow(b, w, h) if f > 1 else b)
    return out


def streak(hot, scale, length):
    """A wide lens's sideways streak through the brightest light."""
    h, w = hot.shape[:2]
    small = halve(halve(hot))
    k = max(int(length * scale / 4.0), 2)
    x = np.arange(-3 * k, 3 * k + 1)
    kern = np.exp(-np.abs(x) / float(k))
    kern /= kern.sum()
    out = np.empty_like(small)
    for c in range(3):
        out[..., c] = ndimage.convolve1d(small[..., c], kern, axis=1, mode='constant')
        out[..., c] = ndimage.gaussian_filter1d(out[..., c], 0.8, axis=0)
    return grow(out, w, h)


# dust in the air near the camera: only the flash shows it
_rng = np.random.default_rng(404)
N_MOTES = 420
MOTES = np.stack([_rng.uniform(-10.0, 7.0, N_MOTES), _rng.uniform(-18.5, -4.0, N_MOTES), _rng.uniform(0.6, 12.0, N_MOTES)], axis=1)
MOTE_GLOW = _rng.uniform(0.35, 1.0, N_MOTES) ** 2
MOTE_DRIFT = _rng.normal(0.0, 0.05, (N_MOTES, 3))


def motes(t, w, h, strength):
    """Out-of-focus specks of dust lit by the flash: soft discs, bigger the nearer they are."""
    out = np.zeros((h, w), np.float32)
    if strength <= 1e-4:
        return out
    scale = w / 1080.0
    px, py, depth = C.project(MOTES + MOTE_DRIFT * t, t, w, h)
    for i in range(N_MOTES):
        d = depth[i]
        if d < 0.7:
            continue
        r = min(max(13.0 / d, 1.2), 15.0) * scale
        x, y = px[i], py[i]
        if x < -r or x > w + r or y < -r or y > h + r:
            continue
        x0, x1 = int(max(x - r - 2, 0)), int(min(x + r + 3, w))
        y0, y1 = int(max(y - r - 2, 0)), int(min(y + r + 3, h))
        if x1 <= x0 or y1 <= y0:
            continue
        yy, xx = np.mgrid[y0:y1, x0:x1]
        rr = np.sqrt((xx + 0.5 - x) ** 2 + (yy + 0.5 - y) ** 2)
        disc = np.clip(r - rr + 0.5, 0.0, 1.0) * (0.72 + 0.28 * (rr / r) ** 3)
        # light falls with distance; a disc's own brightness stays much the same, the far small ones a little brighter
        out[y0:y1, x0:x1] += disc * (MOTE_GLOW[i] * strength * min(1.0, (3.0 * scale / r) ** 1.3))
    return out


METEOR = dict(t0=1.36, t1=1.62, a=(0.30, 0.955, 0.86), b=(0.085, 0.96, 0.50))  # world directions (unnormalised)


def meteor(t, w, h):
    """A shooting star crossing the upper sky: a bright head and a tail that fades behind it."""
    out = np.zeros((h, w), np.float32)
    m = METEOR
    if t < m['t0'] or t > m['t1'] + 0.22:
        return out
    a = np.array(m['a']); a /= np.linalg.norm(a)
    b = np.array(m['b']); b /= np.linalg.norm(b)
    head = min((t - m['t0']) / (m['t1'] - m['t0']), 1.0)
    fade = 1.0 if t <= m['t1'] else max(0.0, 1.0 - (t - m['t1']) / 0.22)
    n = 400
    u = np.linspace(max(head - 0.55, 0.0), head, n)
    pts = a[None, :] * (1.0 - u[:, None]) + b[None, :] * u[:, None]
    px, py, _ = C.project(pts * 1e6, t, w, h)
    amp = np.exp(-(head - u) / 0.16) * np.sin(math.pi * np.clip(u, 0, 1)) ** 0.6 * fade
    ok = (px > 1) & (px < w - 2) & (py > 1) & (py < h - 2)
    np.add.at(out, (np.rint(py[ok]).astype(int), np.rint(px[ok]).astype(int)), amp[ok] * 0.06)
    return ndimage.gaussian_filter(out, 1.1 * w / 1080.0)


# the glint: a narrow bar of light that crosses the name with the strip light's soft reflection (which the render has)
GLINT = dict(t0=1.092, t1=1.441, u0=-7.5, u1=7.5, slant=0.5, core=0.42, skirt=1.5, gain=8.0)


def glint(t, w, h):
    """Where the bar of light is at time t: a weight per pixel (1 on its line), or None when it isn't on the name."""
    g = GLINT
    if t < g['t0'] - 0.02 or t > g['t1'] + 0.02:
        return None
    pos, _, _, _ = C.cam_pose(t)
    dirs = C.pixel_dirs(t, w, h)
    right, up, facing = C.block_axes()
    # where each pixel's ray meets the plane the lettering stands in
    denom = dirs @ facing
    denom = np.where(np.abs(denom) > 1e-6, denom, 1e-6)
    s = ((C.BLOCK_CENTRE - pos) @ facing) / denom
    d = pos + dirs * s[..., None] - C.BLOCK_CENTRE
    u = d @ right + g['slant'] * (d @ up)
    x = u - (g['u0'] + (g['u1'] - g['u0']) * (t - g['t0']) / (g['t1'] - g['t0']))
    win = np.exp(-0.5 * (x / g['core']) ** 2) + 0.22 * np.exp(-0.5 * (x / g['skirt']) ** 2)
    return (win * (s > 0)).astype(np.float32)


def star_steps(t, w, h, shutter=0.5):
    """How many times the stars are drawn across the shutter's opening, so their blur is a line and not a row of dots."""
    far = np.array([[0.0, 1.0, 0.3]]) * 1e7
    ax, ay, _ = C.project(far, t - 0.5 * shutter / C.FPS, w, h)
    bx, by, _ = C.project(far, t + 0.5 * shutter / C.FPS, w, h)
    moved = math.hypot(bx[0] - ax[0], by[0] - ay[0])
    return int(min(40, max(4, math.ceil(moved / 0.8))))


def compose(t, layers, w, h, sub=None):
    """One frame in scene light (before the film curve)."""
    if sub is None:
        sub = star_steps(t, w, h)
    scale = w / 1080.0
    wts = C.weights(t)
    rgba = layers['ViewLayer.Combined']
    alpha = np.clip(rgba[..., 3], 0.0, 1.0)
    dirs = C.pixel_dirs(t, w, h)
    ground = (dirs[..., 2] < math.sin(math.radians(6.0))).astype(np.float32)
    ground = ndimage.gaussian_filter(ground, 2.0 * scale)
    fl = C.flash(t)
    fg = np.zeros((h, w, 3), np.float32)
    for g in GROUPS:
        layer = layers['ViewLayer.Combined_' + g]
        if g == 'flash':
            fg += layer * (GAIN[g] * wts[g]) * (1.0 - ground * (1.0 - GROUND_FLASH))[..., None]
        else:
            fg += layer * (GAIN[g] * wts.get(g, 1.0))
    win = glint(t, w, h)
    if win is not None:
        # the bar shows on the metal as the big soft light does (its pass is the metal's answer to light from the front)
        fg += layers['ViewLayer.Combined_key'] * (GLINT['gain'] * win)[..., None]
    bg, bright = sky.render_sky(t, w, h, sub=sub)
    # the sky's colour, deepened
    lum = bg @ LUMA
    bg = np.clip(lum[..., None] + (bg - lum[..., None]) * 1.55, 0.0, None)
    bg = bg + gauss(bright, 1.8 * scale) * 0.55 + gauss(bright, 6.0 * scale) * 0.16 + gauss(bright, 18.0 * scale) * 0.04
    bg = bg + meteor(t, w, h)[..., None] * np.array([0.9, 1.0, 0.95], np.float32) * 9.0
    img = bg * (1.0 - alpha)[..., None] + fg
    # glare: the brightest light spreads (more while the flash is on), and streaks sideways
    lum = img.max(axis=-1)
    hot = img * np.clip((lum - 0.8) / (lum + 1e-6), 0.0, 1.0)[..., None]
    img = img + bloom(hot, scale, ((2.0, 0.26), (6.0, 0.22), (16.0, 0.16), (40.0, 0.10), (110.0, 0.05 + 0.10 * fl))) * (0.55 + 0.6 * fl)
    img = img + streak(hot, scale, 150.0 + 260.0 * fl) * np.array([0.55, 0.75, 1.0], np.float32) * (0.05 + 0.28 * fl)
    if fl > 0:
        # the flash in the air in front of the lens: a burst around the name, and the dust it catches
        cx, cy, _ = C.project(C.BLOCK_CENTRE[None, :], t, w, h)
        yy, xx = np.mgrid[0:h, 0:w]
        r2 = ((xx - cx[0]) / (w * 0.62)) ** 2 + ((yy - cy[0]) / (w * 0.50)) ** 2
        burst = np.exp(-r2) * 0.17 + np.exp(-r2 / 6.0) * 0.07
        img += (fl ** 1.25) * burst[..., None] * np.array([1.0, 0.985, 0.96], np.float32)
        img += motes(t, w, h, fl)[..., None] * np.array([1.0, 0.97, 0.92], np.float32) * 0.42
        img *= 1.0 + 0.40 * fl
    return img


def finish(img, seed):
    """A vignette, the film curve, a touch of colour in the shadows, grain, 8 bits."""
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    r2 = ((xx - w / 2.0) / (w / 2.0)) ** 2 * 0.5 + ((yy - h * 0.46) / (h / 2.0)) ** 2 * 0.5
    img = img * (1.0 - 0.42 * np.clip(r2, 0, 1.3) ** 1.25)[..., None]
    y = film(img * 1.18)
    # split tone: the darks lean blue, the brights warm
    l = (y @ LUMA)[..., None]
    y = y + (1.0 - l) ** 3 * np.array([-0.006, 0.000, 0.016], np.float32) + l ** 2 * np.array([0.010, 0.002, -0.012], np.float32)
    rng = np.random.default_rng(seed)
    g = rng.normal(0.0, 1.0, (h, w)).astype(np.float32)
    g = ndimage.gaussian_filter(g, 0.55) * 1.6
    y = y + (g * 0.011 * (0.45 + 0.55 * (1.0 - l[..., 0])))[..., None]
    y = y + (rng.random((h, w, 3)).astype(np.float32) - 0.5) / 255.0
    return (np.clip(y, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    names = sorted(n for n in os.listdir(src) if n.endswith('.exr'))
    if len(sys.argv) > 4:
        a, b = int(sys.argv[3]), int(sys.argv[4])
        names = [n for n in names if a <= int(n[1:5]) <= b]
    for n in names:
        f = int(n[1:5])
        out = os.path.join(dst, 'f%04d.png' % f)
        if os.path.exists(out) and not os.environ.get('REDO'):
            continue
        t = f / C.FPS
        layers = exr.read_layers(os.path.join(src, n))
        h, w = layers['ViewLayer.Combined'].shape[:2]
        img = compose(t, layers, w, h)
        Image.fromarray(finish(img, f)).save(out)
        print('frame', f, flush=True)


if __name__ == '__main__':
    main()
