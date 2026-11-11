#!/usr/bin/env python3
"""Synthesises the intro's sound (2.2 s, 48 kHz, 16-bit stereo), in step with the film (see intro_common.py).

  0.00-0.55  the camera swinging round: air moving past, from the left
  0.00-0.56  the flash charging: a rising whine
  0.41-0.56  a rush into the flash
  0.56       the flash: the tube's pop, a heavy shutter, a low thump, then a shimmer that hangs in the air
  1.10-1.45  the glint crossing the name, left to right
  1.38       the shooting star, a breath high on the right
  to 2.20    the night settling

    python3 sound.py OUT.wav
"""
import sys
import wave

import numpy as np
from scipy.signal import lfilter

import intro_common as C

SR = 48000
DUR = C.DUR
N = int(round(SR * DUR))
FLASH = C.T_FLASH
rng = np.random.default_rng(2020)
t = np.arange(N) / SR
L = np.zeros(N)
R = np.zeros(N)


def place(sig, start, pan=0.0, gain=1.0):
    """Adds a mono sound at `start` seconds, panned -1 (left) .. 1 (right) at equal power."""
    i0 = int(round(start * SR))
    if i0 >= N:
        return
    if i0 < 0:
        sig = sig[-i0:]
        i0 = 0
    n = min(len(sig), N - i0)
    a = (pan + 1.0) * np.pi / 4.0
    L[i0:i0 + n] += sig[:n] * np.cos(a) * gain
    R[i0:i0 + n] += sig[:n] * np.sin(a) * gain


def place_moving(sig, start, pan):
    """The same with the pan changing along the sound (an array as long as it)."""
    i0 = int(round(start * SR))
    n = min(len(sig), N - i0)
    a = (pan[:n] + 1.0) * np.pi / 4.0
    L[i0:i0 + n] += sig[:n] * np.cos(a)
    R[i0:i0 + n] += sig[:n] * np.sin(a)


def env_ad(n, attack, decay):
    x = np.arange(n) / SR
    return np.where(x < attack, x / max(attack, 1e-6), np.exp(-(x - attack) / decay))


def biquad(x, kind, f0, q):
    w0 = 2 * np.pi * f0 / SR
    cw, sw = np.cos(w0), np.sin(w0)
    alpha = sw / (2 * q)
    if kind == 'lp':
        b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]
    elif kind == 'hp':
        b = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]
    else:  # band-pass, constant peak gain
        b = [alpha, 0.0, -alpha]
    a = [1 + alpha, -2 * cw, 1 - alpha]
    return lfilter(np.array(b) / a[0], np.array(a) / a[0], x)


