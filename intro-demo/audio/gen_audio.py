#!/usr/bin/env python3
"""Synthesises the intro's sound (2.2 s, 48 kHz, 16-bit stereo) in sync with the visual timeline.

  0.00-0.30  shutter iris opening (mechanical whirr, settle tick)
  0.24/0.29  autofocus confirm beeps
  0.00-0.63  flash capacitor charging whine
  0.34-0.57  four paparazzi shutter clicks, panned to where their flashes pop
  0.50-0.64  whoosh into the hero flash
  0.64       hero flash: tube pop, heavy shutter, sub boom, sparkle shimmer
  1.10-1.68  shine "shing" sweeping left to right
  2.00       soft closing shutter
Outputs ../assets/intro_sound.wav and intro_sound.pcm (raw little-endian int16 stereo).
"""
import os
import wave

import numpy as np
from scipy.signal import lfilter

SR = 48000
DUR = 2.2
N = int(SR * DUR)
rng = np.random.default_rng(1987)
t = np.arange(N) / SR
L = np.zeros(N)
R = np.zeros(N)


def place(sig, start, pan=0.0, gain=1.0):
    """Add a mono signal at time `start` with equal-power pan (-1 left .. 1 right)."""
    i0 = int(start * SR)
    if i0 >= N:
        return
    n = min(len(sig), N - i0)
    a = (pan + 1.0) * np.pi / 4.0
    L[i0:i0 + n] += sig[:n] * np.cos(a) * gain
    R[i0:i0 + n] += sig[:n] * np.sin(a) * gain


def env_ad(n, attack, decay):
    x = np.arange(n) / SR
    e = np.where(x < attack, x / max(attack, 1e-6), np.exp(-(x - attack) / decay))
    return e


def biquad(x, kind, f0, q):
    w0 = 2 * np.pi * f0 / SR
    cw, sw = np.cos(w0), np.sin(w0)
    alpha = sw / (2 * q)
    if kind == "lp":
        b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]
    elif kind == "hp":
        b = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]
    else:  # band-pass, constant peak gain
        b = [alpha, 0.0, -alpha]
    a = [1 + alpha, -2 * cw, 1 - alpha]
    b = np.array(b) / a[0]
    a = np.array(a) / a[0]
    return lfilter(b, a, x)


def noise(n):
    return rng.standard_normal(n)


def click(pitch=1.0, weight=1.0):
    """One mechanical shutter click: transient + two resonances + a small body thump."""
    n = int(0.09 * SR)
    x = np.arange(n) / SR
    tr = biquad(noise(n) * env_ad(n, 0.0004, 0.0025), "hp", 2500, 0.7)
    r1 = np.sin(2 * np.pi * 1850 * pitch * x) * np.exp(-x / 0.010)
    r2 = np.sin(2 * np.pi * 3600 * pitch * x + 1.3) * np.exp(-x / 0.006)
    body = np.sin(2 * np.pi * 160 * pitch * x) * np.exp(-x / 0.018) * weight
    s = 0.9 * tr + 0.45 * r1 + 0.30 * r2 + 0.55 * body
    # second curtain a few ms later
    d = int(0.012 * SR / pitch)
    s2 = np.zeros(n)
    s2[d:] = 0.55 * s[:n - d]
    return (s + s2) * 0.5


# --- ambient bed: soft night air and a low drone ------------------------------------------------
air = biquad(noise(N), "lp", 900, 0.5) * 0.012
drone = (np.sin(2 * np.pi * 55 * t) * 0.5 + np.sin(2 * np.pi * 82.4 * t) * 0.25) * 0.05
bed_env = np.clip(t / 0.25, 0, 1) * np.clip((DUR - t) / 0.35, 0, 1)
place((air + drone) * bed_env, 0.0)

# --- iris opening whirr + settle tick -------------------------------------------------------------
n = int(0.30 * SR)
x = np.arange(n) / SR
whirr = biquad(noise(n), "bp", 3200, 1.6) * (np.sin(np.pi * np.clip(x / 0.30, 0, 1)) ** 1.5)
whirr *= 1.0 + 0.5 * np.sin(2 * np.pi * 95 * x)
place(whirr * 0.10, 0.0)
place(click(1.6, 0.3) * 0.35, 0.29)

# --- AF beeps -----------------------------------------------------------------------------------
for tb in (0.240, 0.292):
    n = int(0.042 * SR)
    x = np.arange(n) / SR
    b = np.sin(2 * np.pi * 2730 * x) * np.clip(x / 0.002, 0, 1) * np.clip((0.042 - x) / 0.006, 0, 1)
    place(b * 0.11, tb)

# --- flash charging whine -------------------------------------------------------------------------
n = int(0.63 * SR)
x = np.arange(n) / SR
f = 2100 * (7600 / 2100) ** (x / 0.63)
ph = 2 * np.pi * np.cumsum(f) / SR + 0.002 * np.sin(2 * np.pi * 7 * x)
wh = np.sin(ph) * (0.02 + 0.06 * (x / 0.63) ** 1.6)
wh *= np.clip((0.63 - x) / 0.004, 0, 1)
place(wh, 0.0)

