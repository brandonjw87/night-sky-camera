"""The night sky behind the name: the real bright stars (the app's own catalogue), a Milky Way built from a model of
its light and its dust in galactic coordinates, and faint stars scattered the way the model's light lies.

render_sky(t) draws it for the camera at time t (linear light, float32); equirect() gives the soft glow of the whole
sky for the 3D scene's reflections.
"""
import math
import os

import numpy as np
from scipy import ndimage

import intro_common as C

HERE = os.path.dirname(os.path.abspath(__file__))
CATALOGUE = os.environ.get('STARS_CSV', os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'stars.csv'))
CACHE = os.path.join(os.environ.get('INTRO_WORK', HERE), 'sky_cache.npz')

# J2000 equatorial to galactic
EQ2GAL = np.array([[-0.0548755604, -0.8734370902, -0.4838350155],
                   [0.4941094279, -0.4448296300, 0.7469822445],
                   [-0.8676661490, -0.1980763734, 0.4559837762]])

# where the galactic centre stands in the world, and how the Milky Way leans there
GC_AZ = math.radians(2.0)      # from +Y towards +X
GC_EL = math.radians(8.5)
MW_TILT = math.radians(-13.0)  # the band's lean from upright (negative: its top leans left)

L0, L1, B0, B1, STEP = -75.0, 115.0, -42.0, 42.0, 0.05  # the model's grid in galactic degrees
STAR_SLOPE = 0.27  # a photo's response to starlight is compressed (0.4 would be linear)


def gal_to_world():
    az, e = GC_AZ, GC_EL
    g = np.array([math.sin(az) * math.cos(e), math.cos(az) * math.cos(e), math.sin(e)])
    up = np.array([-math.sin(az) * math.sin(e), -math.cos(az) * math.sin(e), math.cos(e)])
    right = np.array([math.cos(az), -math.sin(az), 0.0])
    tl = math.cos(MW_TILT) * up + math.sin(MW_TILT) * right
    pole = np.cross(g, tl)
    return np.stack([g, tl, pole], axis=1)  # columns: galactic x, y, z in the world


G2W = gal_to_world()


def fbm(shape, beta, seed, lo=0.0):
    """Noise with a 1/f^beta spectrum, zero mean and unit deviation (lo cuts the lowest frequencies)."""
    rng = np.random.default_rng(seed)
    white = rng.standard_normal(shape).astype(np.float32)
    f = np.fft.rfft2(white)
    fy = np.fft.fftfreq(shape[0])[:, None]
    fx = np.fft.rfftfreq(shape[1])[None, :]
    k = np.sqrt(fy * fy + fx * fx)
    k[0, 0] = 1.0
    amp = k ** (-beta / 2.0)
    if lo > 0:
        amp *= 1.0 - np.exp(-(k / lo) ** 2)
    amp[0, 0] = 0.0
    out = np.fft.irfft2(f * amp, s=shape).astype(np.float32)
    out -= out.mean()
    out /= out.std()
    return out


def bv_rgb(bv):
    """A star's colour from its B-V (linear RGB, green = 1 near white)."""
    bv = np.clip(bv, -0.35, 1.9)
    t = 4600.0 * (1.0 / (0.92 * bv + 1.7) + 1.0 / (0.92 * bv + 0.62))
    # blackbody ratios against 6500 K at 610 / 550 / 465 nm
    out = []
    for lam in (610e-9, 550e-9, 465e-9):
        x = 1.4388e-2 / lam
        out.append((np.exp(x / 6500.0) - 1.0) / (np.exp(x / t) - 1.0))
    rgb = np.stack(out, axis=-1)
    rgb /= rgb[..., 1:2]
    return rgb