def swept_band(x, f_of, q, block=256):
    """Noise through a band-pass whose centre moves (f_of: array of Hz as long as x), block by block with overlap."""
    out = np.zeros(len(x))
    win = np.hanning(2 * block)
    pad = np.concatenate([np.zeros(block), x, np.zeros(2 * block)])
    for i in range(0, len(x) + block, block):
        seg = pad[i:i + 2 * block] * win
        f0 = f_of[min(len(x) - 1, max(0, i - block // 2))]
        y = biquad(np.concatenate([seg, np.zeros(block)]), 'bp', f0, q)[:2 * block]
        a, b = i - block, i + block
        lo, hi = max(a, 0), min(b, len(x))
        if hi > lo:
            out[lo:hi] += y[lo - a:hi - a]
    return out


def noise(n):
    return rng.standard_normal(n)


def click(pitch=1.0, weight=1.0):
    """One mechanical shutter: a transient, two resonances, a small body thump, and the second curtain after it."""
    n = int(0.09 * SR)
    x = np.arange(n) / SR
    tr = biquad(noise(n) * env_ad(n, 0.0004, 0.0025), 'hp', 2500, 0.7)
    r1 = np.sin(2 * np.pi * 1850 * pitch * x) * np.exp(-x / 0.010)
    r2 = np.sin(2 * np.pi * 3600 * pitch * x + 1.3) * np.exp(-x / 0.006)
    body = np.sin(2 * np.pi * 160 * pitch * x) * np.exp(-x / 0.018) * weight
    s = 0.9 * tr + 0.45 * r1 + 0.30 * r2 + 0.55 * body
    d = int(0.012 * SR / pitch)
    s2 = np.zeros(n)
    s2[d:] = 0.55 * s[:n - d]
    return (s + s2) * 0.5


# --- the night: soft air and a low drone under everything -------------------------------------------------------------
air = biquad(noise(N), 'lp', 900, 0.5) * 0.012
drone = (np.sin(2 * np.pi * 55 * t) * 0.5 + np.sin(2 * np.pi * 82.4 * t) * 0.25) * 0.05
bed = np.clip(t / 0.25, 0, 1) * np.clip((DUR - t) / 0.35, 0, 1)
place((air + drone) * bed, 0.0)

# --- the camera swinging round: air past the lens, strongest at the start, from the left to the middle ------------
n = int(0.60 * SR)
x = np.arange(n) / SR
speed = np.array([abs(C.cam_pose(v + 0.004)[0][0] - C.cam_pose(v)[0][0]) for v in x[::480]])
speed = np.interp(x, x[::480], speed / speed.max())
rush = swept_band(noise(n), 380 + 1500 * speed ** 0.7, 0.9)
rush *= np.clip(x / 0.035, 0, 1) * speed ** 1.2 * np.clip((0.60 - x) / 0.08, 0, 1)
place_moving(rush * 0.30, 0.0, -0.65 + 0.65 * np.clip(x / 0.5, 0, 1))

# --- the flash charging ----------------------------------------------------------------------------------------------
n = int(FLASH * SR)
x = np.arange(n) / SR
f = 2100 * (7600 / 2100) ** (x / FLASH)
ph = 2 * np.pi * np.cumsum(f) / SR + 0.002 * np.sin(2 * np.pi * 7 * x)
whine = np.sin(ph) * (0.015 + 0.055 * (x / FLASH) ** 1.6)
whine *= np.clip((FLASH - x) / 0.004, 0, 1)
place(whine, 0.0)

# --- a rush into the flash ---------------------------------------------------------------------------------------------
n = int(0.15 * SR)
x = np.arange(n) / SR
place(biquad(noise(n), 'bp', 1200, 0.8) * (x / 0.15) ** 2.5 * 0.30, FLASH - 0.15)

# --- the flash -----------------------------------------------------------------------------------------------------------
n = int(0.03 * SR)
place(biquad(noise(n), 'hp', 3000, 0.7) * env_ad(n, 0.0002, 0.004) * 0.9, FLASH)       # the tube's pop
place(click(0.78, 1.4), FLASH - 0.002, 0.0, 0.95)                                        # a heavy shutter
place(click(0.70, 1.0), FLASH + 0.034, 0.0, 0.50)
n = int(0.6 * SR)
x = np.arange(n) / SR
boom_f = 62 * np.exp(-x / 0.25) + 38
boom = np.sin(2 * np.pi * np.cumsum(boom_f) / SR) * np.exp(-x / 0.22) * np.clip(x / 0.004, 0, 1)
place(boom * 0.55, FLASH)
# the shimmer that hangs in the air, a little different in each ear
n = int(1.35 * SR)
x = np.arange(n) / SR
for pan, delay, g in ((-0.35, 0.0, 0.055), (0.35, 0.013, 0.047)):
    sh = biquad(noise(n), 'hp', 5200, 0.7) * np.exp(-x / 0.36) * np.clip(x / 0.01, 0, 1)
    sh *= 0.6 + 0.4 * np.sin(2 * np.pi * 23 * x + pan) ** 2
    place(sh * g, FLASH + delay, pan)
# and a few high bell notes in it
for k, (fq, dl, g) in enumerate(((5274, 0.00, 0.05), (6645, 0.04, 0.04), (7902, 0.08, 0.03), (4186, 0.11, 0.035))):
    n = int(0.95 * SR)
    x = np.arange(n) / SR
    place(np.sin(2 * np.pi * fq * x) * np.exp(-x / 0.30) * np.clip(x / 0.002, 0, 1) * g, FLASH + 0.02 + dl, (-1) ** k * 0.4)

# --- the glint crossing the name, left to right ----------------------------------------------------------------------
n = int(0.80 * SR)
x = np.arange(n) / SR
ring = sum(np.sin(2 * np.pi * fq * x + i) * g for i, (fq, g) in enumerate(((2637, 0.5), (3951, 0.35), (5274, 0.22), (7040, 0.12))))
ring *= np.clip(x / 0.014, 0, 1) * np.exp(-x / 0.24)
hiss = biquad(noise(n), 'bp', 6500, 2.0) * np.exp(-x / 0.13) * 0.6
place_moving((ring + hiss) * 0.17, 1.10, np.clip(-0.8 + 1.6 * x / 0.36, -0.8, 0.8))

# --- the shooting star: a breath, high on the right -------------------------------------------------------------------
n = int(0.30 * SR)
x = np.arange(n) / SR
breath = swept_band(noise(n), 9500 - 4500 * (x / 0.30), 3.0) * np.sin(np.pi * np.clip(x / 0.30, 0, 1)) ** 2
place(breath * 0.085, 1.37, 0.6)

# --- the place: a short reverb -----------------------------------------------------------------------------------------
irn = int(0.7 * SR)
ir_t = np.arange(irn) / SR
wet = []
for src in (L, R):
    ir = rng.standard_normal(irn) * np.exp(-ir_t / 0.18)
    ir = biquad(ir, 'lp', 7000, 0.7)
    ir /= np.sqrt(np.sum(ir ** 2))
    wet.append(np.fft.irfft(np.fft.rfft(src, N + irn) * np.fft.rfft(ir, N + irn))[:N])
L = L + 0.22 * wet[0]
R = R + 0.22 * wet[1]

# --- the whole: a gentle limiter, the end faded, a level that leaves the phone's volume in charge -------------------
mix = np.stack([L, R], axis=1)
mix *= np.clip((DUR - t) / 0.14, 0, 1)[:, None]
mix *= np.clip(t / 0.004, 0, 1)[:, None]
mix = np.tanh(mix * 1.6) / np.tanh(1.6)
mix *= 0.80 / np.max(np.abs(mix))
pcm = np.round(mix * 32767).astype('<i2')
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
rms = np.sqrt(np.mean(mix ** 2))
print('samples', len(pcm), 'peak', int(np.max(np.abs(pcm))), 'at %.3f s' % (np.argmax(np.abs(mix).max(axis=1)) / SR), 'rms %.4f' % rms)
for a, b in ((0.0, 0.3), (0.3, 0.55), (0.55, 0.65), (0.65, 1.1), (1.1, 1.5), (1.5, 2.2)):
    seg = mix[int(a * SR):int(b * SR)]
    print('  %.2f-%.2f s: rms %.4f peak %.3f' % (a, b, np.sqrt(np.mean(seg ** 2)), np.abs(seg).max()))
