"""The lettering's outlines: lines of text shaped with their font's own kerning, as closed cubic Bezier contours.

layout() gives the three lines of the lockup placed in the block's own flat coordinates (x right, y up, centred on
the block): MADE BY: small on top, BRANDON J. and WALLACE each as wide as the block.
"""
import os

import uharfbuzz as hb
from fontTools.pens.basePen import BasePen
from fontTools.ttLib import TTFont

import intro_common as C

# Inter (SIL Open Font License 1.1), as Debian's fonts-inter package installs it
INTER_DIR = os.environ.get('INTER_DIR', '/usr/share/fonts/opentype/inter')
FONT_NAME = os.path.join(INTER_DIR, 'InterDisplay-ExtraBold.otf')
FONT_SMALL = os.path.join(INTER_DIR, 'InterDisplay-SemiBold.otf')


class ContourPen(BasePen):
    """Collects contours as lists of cubic segments: (p0, c1, c2, p1)."""

    def __init__(self, glyph_set):
        BasePen.__init__(self, glyph_set)
        self.contours = []
        self.cur = None
        self.start = None
        self.last = None

    def _moveTo(self, p):
        self.cur = []
        self.start = p
        self.last = p

    def _lineTo(self, p):
        a = self.last
        if p != a:
            self.cur.append((a, None, None, p))
        self.last = p

    def _curveToOne(self, c1, c2, p):
        self.cur.append((self.last, c1, c2, p))
        self.last = p

    def _closePath(self):
        if self.cur is not None:
            if self.last != self.start:
                self.cur.append((self.last, None, None, self.start))
            if self.cur:
                self.contours.append(self.cur)
        self.cur = None

    _endPath = _closePath


def shape_line(path, text, tracking):
    """One line's contours in em units (baseline at y = 0, starting at x = 0), its ink box, and the cap height."""
    tt = TTFont(path)
    upm = tt['head'].unitsPerEm
    cap = getattr(tt['OS/2'], 'sCapHeight', 0) or 0.72 * upm
    glyph_set = tt.getGlyphSet()
    order = tt.getGlyphOrder()
    with open(path, 'rb') as f:
        face = hb.Face(f.read())
    font = hb.Font(face)
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf, {'kern': True, 'liga': False})
    contours = []
    x = 0.0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        name = order[info.codepoint]
        pen = ContourPen(glyph_set)
        glyph_set[name].draw(pen)
        ox = x + pos.x_offset
        for con in pen.contours:
            out = []
            for (p0, c1, c2, p1) in con:
                def tr(p):
                    return None if p is None else ((p[0] + ox) / upm, (p[1] + pos.y_offset) / upm)
                out.append((tr(p0), tr(c1), tr(c2), tr(p1)))
            contours.append(out)
        x += pos.x_advance + tracking * upm
    xs = [p[0] for con in contours for seg in con for p in seg if p is not None]
    ys = [p[1] for con in contours for seg in con for p in seg if p is not None]
    return contours, (min(xs), min(ys), max(xs), max(ys)), cap / upm


def place(contours, box, scale, x_centre, y_base):
    """Scale a line and put its ink box's middle at x_centre and its baseline at y_base."""
    mid = 0.5 * (box[0] + box[2])
    out = []
    for con in contours:
        o = []
        for seg in con:
            o.append(tuple(None if p is None else ((p[0] - mid) * scale + x_centre, p[1] * scale + y_base) for p in seg))
        out.append(o)
    return out


def layout():
    """The lockup: {'made': contours, 'name': contours (both name lines)}, and each line's box in block units."""
    w = C.BLOCK_WIDTH
    c1, b1, cap = shape_line(FONT_NAME, 'BRANDON J.', 0.035)
    c2, b2, _ = shape_line(FONT_NAME, 'WALLACE', 0.03)
    cm, bm, capm = shape_line(FONT_SMALL, 'MADE BY:', 0.42)
    s1 = w / (b1[2] - b1[0])
    s2 = w / (b2[2] - b2[0])
    sm = 0.40 * s1
    h1, h2, hm = cap * s1, cap * s2, capm * sm
    gap = 0.36 * h1
    gap_m = 0.62 * h1
    total = hm + gap_m + h1 + gap + h2
    y2 = -total / 2.0                 # WALLACE's baseline
    y1 = y2 + h2 + gap                # BRANDON J.'s baseline
    ym = y1 + h1 + gap_m              # MADE BY:'s baseline
    name = place(c1, b1, s1, 0.0, y1) + place(c2, b2, s2, 0.0, y2)
    made = place(cm, bm, sm, 0.0, ym)
    boxes = {'line1': (-w / 2, y1, w / 2, y1 + h1), 'line2': (-w / 2, y2, w / 2, y2 + h2),
             'made': (-(bm[2] - bm[0]) * sm / 2, ym, (bm[2] - bm[0]) * sm / 2, ym + hm), 'total': total}
    return {'name': name, 'made': made}, boxes


if __name__ == '__main__':
    lay, boxes = layout()
    print(boxes)
    print(len(lay['name']), 'contours in the name,', len(lay['made']), 'in MADE BY:')