def build():
    """The model: the glow image on the galactic grid and the faint stars (cached)."""
    if os.path.exists(CACHE):
        z = np.load(CACHE)
        return z['glow'], z['fs_dir'], z['fs_flux']
    nl = int(round((L1 - L0) / STEP))
    nb = int(round((B1 - B0) / STEP))
    l = (L0 + (np.arange(nl) + 0.5) * STEP)[None, :].astype(np.float32)
    b = (B0 + (np.arange(nb) + 0.5) * STEP)[:, None].astype(np.float32)
    shape = (nb, nl)
    n_big = fbm(shape, 3.2, 11)
    n_med = fbm(shape, 2.6, 12, lo=0.004)
    n_fine = fbm(shape, 2.0, 13, lo=0.02)
    n_rift = fbm(shape, 2.8, 14, lo=0.003)
    n_fil = fbm(shape, 2.4, 15, lo=0.006)
    wander = ndimage.gaussian_filter1d(fbm((1, nl), 3.0, 16)[0], 60)[None, :]

    # light: thin disc, thick disc, bulge, star clouds
    disc = np.exp(-np.abs(b - 0.2 * n_big) / 2.4) * (0.30 + 0.70 * np.exp(-np.abs(l) / 34.0))
    thick = 0.22 * np.exp(-np.abs(b) / 8.5) * (0.35 + 0.65 * np.exp(-np.abs(l) / 48.0))
    bulge = 2.1 * np.exp(-(((l / 10.5) ** 2 + ((b + 0.3) / 7.0) ** 2) ** 0.72))
    clouds = np.zeros(shape, np.float32)
    for (cl, cb, sl, sb, a) in ((13.4, 0.1, 1.5, 0.9, 1.1), (27.0, -3.2, 3.0, 2.2, 0.55), (2.5, -4.2, 3.2, 2.2, 0.75),
                                (-8.0, -3.0, 4.0, 2.0, 0.35), (38.0, -1.0, 4.0, 1.6, 0.35), (-25.0, -1.0, 6.0, 1.6, 0.3),
                                (48.0, 1.0, 5.0, 2.0, 0.3), (68.0, 1.5, 7.0, 2.6, 0.32), (80.0, 0.5, 5.0, 3.0, 0.35)):
        clouds += a * np.exp(-(((l - cl) / sl) ** 2 + ((b - cb) / sb) ** 2))
    light = (disc + thick + bulge + clouds) * np.exp(0.26 * n_med) * (1.0 + 0.16 * n_fine)

    # dust: the great rift winding along the plane, threads off it, patches
    n_f2 = fbm(shape, 1.9, 17, lo=0.012)
    n_f3 = fbm(shape, 1.7, 18, lo=0.03)
    r1 = 1.0 - np.abs(np.tanh(0.9 * n_rift))
    r2 = 1.0 - np.abs(np.tanh(1.3 * n_fil))
    r3 = 1.0 - np.abs(np.tanh(1.6 * n_f2))
    r4 = 1.0 - np.abs(np.tanh(1.8 * n_f3))
    threads = 0.42 * r1 ** 2 + 0.30 * r2 ** 3 + 0.18 * r3 ** 4 + 0.10 * r4 ** 5
    rift_b = 1.0 + 1.2 * np.sin(np.radians(l * 5.5)) + 2.0 * wander
    rift_w = 1.15 + 1.5 * (1.0 / (1.0 + np.exp(-(l - 14.0) / 8.0))) + 0.45 * np.abs(n_big)
    near = np.exp(-(((b - rift_b) / rift_w) ** 2))
    wide = np.exp(-(np.abs(b - rift_b) / 7.5) ** 1.5) * (0.35 + 0.65 * np.exp(-np.abs(l - 8.0) / 45.0))
    tau = 5.2 * near * (0.25 + 1.9 * threads) + 5.5 * wide * np.clip(threads - 0.27, 0.0, None) ** 1.25 * (1.0 + 0.6 * np.tanh(n_big))
    tau += 1.1 * np.clip(n_med - 0.6, 0.0, None) * np.exp(-np.abs(b) / 7.0)
    tau = ndimage.gaussian_filter(tau, 0.6)
    k = np.array([0.84, 1.0, 1.17], np.float32)
    trans = np.exp(-tau[..., None] * k)

    warm = np.array([1.0, 0.84, 0.62], np.float32)
    cool = np.array([0.80, 0.88, 1.0], np.float32)
    wb = (bulge + 0.5 * clouds) / (disc + thick + bulge + clouds + 1e-6)
    colour = cool + (warm - cool) * np.clip(wb * 1.15, 0.0, 1.0)[..., None]
    glow = light[..., None] * colour * trans
    # emission nebulae: small pink clouds on the plane
    for (cl, cb, s, a) in ((6.0, -1.2, 0.42, 2.4), (7.0, -0.25, 0.22, 1.5), (17.0, 0.8, 0.30, 1.4), (15.1, -0.7, 0.24, 1.7),
                           (-8.5, 0.6, 0.6, 0.7), (25.3, -2.6, 0.2, 0.7), (-23.0, -0.4, 0.5, 0.8), (-6.7, 0.7, 0.3, 0.9)):
        blob = a * np.exp(-(((l - cl) / s) ** 2 + ((b - cb) / (0.8 * s)) ** 2))
        glow += blob[..., None] * np.array([1.0, 0.30, 0.42], np.float32) * (0.35 + 0.65 * trans)
    glow = glow.astype(np.float32)

    # faint stars: where the light is, thinned by the dust; many more faint than bright
    rng = np.random.default_rng(77)
    dens = (light * trans[..., 1] + 0.035).astype(np.float64)
    dens *= np.cos(np.radians(b))
    cdf = np.cumsum(dens.ravel())
    n = 420000
    pick = np.searchsorted(cdf, rng.random(n) * cdf[-1])
    ib, il = np.divmod(pick, nl)
    fl = L0 + (il + rng.random(n)) * STEP
    fb = B0 + (ib + rng.random(n)) * STEP
    # magnitudes 6.5 .. 13: counts rise 10^(0.36 m)
    u = rng.random(n)
    a = 0.36 * math.log(10.0)
    m = np.log(np.exp(a * 6.5) + u * (np.exp(a * 13.0) - np.exp(a * 6.5))) / a
    red = tau[ib, il]
    bv = np.clip(rng.normal(0.75, 0.42, n) + 0.25 * red, -0.25, 1.9)
    flux = (10.0 ** (-STAR_SLOPE * m))[:, None] * bv_rgb(bv)
    lr, br = np.radians(fl), np.radians(fb)
    gdir = np.stack([np.cos(br) * np.cos(lr), np.cos(br) * np.sin(lr), np.sin(br)], axis=1)
    fs_dir = (gdir @ G2W.T).astype(np.float32)
    np.savez(CACHE, glow=glow, fs_dir=fs_dir, fs_flux=flux.astype(np.float32))
    return glow, fs_dir, flux.astype(np.float32)


