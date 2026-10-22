#!/usr/bin/env python3
"""Builds the signed-distance-field textures for the flash intro.

Outputs (in ../assets):
  name_portrait.r16f   half-float SDF, rows bottom-to-top, value = signed distance / spread
  name_landscape.r16f
  labels.r8            8-bit SDF atlas for the flat texts, value = 0.5 + 0.5 * d / spread
  sdf_manifest.json    sizes and object-space rectangles (in name-em units)
  *_preview.png        quick visual checks

The name is laid out with HarfBuzz (kerning) in Montserrat ExtraBold and rasterised 4x
supersampled, then an exact Euclidean distance transform gives the field.
"""
import json
import os
import sys

import numpy as np
import uharfbuzz as hb
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "fonts", "Montserrat-VF.ttf")
OUT = os.path.join(HERE, "..", "assets")
SS = 4  # supersampling factor for the rasterised mask

NAME_WGHT = int(os.environ.get("NAME_WGHT", "800"))


def shape(text, wght, tracking_em):
    """Glyph pen positions (em) for one line, with kerning and extra tracking."""
    blob = hb.Blob.from_file_path(FONT)
    face = hb.Face(blob)
    font = hb.Font(face)
    font.set_variations({"wght": wght})
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf, {"kern": True, "liga": False})
    upem = face.upem
    out, x = [], 0.0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        out.append((text[info.cluster], x + pos.x_offset / upem, pos.y_offset / upem))
        x += pos.x_advance / upem + tracking_em
    return out, x - tracking_em


def raster_lines(lines, px_per_em):
    """lines: list of dicts(text, wght, tracking, scale, baseline_em). Returns mask + em transform."""
    # generous canvas; text is centred around the canvas middle, cropped later
    shaped = []
    max_w = 0.0
    for ln in lines:
        glyphs, width = shape(ln["text"], ln["wght"], ln["tracking"])
        shaped.append((ln, glyphs, width))
        max_w = max(max_w, width * ln["scale"])
    ys = [ln["baseline_em"] for ln in lines]
    cw = int((max_w + 2.0) * px_per_em)
    ch = int((max(ys) - min(ys) + 3.0) * px_per_em)
    img = Image.new("L", (cw, ch), 0)
    draw = ImageDraw.Draw(img)
    cx, cy = cw / 2.0, ch / 2.0
    fonts = {}
    for ln, glyphs, width in shaped:
        key = (ln["wght"], ln["scale"])
        if key not in fonts:
            f = ImageFont.truetype(FONT, int(round(px_per_em * ln["scale"])), layout_engine=ImageFont.Layout.BASIC)
            f.set_variation_by_axes([ln["wght"]])
            fonts[key] = f
        f = fonts[key]
        x0 = cx - width * ln["scale"] * px_per_em / 2.0
        base = cy - ln["baseline_em"] * px_per_em  # y down in image
        for chr_, gx, gy in glyphs:
            draw.text((x0 + gx * ln["scale"] * px_per_em, base - gy * ln["scale"] * px_per_em), chr_,
                      font=f, fill=255, anchor="ls")
    a = np.asarray(img, dtype=np.uint8)
    return a, cx, cy


def build_sdf(mask_u8, cx, cy, px_per_em, spread_tex, margin_tex, max_w=None):
    """Crop to ink + margin (whole texels), EDT, box-downsample by SS. Returns field (texels), rect (em)."""
    mask = mask_u8 >= 128
    ys, xs = np.nonzero(mask)
    x0, x1 = xs.min(), xs.max() + 1
    y0, y1 = ys.min(), ys.max() + 1
    m = margin_tex * SS
    # centre of the ink box becomes the object-space origin
    icx, icy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    wpx = (x1 - x0) + 2 * m
    hpx = (y1 - y0) + 2 * m
    wtex = int(np.ceil(wpx / SS))
    htex = int(np.ceil(hpx / SS))
    if wtex % 2:
        wtex += 1
    if htex % 2:
        htex += 1
    W, H = wtex * SS, htex * SS
    left = int(round(icx - W / 2.0))
    top = int(round(icy - H / 2.0))
    crop = np.zeros((H, W), dtype=bool)
    sx0, sy0 = max(left, 0), max(top, 0)
    sx1, sy1 = min(left + W, mask.shape[1]), min(top + H, mask.shape[0])
    crop[sy0 - top:sy1 - top, sx0 - left:sx1 - left] = mask[sy0:sy1, sx0:sx1]
    d_out = distance_transform_edt(~crop)
    d_in = distance_transform_edt(crop)
    sd = np.where(crop, -(d_in - 0.5), d_out - 0.5).astype(np.float32)
    # box downsample SSxSS -> texel, distances in texels
    sd = sd.reshape(htex, SS, wtex, SS).mean(axis=(1, 3)) / SS
    em_tex = px_per_em / SS
    # object space rect (y up), origin at ink centre
    ox = (left - icx) / px_per_em
    oy_top = (icy - top) / px_per_em  # top edge in y-up em
    rect = [ox, oy_top - H / px_per_em, W / px_per_em, H / px_per_em]
    ink = [(x1 - x0) / px_per_em, (y1 - y0) / px_per_em]
    return sd, rect, em_tex, wtex, htex, ink


