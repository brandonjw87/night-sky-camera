#!/usr/bin/env python3
"""Builds app/src/main/assets/stars.csv from the Yale Bright Star Catalogue (5th revised edition,
Hoffleit & Warren 1991), as converted to JSON by github.com/brettonw/YaleBrightStarCatalog (bsc5-short.json,
and bsc5.json for the stars' B-V colour indices and spectral types), with the stars' distances from the HYG
database 3.7 (github.com/astronexus/HYG-Database, hyg/v3/hyg_v37.csv.gz: the Hipparcos new reduction's
parallaxes, van Leeuwen 2007; CC BY-SA), matched by the stars' HR numbers.

    tools/make_stars.py bsc5-short.json bsc5.json hyg_v37.csv.gz > app/src/main/assets/stars.csv

Columns: right ascension and declination (J2000, degrees), V magnitude, colour temperature (K, 0 if
unknown), constellation (IAU abbreviation), Bayer letter (Greek name) or Flamsteed number, proper name,
B-V colour index (empty if unknown); since 2.3, the distance in light-years (empty if unknown, or too far for
its parallax to say: beyond 1,500 parsecs) and the spectral type (the catalogue's, else HYG's; empty if unknown).
Without the HYG file the last two columns are left out, and the first eight are as before.
"""
import csv, gzip, json, sys

#: light-years in a parsec
LY_PER_PC = 3.261564
#: beyond this many parsecs a Hipparcos parallax says little (under 0.67 milliarcseconds)
MAX_PC = 1500

GREEK = {'α': 'alpha', 'β': 'beta', 'γ': 'gamma', 'δ': 'delta', 'ε': 'epsilon', 'ζ': 'zeta', 'η': 'eta',
         'θ': 'theta', 'ι': 'iota', 'κ': 'kappa', 'λ': 'lambda', 'μ': 'mu', 'ν': 'nu', 'ξ': 'xi',
         'ο': 'omicron', 'π': 'pi', 'ρ': 'rho', 'σ': 'sigma', 'τ': 'tau', 'υ': 'upsilon', 'φ': 'phi',
         'χ': 'chi', 'ψ': 'psi', 'ω': 'omega'}


def angle(s, hours):
    s = s.replace('h', ' ').replace('m', ' ').replace('s', ' ').replace('°', ' ').replace('′', ' ').replace('″', ' ')
    p = s.split()
    sign = -1 if p[0].startswith('-') else 1
    v = abs(float(p[0])) + float(p[1]) / 60 + float(p[2]) / 3600
    return sign * v * (15 if hours else 1)


def colours(path):
    """B-V by Harvard (HR) number, as printed in the catalogue ("+1.23"), and the spectral types."""
    out, types = {}, {}
    for s in json.load(open(path, encoding='utf-8')):
        bv = s.get('B-V', '').strip()
        if 'HR' in s and bv:
            try:
                out[str(s['HR'])] = '%.2f' % float(bv)
            except ValueError:
                pass
        sp = (s.get('SpectralCls', '') + s.get('LuminosityCls', '')).strip()
        if 'HR' in s and sp:
            types[str(s['HR'])] = sp
    return out, types


def distances(path):
    """HYG's distance (parsecs) and spectral type by HR number."""
    out = {}
    with gzip.open(path, 'rt', encoding='utf-8') if path.endswith('.gz') else open(path, encoding='utf-8') as f:
        for row in csv.DictReader(f):
            hr = row.get('hr', '').strip()
            if not hr:
                continue
            try:
                pc = float(row.get('dist', ''))
            except ValueError:
                pc = 0
            out[str(int(float(hr)))] = (pc, row.get('spect', '').strip())
    return out


def light_years(pc):
    """Three significant figures (two below ten light-years), or '' if unknown or too far to say."""
    if not (0 < pc <= MAX_PC):
        return ''
    ly = pc * LY_PER_PC
    digits = 2 if ly < 10 else 3
    return ('%.' + str(digits) + 'g') % ly if ly < 1000 else '%d' % int(float('%.3g' % ly))


def spectral(t):
    """A spectral type safe in a CSV line."""
    return t.replace(',', ' ').strip()


def main():
    stars = json.load(open(sys.argv[1], encoding='utf-8'))
    bvs, types = colours(sys.argv[2]) if len(sys.argv) > 2 else ({}, {})
    hyg = distances(sys.argv[3]) if len(sys.argv) > 3 else None
    out = []
    for s in stars:
        if 'RA' not in s or 'Dec' not in s or 'V' not in s:
            continue
        ra, dec, v = angle(s['RA'], True), angle(s['Dec'], False), float(s['V'])
        b = s.get('B', '')
        letter = ''
        if b:
            # Bayer letters may carry a superscript number (e.g. "α¹"): keep the letter and the number
            base = b[0]
            letter = GREEK.get(base, '') + b[1:].translate(str.maketrans('¹²³⁴⁵⁶⁷⁸⁹', '123456789'))
        elif 'F' in s:
            letter = s['F']
        name = s.get('N', '').replace(',', ' ')
        hr = str(s.get('HR', ''))
        bv = bvs.get(hr, '')
        line = '%.5f,%.5f,%.2f,%s,%s,%s,%s,%s' % (ra, dec, v, s.get('K', '0'), s.get('C', ''), letter, name, bv)
        extra = ''
        if hyg is not None:
            pc, sp = hyg.get(hr, (0, ''))
            extra = ',%s,%s' % (light_years(pc), spectral(types.get(hr, '') or sp))
        # sorted on the first eight columns, so the order is the same with or without the distances
        out.append((v, line, extra))
    out.sort()
    if hyg is None:
        print('# Yale Bright Star Catalogue 5th rev. ed. (Hoffleit & Warren 1991), via brettonw/YaleBrightStarCatalog; '
              'ra,dec (J2000 deg),V,K,constellation,Bayer/Flamsteed,name,B-V; brightest first')
    else:
        print('# Yale Bright Star Catalogue 5th rev. ed. (Hoffleit & Warren 1991), via brettonw/YaleBrightStarCatalog, '
              'with distances from the HYG database 3.7 (Hipparcos, CC BY-SA); ra,dec (J2000 deg),V,K,constellation,'
              'Bayer/Flamsteed,name,B-V,light-years,spectral type; brightest first')
    for _, line, extra in out:
        print(line + extra)


main()