# --- paparazzi clicks (pan follows flash position) ------------------------------------------------
for tc, pan, p in ((0.340, -0.75, 1.00), (0.430, 0.80, 1.06), (0.505, -0.45, 0.95), (0.565, 0.50, 1.10)):
    place(click(p, 0.6), tc - 0.004, pan, 0.55)
    # tiny flash tube pop
    n = int(0.02 * SR)
    place(biquad(noise(n), "hp", 6000, 0.7) * env_ad(n, 0.0003, 0.003) * 0.25, tc, pan)

# --- whoosh into the hero flash ---------------------------------------------------------------------
n = int(0.15 * SR)
x = np.arange(n) / SR
sw = biquad(noise(n), "bp", 1200, 0.8) * (x / 0.15) ** 2.5
place(sw * 0.35, 0.640 - 0.15)

# --- hero flash -----------------------------------------------------------------------------------
H = 0.640
n = int(0.03 * SR)
place(biquad(noise(n), "hp", 3000, 0.7) * env_ad(n, 0.0002, 0.004) * 0.9, H)          # tube pop
place(click(0.78, 1.4), H - 0.002, 0.0, 0.95)                                           # heavy shutter
place(click(0.70, 1.0), H + 0.034, 0.0, 0.55)
n = int(0.6 * SR)
x = np.arange(n) / SR
boom_f = 62 * np.exp(-x / 0.25) + 38
boom = np.sin(2 * np.pi * np.cumsum(boom_f) / SR) * np.exp(-x / 0.22) * np.clip(x / 0.004, 0, 1)
place(boom * 0.55, H)
n = int(1.25 * SR)
x = np.arange(n) / SR
sh = noise(n)
sh = biquad(sh, "hp", 5200, 0.7) * np.exp(-x / 0.33) * np.clip(x / 0.01, 0, 1)
sh *= 0.6 + 0.4 * np.sin(2 * np.pi * 23 * x) ** 2
place(sh * 0.055, H, -0.3)
sh2 = biquad(noise(n), "hp", 5200, 0.7) * np.exp(-x / 0.33) * np.clip(x / 0.01, 0, 1)
place(sh2 * 0.045, H + 0.013, 0.3)
# sparkle: a few high bell partials
for k, (fq, dl, g) in enumerate(((5274, 0.00, 0.05), (6645, 0.04, 0.04), (7902, 0.08, 0.03), (4186, 0.11, 0.035))):
    n = int(0.9 * SR)
    x = np.arange(n) / SR
    place(np.sin(2 * np.pi * fq * x) * np.exp(-x / 0.28) * np.clip(x / 0.002, 0, 1) * g, H + 0.02 + dl, (-1) ** k * 0.4)

# --- shine "shing" sweeping left to right ------------------------------------------------------------
n = int(0.75 * SR)
x = np.arange(n) / SR
ch = sum(np.sin(2 * np.pi * fq * x + i) * g for i, (fq, g) in enumerate(((2637, 0.5), (3951, 0.35), (5274, 0.22), (7040, 0.12))))
ch *= np.clip(x / 0.012, 0, 1) * np.exp(-x / 0.22)
nz = biquad(noise(n), "bp", 6500, 2.0) * np.exp(-x / 0.12) * 0.6
s = (ch + nz) * 0.20
pan = np.clip(-0.8 + 1.6 * x / 0.55, -0.8, 0.8)
i0 = int(1.14 * SR)
m = min(n, N - i0)
a = (pan[:m] + 1) * np.pi / 4
L[i0:i0 + m] += s[:m] * np.cos(a)
R[i0:i0 + m] += s[:m] * np.sin(a)

# --- closing shutter -------------------------------------------------------------------------------
place(click(1.15, 0.5), 2.00, 0.0, 0.32)

# --- room: short synthetic reverb ------------------------------------------------------------------
irn = int(0.7 * SR)
ir_t = np.arange(irn) / SR
for ch_ in (0, 1):
    ir = rng.standard_normal(irn) * np.exp(-ir_t / 0.18)
    ir = biquad(ir, "lp", 7000, 0.7)
    ir /= np.sqrt(np.sum(ir ** 2))
    src = L if ch_ == 0 else R
    wet = np.fft.irfft(np.fft.rfft(src, N + irn) * np.fft.rfft(ir, N + irn))[:N]
    if ch_ == 0:
        Lw = wet
    else:
        Rw = wet
L = L + 0.22 * Lw
R = R + 0.22 * Rw

# master: gentle limiter, end fade, normalise
mix = np.stack([L, R], axis=1)
mix *= np.clip((DUR - t) / 0.12, 0, 1)[:, None]
mix = np.tanh(mix * 1.6) / np.tanh(1.6)
mix *= 0.89 / np.max(np.abs(mix))
pcm = np.round(mix * 32767).astype("<i2")

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets")
pcm.tofile(os.path.join(out, "intro_sound.pcm"))
with wave.open(os.path.join(out, "intro_sound.wav"), "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print("samples", len(pcm), "peak", np.max(np.abs(pcm)))