def save_r16f(path, sd, spread_tex):
    v = np.clip(sd / spread_tex, -1.0, 1.0).astype(np.float16)
    v = v[::-1, :]  # bottom row first (GL texture convention)
    v.tofile(path)


def preview(path, sd, spread_tex):
    v = np.clip(0.5 - sd / (2 * spread_tex), 0, 1)
    edge = np.clip(1.0 - np.abs(sd) * 0.8, 0, 1)
    rgb = np.stack([v * 0.6 + edge * 0.4, v * 0.6 + edge * 0.4, v], axis=-1)
    Image.fromarray((rgb * 255).astype(np.uint8)).save(path)


def name_texture(tag, lines, target_w, spread_tex):
    # first pass at a nominal size to find the em width, then pick px/em so the texture fits target_w
    probe = 64.0
    a, cx, cy = raster_lines(lines, probe)
    ys, xs = np.nonzero(a >= 128)
    w_em = (xs.max() - xs.min() + 1) / probe
    margin_tex = spread_tex + 6
    em_tex = int((target_w - 2 * margin_tex - 4) / w_em)
    a, cx, cy = raster_lines(lines, em_tex * SS)
    sd, rect, em_tex, wtex, htex, ink = build_sdf(a, cx, cy, em_tex * SS, spread_tex, margin_tex)
    save_r16f(os.path.join(OUT, f"name_{tag}.r16f"), sd, spread_tex)
    preview(os.path.join(OUT, f"name_{tag}_preview.png"), sd, spread_tex)
    print(f"name_{tag}: {wtex}x{htex} em_tex={em_tex} rect={[round(r, 4) for r in rect]}")
    return {"w": wtex, "h": htex, "emTex": em_tex, "spreadEm": spread_tex / em_tex,
            "rect": [round(r, 6) for r in rect], "ink": [round(v, 6) for v in ink]}


def main():
    os.makedirs(OUT, exist_ok=True)
    trk = 0.02
    portrait = [
        {"text": "BRANDON J.", "wght": NAME_WGHT, "tracking": trk, "scale": 1.0, "baseline_em": 0.5},
        {"text": "WALLACE", "wght": NAME_WGHT, "tracking": trk, "scale": 1.0, "baseline_em": -0.5},
    ]
    landscape = [
        {"text": "BRANDON J. WALLACE", "wght": NAME_WGHT, "tracking": trk, "scale": 1.0, "baseline_em": 0.0},
    ]
    man = {"nameWght": NAME_WGHT}
    man["portrait"] = name_texture("portrait", portrait, 2048, 40)
    man["landscape"] = name_texture("landscape", landscape, 3072, 40)

    # flat label atlas: each item rasterised separately, stacked vertically in one R8 texture
    items = [
        ("madeBy", [{"text": "MADE BY:", "wght": 600, "tracking": 0.34, "scale": 1.0, "baseline_em": 0.0}]),
        ("hud", [{"text": "ƒ/1.4    2.2s    ISO 6400", "wght": 500, "tracking": 0.12, "scale": 1.0,
                  "baseline_em": 0.0}]),
    ]
    label_em_tex = 72
    spread_l = 10
    fields = []
    for key, lines in items:
        a, cx, cy = raster_lines(lines, label_em_tex * SS)
        sd, rect, em_tex, wtex, htex, ink = build_sdf(a, cx, cy, label_em_tex * SS, spread_l, spread_l + 4)
        fields.append((key, sd, rect, wtex, htex, ink))
    aw = max(f[3] for f in fields)
    aw = (aw + 3) // 4 * 4
    ah = sum(f[4] for f in fields)
    atlas = np.full((ah, aw), float(spread_l), dtype=np.float32)
    labels = {}
    y = 0
    for key, sd, rect, wtex, htex, ink in fields:
        atlas[y:y + htex, 0:wtex] = sd
        # uv rect in GL convention (v from bottom) -> rows are flipped on save
        v0 = (ah - (y + htex)) / ah
        labels[key] = {"uv": [0.0, v0, wtex / aw, htex / ah], "sizeEm": [rect[2], rect[3]], "ink": ink,
                       "inkCenterOffsetEm": [rect[0] + rect[2] / 2, rect[1] + rect[3] / 2]}
        y += htex
    v = np.clip(0.5 + 0.5 * atlas / spread_l, 0, 1)
    v8 = np.round(v * 255).astype(np.uint8)[::-1, :]
    v8.tofile(os.path.join(OUT, "labels.r8"))
    Image.fromarray(v8[::-1, :]).save(os.path.join(OUT, "labels_preview.png"))
    man["labels"] = {"w": aw, "h": ah, "emTex": label_em_tex, "spreadEm": spread_l / label_em_tex, "items": labels}
    with open(os.path.join(OUT, "sdf_manifest.json"), "w") as fh:
        json.dump(man, fh, indent=1)
    print(json.dumps(man["labels"], indent=1))


if __name__ == "__main__":
    main()
