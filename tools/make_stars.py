#!/usr/bin/env python3
"""Builds app/src/main/assets/stars.csv from the Yale Bright Star Catalogue (5th revised edition,
Hoffleit & Warren 1991), as converted to JSON by github.com/brettonw/YaleBrightStarCatalog (bsc5-short.json).

    tools/make_stars.py bsc5-short.json > app/src/main/assets/stars.csv

Columns: right ascension and declination (J2000, degrees), V magnitude, colour temperature (K, 0 if
unknown), constellation (IAU abbreviation), Bayer letter (Greek name) or Flamsteed number, proper name.
"""
import json, sys

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


def main():
    stars = json.load(open(sys.argv[1], encoding='utf-8'))
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
        out.append((v, '%.5f,%.5f,%.2f,%s,%s,%s,%s' % (ra, dec, v, s.get('K', '0'), s.get('C', ''), letter, name)))
    out.sort()
    print('# Yale Bright Star Catalogue 5th rev. ed. (Hoffleit & Warren 1991), via brettonw/YaleBrightStarCatalog; '
          'ra,dec (J2000 deg),V,K,constellation,Bayer/Flamsteed,name; brightest first')
    for _, line in out:
        print(line)


main()