def catalogue():
    """The bright stars: world directions and linear RGB flux."""
    ra, dec, v, bv = [], [], [], []
    with open(CATALOGUE, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            p = line.rstrip('\n').split(',')
            ra.append(float(p[0]))
            dec.append(float(p[1]))
            v.append(float(p[2]))
            try:
                bv.append(float(p[7]))
            except (ValueError, IndexError):
                bv.append(0.6)
    ra, dec, v, bv = np.radians(ra), np.radians(dec), np.array(v), np.array(bv)
    eq = np.stack([np.cos(dec) * np.cos(ra), np.cos(dec) * np.sin(ra), np.sin(dec)], axis=1)
    gal = eq @ EQ2GAL.T
    world = gal @ G2W.T
    flux = (10.0 ** (-STAR_SLOPE * v))[:, None] * bv_rgb(bv)
    return world.astype(np.float32), flux.astype(np.float32)


_STATE = {}


def state():
    if not _STATE:
        glow, fs_dir, fs_flux = build()
        cd, cf = catalogue()
        _STATE['glow'] = glow
        _STATE['dir'] = np.concatenate([cd, fs_dir]).astype(np.float64)
        _STATE['flux'] = np.concatenate([cf, fs_flux])
    return _STATE


def glow_at(dirs):
    """The model's glow for world directions (..., 3)."""
    g = dirs @ G2W  # world to galactic (G2W's columns are the galactic axes)
    l = np.degrees(np.arctan2(g[..., 1], g[..., 0]))
    b = np.degrees(np.arcsin(np.clip(g[..., 2], -1.0, 1.0)))
    x = (l - L0) / STEP - 0.5
    y = (b - B0) / STEP - 0.5
    glow = state()['glow']
    out = np.empty(dirs.shape[:-1] + (3,), np.float32)
    for c in range(3):
        out[..., c] = ndimage.map_coordinates(glow[..., c], [y, x], order=1, mode='constant', cval=0.0)
    # off the model's grid the sky still has a floor
    return out


def air(dirs):
    """What the air does with height above the horizon: dimming (multiply) and the horizon's own glow (add)."""
    el = np.degrees(np.arcsin(np.clip(dirs[..., 2], -1.0, 1.0)))
    az = np.degrees(np.arctan2(dirs[..., 0], dirs[..., 1]))
    sin_el = np.sin(np.radians(np.clip(el, 0.3, 90.0)))
    airmass = 1.0 / (sin_el + 0.025 * np.exp(-11.0 * sin_el))
    dim = np.exp(-0.16 * (airmass - 1.0))
    elc = np.clip(el, 0.0, 90.0)
    glow = np.zeros(dirs.shape[:-1] + (3,), np.float32)
    # airglow: green, in a band a little above the horizon, fading upwards
    band = np.exp(-elc / 17.0) * (1.0 - 0.55 * np.exp(-elc / 2.5))
    glow += band[..., None] * np.array([0.0080, 0.0170, 0.0125], np.float32)
    # a far town's warm dome low on the left, and the last deep blue of dusk hugging the horizon on the right
    dome = np.exp(-((az + 34.0) / 22.0) ** 2) * np.exp(-elc / 4.5)
    glow += dome[..., None] * np.array([0.095, 0.050, 0.018], np.float32)
    dusk = np.exp(-((az - 30.0) / 50.0) ** 2) * np.exp(-elc / 6.0)
    glow += dusk[..., None] * np.array([0.006, 0.016, 0.036], np.float32)
    return dim.astype(np.float32), glow


# brightness: the glow's scale, the stars' scale (flux of magnitude 0 = 1 before it)
GLOW_GAIN = 0.075
STAR_GAIN = 26.0
SKY_FLOOR = np.array([0.0022, 0.0040, 0.0090], np.float32)


def render_sky(t, w=C.W, h=C.H, sub=6, shutter=0.5):
    """The sky for the camera at time t: (h, w, 3) linear float32, and the bright stars alone (for their glare)."""
    st = state()
    dirs = C.pixel_dirs(t, w, h)
    dim, hz = air(dirs)
    img = (glow_at(dirs) * GLOW_GAIN + SKY_FLOOR) * dim[..., None] + hz
    stars = np.zeros((h * w, 3), np.float64)
    bright = np.zeros((h * w, 3), np.float64)
    f = (w / 2.0) / math.tan(C.HFOV / 2.0)
    scale = w / 1080.0
    for k in range(sub):
        tk = t + ((k + 0.5) / sub - 0.5) * shutter / C.FPS if sub > 1 else t
        pos, right, up, back = C.cam_pose(tk)
        d = st['dir']
        zc = -(d @ back)
        ok = zc > 0.2
        xc = (d[ok] @ right) / zc[ok]
        yc = (d[ok] @ up) / zc[ok]
        px = w / 2.0 + f * xc - 0.5
        py = h / 2.0 - f * yc - 0.5
        inside = (px > -2) & (px < w + 1) & (py > -2) & (py < h + 1)
        px, py = px[inside], py[inside]
        dd = d[ok][inside]
        el_dim = np.interp(np.degrees(np.arcsin(np.clip(dd[:, 2], -1, 1))), [0.0, 1.0, 3.0, 8.0, 20.0, 90.0],
                           [0.0, 0.10, 0.35, 0.62, 0.86, 1.0])
        fl = st['flux'][ok][inside] * el_dim[:, None] * (STAR_GAIN * scale * scale / sub)
        x0 = np.floor(px).astype(np.int64)
        y0 = np.floor(py).astype(np.int64)
        fx = px - x0
        fy = py - y0
        for (ox, oy, wt) in ((0, 0, (1 - fx) * (1 - fy)), (1, 0, fx * (1 - fy)), (0, 1, (1 - fx) * fy), (1, 1, fx * fy)):
            xi, yi = x0 + ox, y0 + oy
            m = (xi >= 0) & (xi < w) & (yi >= 0) & (yi < h)
            idx = yi[m] * w + xi[m]
            for c in range(3):
                stars[:, c] += np.bincount(idx, weights=fl[m, c] * wt[m], minlength=h * w)
        # the brightest ones again on their own, for the glare
        big = fl.max(axis=1) > 1.6 * scale * scale / sub
        if big.any():
            xi = np.clip(np.rint(px[big]).astype(np.int64), 0, w - 1)
            yi = np.clip(np.rint(py[big]).astype(np.int64), 0, h - 1)
            for c in range(3):
                bright[:, c] += np.bincount(yi * w + xi, weights=fl[big, c], minlength=h * w)
    stars = stars.reshape(h, w, 3).astype(np.float32)
    bright = bright.reshape(h, w, 3).astype(np.float32)
    # a star is a small soft disc, not one pixel
    s = 0.62 * scale
    for c in range(3):
        stars[..., c] = ndimage.gaussian_filter(stars[..., c], s)
    return img + stars, bright


def equirect(w=4096, h=2048):
    """The whole sky for the scene's world lighting and the lake's mirror (Blender's equirectangular layout, row 0
    at the top): the same glow and the same stars as the picture's sky, at the same brightness."""
    # Blender: u = 0.5 - atan2(y, x) / 2 pi, and the top row is straight up
    u = (np.arange(w) + 0.5) / w
    lon = (0.5 - u) * 2.0 * math.pi
    lat = (0.5 - (np.arange(h) + 0.5) / h) * math.pi
    LON, LAT = np.meshgrid(lon, lat)
    d = np.stack([np.cos(LAT) * np.cos(LON), np.cos(LAT) * np.sin(LON), np.sin(LAT)], axis=-1)
    dim, hz = air(d)
    img = (glow_at(d) * GLOW_GAIN + SKY_FLOOR) * dim[..., None] + hz
    st = state()
    sd, sf = st['dir'], st['flux']
    slat = np.arcsin(np.clip(sd[:, 2], -1, 1))
    slon = np.arctan2(sd[:, 1], sd[:, 0])
    px = np.clip(((0.5 - slon / (2.0 * math.pi)) * w).astype(np.int64), 0, w - 1)
    py = np.clip(((0.5 - slat / math.pi) * h).astype(np.int64), 0, h - 1)
    el_dim = np.interp(np.degrees(slat), [0.0, 1.0, 3.0, 8.0, 20.0, 90.0], [0.0, 0.10, 0.35, 0.62, 0.86, 1.0])
    f = (C.W / 2.0) / math.tan(C.HFOV / 2.0)
    texel = (2.0 * math.pi / w) ** 2 * np.maximum(np.cos(slat), 0.05)
    val = sf * (STAR_GAIN * el_dim / (f * f) / texel)[:, None]
    stars = np.zeros((h * w, 3), np.float64)
    for c in range(3):
        stars[:, c] = np.bincount(py * w + px, weights=val[:, c], minlength=h * w)
    stars = stars.reshape(h, w, 3).astype(np.float32)
    for c in range(3):
        stars[..., c] = ndimage.gaussian_filter(stars[..., c], 0.8, mode='wrap')
    img = img + np.clip(stars, 0.0, 6.0)
    below = d[..., 2] < 0
    img[below] = np.array([0.0015, 0.0017, 0.002], np.float32)
    return img.astype(np.float32)


if __name__ == '__main__':
    import sys
    from PIL import Image
    t = float(sys.argv[1]) if len(sys.argv) > 1 else C.DUR
    out = sys.argv[2] if len(sys.argv) > 2 else 'sky_test.png'
    img, bright = render_sky(t)
    img = img + ndimage.gaussian_filter(bright, (3, 3, 0)) * 0.6
    x = np.clip(img * 2.2, 0, None)
    x = x / (1.0 + x)
    x = np.clip(x * 1.25, 0, 1) ** (1 / 2.2)
    Image.fromarray((x * 255 + 0.5).astype(np.uint8)).save(out)
    print('wrote', out, img.mean(), img.max())
