// Scene pass: night sky + shutter iris + raymarched 3D name + "MADE BY:" + flash sources -> HDR.
uniform sampler2D uNameSdf;   // R16F signed distance / spread
uniform sampler2D uLabels;    // R8 label SDF atlas
layout(location = 0) out vec4 oColor;

// =============================== 3D name =======================================================
float sd2(vec2 xy) {
    vec2 uv = (xy - uNameRect.xy) / uNameRect.zw;
    vec2 c = clamp(uv, 0.0, 1.0);
    float d = textureLod(uNameSdf, c, 0.0).r * uNameSpread;
    return d + length((uv - c) * uNameRect.zw);
}
float shapeF(float d2, float z) {
    float dz = abs(z) - HALF_DEPTH;
    return max(max(d2, dz), (d2 + dz + BEVEL) * 0.70710678);
}
float sdName(vec3 p) { return shapeF(sd2(p.xy), p.z); }

vec3 nameNormal(vec3 p) {
    float e = uNameEps;
    float c = sd2(p.xy);
    vec3 n = vec3(shapeF(sd2(p.xy + vec2(e, 0.0)), p.z) - shapeF(sd2(p.xy - vec2(e, 0.0)), p.z),
                  shapeF(sd2(p.xy + vec2(0.0, e)), p.z) - shapeF(sd2(p.xy - vec2(0.0, e)), p.z),
                  shapeF(c, p.z + e) - shapeF(c, p.z - e));
    return normalize(n);
}

// ---- lighting helpers ----
float D_GGX(float NoH, float a) { float a2 = a * a; float d = NoH * NoH * (a2 - 1.0) + 1.0; return a2 / (PI * d * d); }
float V_Smith(float NoV, float NoL, float a) {
    float a2 = a * a;
    float gv = NoL * sqrt(NoV * NoV * (1.0 - a2) + a2);
    float gl = NoV * sqrt(NoL * NoL * (1.0 - a2) + a2);
    return 0.5 / max(gv + gl, 1e-5);
}
vec3 F_Schlick(vec3 f0, float c) { return f0 + (1.0 - f0) * pow(1.0 - c, 5.0); }
vec3 specGGX(vec3 n, vec3 v, vec3 l, vec3 f0, float rough) {
    vec3 h = normalize(v + l);
    float NoL = sat(dot(n, l)), NoV = max(dot(n, v), 1e-3), NoH = sat(dot(n, h)), VoH = sat(dot(v, h));
    float a = rough * rough;
    return D_GGX(NoH, a) * V_Smith(NoV, NoL, a) * F_Schlick(f0, VoH) * NoL;
}

const vec3 KEY_DIR = normalize(vec3(-0.45, 0.62, 0.64));   // moonlight, upper left, from the front
const vec3 RIM_DIR = normalize(vec3(0.25, 0.80, -0.55));   // cool rim from behind and above

float keyLevel(float t) { return mix(0.80, 1.18, smoothstep(0.55, 1.10, t)); }

// studio-at-night environment for reflections (world direction)
vec3 envMap(vec3 r, float rough, float t) {
    float y = r.y;
    vec3 c = mix(vec3(0.010, 0.012, 0.022), vec3(0.050, 0.062, 0.105), smoothstep(-0.25, 0.85, y));
    float w = 0.05 + rough * 0.35;
    c += vec3(0.85, 0.92, 1.00) * 1.10 * exp(-pow((y - 0.10) / w, 2.0));
    c += vec3(1.00, 0.62, 0.38) * 0.32 * exp(-pow((y + 0.30) / (w * 1.8), 2.0));
    vec3 kd = normalize(vec3(-0.35, 0.45, 0.82));
    c += vec3(0.75, 0.82, 1.00) * 0.9 * pow(sat(dot(r, kd)), mix(60.0, 6.0, rough));
    // the light bar that sweeps across the letters after the flash
    float az = atan(r.x, r.z);
    float sp = mix(-0.95, 0.95, easeInOutCubic((t - 1.12) / 0.55));
    float on = smoothstep(1.08, 1.22, t) * (1.0 - smoothstep(1.60, 1.76, t));
    float sweep = exp(-pow((az - sp) / (0.05 + rough * 0.25), 2.0)) * on;
    c += vec3(1.00, 0.96, 0.90) * 3.6 * sweep * smoothstep(-0.7, -0.1, y) * (1.0 - smoothstep(0.45, 0.9, y));
    return c;
}

struct Shade { vec3 diff; vec3 coat; vec3 bev; vec3 side; };

void addLight(inout Shade s, vec3 n, vec3 v, vec3 l, vec3 lc, vec3 albedo, vec3 bevF0, vec3 sideF0) {
    float NoL = sat(dot(n, l));
    s.diff += albedo * lc * NoL;
    s.coat += specGGX(n, v, l, vec3(0.045), 0.20) * lc;
    s.bev  += specGGX(n, v, l, bevF0, 0.13) * lc;
    s.side += (specGGX(n, v, l, sideF0, 0.32) + sideF0 * 0.10 * NoL) * lc;
}

vec3 shadeName(vec3 p, vec3 nO, vec3 vO, mat3 R, vec3 ro, float t) {
    vec3 n = R * nO, v = R * vO, pw = R * p;
    float nz = nO.z;
    float wFace = smoothstep(0.88, 0.985, nz);
    float wSide = 1.0 - smoothstep(0.22, 0.48, nz);
    float wBev = sat(1.0 - wFace - wSide);

    float gy = sat(p.y / max(uNameInk.y, 0.5) + 0.5);
    vec3 albedo = vec3(0.86, 0.87, 0.90) * mix(vec3(1.0, 0.965, 0.93), vec3(0.965, 0.985, 1.0), gy);
    vec3 bevF0 = vec3(1.00, 0.79, 0.47);         // champagne gold chamfer
    vec3 sideF0 = vec3(0.40, 0.42, 0.48);        // dark steel walls

    Shade s = Shade(vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0));
    vec3 amb = mix(vec3(0.020, 0.017, 0.020), vec3(0.060, 0.075, 0.120), n.y * 0.5 + 0.5);
    s.diff = albedo * amb;
    float kl = keyLevel(t);
    addLight(s, n, v, KEY_DIR, vec3(0.70, 0.78, 1.00) * 1.15 * kl, albedo, bevF0, sideF0);
    addLight(s, n, v, RIM_DIR, vec3(0.40, 0.55, 1.00) * 0.9, albedo * 0.2, bevF0, sideF0);

    // paparazzi bursts: point lights in front of the letters
    for (int i = 0; i < 4; i++) {
        float I = flashI(i, t);
        if (I < 0.002) continue;
        vec3 ld = flashPos(i) - pw;
        float d2 = dot(ld, ld);
        addLight(s, n, v, ld * inversesqrt(d2), vec3(1.0, 0.975, 0.95) * I * 7.5 * 9.0 / (9.0 + d2), albedo, bevF0, sideF0);
    }
    // hero flash, mounted just above the lens
    float H = heroI(t);
    if (H > 0.002) {
        vec3 l = normalize(ro + vec3(0.0, 0.9, 0.0) - pw);
        addLight(s, n, v, l, vec3(1.0, 0.98, 0.96) * H * 4.6, albedo, bevF0, sideF0);
    }

    float NoV = max(dot(n, v), 1e-3);
    vec3 r = reflect(-v, n);
    vec3 face = s.diff + s.coat + F_Schlick(vec3(0.045), NoV) * envMap(r, 0.2, t);
    vec3 bev = s.bev + F_Schlick(bevF0, NoV) * envMap(r, 0.13, t);
    float depthK = mix(0.30, 1.0, sat((p.z + HALF_DEPTH) / (2.0 * HALF_DEPTH)));
    vec3 side = (s.side + F_Schlick(sideF0, NoV) * envMap(r, 0.32, t)) * depthK;
    float sx = mix(-0.62, 0.62, easeInOutSine((t - 1.10) / 0.58)) * uNameInk.x - 0.45 * p.y;
    float on = smoothstep(1.08, 1.18, t) * (1.0 - smoothstep(1.60, 1.72, t));
    float bar = exp(-pow((p.x - sx) / 0.32, 2.0)) * on;
    float core = exp(-pow((p.x - sx) / 0.07, 2.0)) * on;
    face += vec3(1.00, 0.97, 0.92) * (0.55 * bar + 0.60 * core);
    bev += bevF0 * (2.2 * bar + 3.0 * core);
    return face * wFace + bev * wBev + side * wSide;
}

// raymarch the extruded name; returns premultiplied colour + coverage
vec4 renderName(vec3 ro, vec3 rd, mat3 R, float t, float pixAng) {
    mat3 Rt = transpose(R);
    vec3 o = Rt * ro, d = Rt * rd;
    vec3 bmin = vec3(uNameRect.xy, -HALF_DEPTH), bmax = vec3(uNameRect.xy + uNameRect.zw, HALF_DEPTH);
    vec3 inv = 1.0 / d;
    vec3 t0 = (bmin - o) * inv, t1 = (bmax - o) * inv;
    vec3 tmn = min(t0, t1), tmx = max(t0, t1);
    float tn = max(max(tmn.x, tmn.y), tmn.z), tf = min(min(tmx.x, tmx.y), tmx.z);
    if (tn > tf || tf < 0.0) return vec4(0.0);
    float tt = max(tn, 0.0) + 1e-4;
    float bestR = 1e9, bestT = tt;
    bool hit = false;
    for (int i = 0; i < 72; i++) {
        vec3 p = o + d * tt;
        float s = sdName(p);
        float fp = tt * pixAng;
        float ratio = s / fp;
        if (ratio < bestR) { bestR = ratio; bestT = tt; }
        if (s < fp * 0.08) { hit = true; bestT = tt; break; }
        tt += s * 0.92;
        if (tt > tf) break;
    }
    float cov = hit ? 1.0 : sat(0.5 - bestR);
    if (cov <= 0.0) return vec4(0.0);
    vec3 p = o + d * bestT;
    vec3 n = nameNormal(p);
    vec3 col = shadeName(p, n, -d, R, ro, t);
    return vec4(col * cov, cov);
}

// "MADE BY:" — a flat engraved plate in the plane of the letter fronts
vec4 renderMadeBy(vec3 ro, vec3 rd, mat3 R, float t, out float shadow) {
    mat3 Rt = transpose(R);
    vec3 o = Rt * ro, d = Rt * rd;
    float tt = (HALF_DEPTH - o.z) / d.z;
    vec3 p = o + d * tt;
    float ls = labelScale();
    vec2 size = uMadeSize * ls;
    vec2 local = (p.xy - vec2(0.0, madeCenterY())) / size + 0.5;
    vec2 uv = uMadeUV.xy + clamp(local, 0.0, 1.0) * uMadeUV.zw;
    float sd = (texture(uLabels, uv).r - 0.5) * 2.0 * uLabelSpread * ls;   // name em
    float inside = step(0.0, local.x) * step(local.x, 1.0) * step(0.0, local.y) * step(local.y, 1.0);
    float aa = max(fwidth(sd), 1e-4);
    float a = sat(0.5 - sd / aa) * inside;
    float spr = uLabelSpread * ls;
    shadow = pow(sat(1.0 - sd / (spr * 0.95)), 1.5) * inside;
    vec3 n = R * vec3(0.0, 0.0, 1.0);
    vec3 pw = R * p;
    float light = 0.50 * keyLevel(t) * sat(dot(n, KEY_DIR)) + 0.40;
    for (int i = 0; i < 4; i++) {
        float I = flashI(i, t);
        vec3 ld = flashPos(i) - pw;
        light += I * 2.2 * sat(dot(n, normalize(ld))) * 9.0 / (9.0 + dot(ld, ld));
    }
    light += heroI(t) * 2.6;
    // brushed champagne metal: faint horizontal streaks
    float brush = 0.92 + 0.08 * vnoise(vec2(p.x * 40.0, p.y * 900.0));
    vec3 col = vec3(0.95, 0.86, 0.68) * light * brush;
    return vec4(col * a, a);
}

// =============================== night sky =====================================================
vec3 starColor(float h) {
    vec3 c1 = vec3(0.66, 0.78, 1.00), c2 = vec3(1.00, 0.97, 0.93), c3 = vec3(1.00, 0.78, 0.56);
    return h < 0.55 ? mix(c1, c2, h / 0.55) : mix(c2, c3, (h - 0.55) / 0.45);
}

// stars on rings around the celestial pole; each one draws its trail arc [ts, ts + dTheta]
vec3 starLayer(vec2 q, vec2 pole, float dr, float density, float gain, float dTheta, float sig, float seed, float defocus) {
    vec2 rel = q - pole;
    float r = length(rel);
    float th = atan(rel.y, rel.x);
    float rr = r / dr, ri = floor(rr), fr = rr - ri;
    float sgm = sig * (1.0 + 2.6 * defocus);
    float reach = 3.2 * sgm / dr;
    float k0 = fr < reach ? -1.0 : 0.0;
    float k1 = fr > 1.0 - reach ? 1.0 : 0.0;
    float dimDef = (1.0 + 1.2 * defocus) / ((1.0 + 2.6 * defocus) * (1.0 + 2.6 * defocus));
    vec3 acc = vec3(0.0);
    for (int k = -1; k <= 1; k++) {
        float fk = float(k);
        if (fk < k0 || fk > k1) continue;
        float ring = ri + fk;
        if (ring < 2.0) continue;
        float rc = (ring + 0.5) * dr;
        float dth = dr / rc;
        float jHi = floor(th / dth) + 1.0;
        float jLo = floor((th - dTheta) / dth) - 1.0;
        int n = int(min(jHi - jLo, 24.0));
        for (int m = 0; m <= 24; m++) {
            if (m > n) break;
            float j = jLo + float(m);
            vec2 cell = vec2(ring, j) + seed;
            vec3 h = hash32(cell);
            if (h.z > density) continue;
            float rs = (ring + 0.2 + 0.6 * h.x) * dr;
            float ts = (j + 0.2 + 0.6 * h.y) * dth;
            float hb = hash12(cell + 17.31);
            float b = gain * (0.045 + 2.6 * pow(hb, 11.0) + 0.22 * pow(hb, 3.0));
            float s = sgm * (0.85 + 0.9 * pow(hb, 6.0));
            float over = max(max(ts - th, th - (ts + dTheta)), 0.0) * r;
            float radial = r - rs;
            float dd = radial * radial + over * over;
            float along = dTheta > 1e-5 ? sat((th - ts) / dTheta) : 1.0;
            float trailK = dTheta > 1e-5 ? mix(0.16, 1.0, pow(along, 4.0)) : 1.0;
            acc += starColor(fract(hb * 7.13)) * b * trailK * exp(-dd / (2.0 * s * s));
        }
    }
    return acc * dimDef;
}

vec3 milkyWay(vec2 q, out float band) {
    vec2 core = vec2(-55.0, -430.0);
    vec2 dir = normalize(vec2(0.36, 0.93));
    vec2 rel = q - core;
    float along = dot(rel, dir);
    float across = dot(rel, vec2(-dir.y, dir.x));
    float w = 80.0 + 0.11 * max(along, 0.0);
    band = exp(-across * across / (2.0 * w * w));
    vec2 p = vec2(along, across) * 0.0046;
    float n1 = fbm(p + vec2(3.1, 1.7));
    float n2 = fbm(p * 2.6 + vec2(-1.3, 4.2));
    float lane = exp(-pow(across / (w * 0.42) - 0.25 * sin(along * 0.006), 2.0));
    float dust = smoothstep(0.40, 0.72, fbm(p * 1.7 + vec2(9.2, -3.3))) * lane;
    float coreGlow = exp(-dot(rel, rel) / (2.0 * 165.0 * 165.0));
    float dens = band * (0.30 + 1.0 * n1 * n1 + 0.40 * n2 * n2) * (1.0 - 0.85 * dust);
    vec3 cool = vec3(0.52, 0.60, 0.80), warm = vec3(1.00, 0.76, 0.52);
    vec3 c = mix(cool, warm, sat(coreGlow * 1.7 + 0.12)) * dens * 0.095;
    c += warm * coreGlow * 0.10 * (1.0 - 0.7 * dust);
    return c;
}

float ridge(float x, float seed) {
    float h = 0.0, a = 0.5, f = 0.0032;
    for (int i = 0; i < 5; i++) { h += a * (vnoise(vec2(x * f, seed)) * 2.0 - 1.0); f *= 2.13; a *= 0.48; }
    return h;
}

// shutter iris that opens onto the sky; returns blade colour and coverage
vec4 iris(vec2 q, vec2 c, float t) {
    float o = easeInOutCubic((t - 0.02) / 0.28);
    if (o >= 1.0) return vec4(0.0);
    float halfDiag = length(vec2(500.0 * uRes.x / uRes.y, 500.0)) + length(c);
    float R = mix(0.0, halfDiag * 1.08, o);
    float rot = 1.05 * o;
    vec2 p = q - c;
    float r = length(p);
    float a = atan(p.y, p.x) + rot;
    float seg = 2.0 * PI / 9.0;
    float am = mod(a, seg) - 0.5 * seg;
    float polyD = r * cos(am) - R * cos(0.5 * seg);
    float px = 1000.0 / uRes.y;
    float cov = smoothstep(-px, px, polyD);
    float sp = a + 0.85 * log((r + 30.0) / (R + 30.0));
    float bi = floor(sp / seg);
    float bf = fract(sp / seg);
    float tilt = hash12(vec2(bi, 4.0));
    vec3 base = vec3(0.028, 0.030, 0.036) * (0.7 + 0.6 * tilt);
    float sheen = pow(sat(cos(sp - 0.6 + rot * 2.0)), 6.0) * 0.12 + pow(sat(cos(sp * 2.0 + 1.9)), 12.0) * 0.05;
    base += vec3(0.50, 0.58, 0.72) * sheen * (0.4 + 0.6 * tilt);
    float sw = 0.010;
    base *= mix(0.2, 1.0, smoothstep(0.0, sw, bf));
    base += vec3(0.60, 0.70, 0.85) * 0.06 * exp(-pow((bf - sw * 1.6) / sw, 2.0));
    base += vec3(0.65, 0.78, 1.00) * 0.30 * exp(-max(polyD, 0.0) / (2.5 * px)) * step(0.0, polyD);
    return vec4(base, cov);
}

vec3 background(vec2 frag, float t, vec2 blockPx) {
    float e = easeOutCubic(t / T_END);
    float sh = camShake(t);
    vec2 q = (frag - 0.5 * uRes) / uRes.y * 1000.0;
    q += vec2(-9.0, 5.0) * e + 6.0 * sh * vec2(sin(t * 173.0), cos(t * 131.0));
    float px = 1000.0 / uRes.y;
    float landscapeX = mix(1.0, 0.55, uLandscape);

    // sky gradient
    float hgt = sat((q.y + 420.0) / 920.0);
    vec3 col = mix(vec3(0.030, 0.027, 0.052), vec3(0.004, 0.007, 0.021), pow(hgt, 0.55));

    // mountains
    float yFar = -318.0 + 72.0 * ridge(q.x * landscapeX + 25.0 * e + 300.0, 3.0);
    float yNear = -398.0 + 58.0 * ridge(q.x * 1.25 * landscapeX + 55.0 * e - 120.0, 11.0);
    float far = smoothstep(yFar + px, yFar - px, q.y);
    float near = smoothstep(yNear + px, yNear - px, q.y);

    if (far < 1.0) {
        float defocus = 1.0 - smoothstep(0.17, 0.25, t);
        float dTheta = 0.030 * easeOutQuart((t - 0.70) / 1.30);
        vec2 pole = mix(vec2(-520.0, 760.0), vec2(-950.0, 880.0), uLandscape);
        float band;
        vec3 mw = milkyWay(q, band);
        vec3 st = starLayer(q, pole, 9.0, 0.30 + 0.32 * band, 0.70, dTheta, 0.48, 0.0, defocus);
        st += starLayer(q, pole, 23.0, 0.45, 1.35, dTheta, 0.60, 41.0, defocus);
        col += (mw * (1.0 - 0.6 * defocus) + st);
        // airglow and a faint warm horizon glow
        col += vec3(0.020, 0.050, 0.030) * 0.55 * exp(-pow((q.y + 170.0) / 120.0, 2.0));
        col += vec3(0.16, 0.085, 0.060) * 0.42 * exp(-max(q.y - yFar, 0.0) / 75.0);
        col *= 1.0 - far;
    }
    vec3 farCol = mix(vec3(0.016, 0.019, 0.034), vec3(0.030, 0.032, 0.052), smoothstep(yFar - 60.0, yFar, q.y));
    col += farCol * far * (1.0 - near);
    col = mix(col, vec3(0.004, 0.004, 0.007), near);

    // iris
    vec4 ir = iris(q, (blockPx - 0.5 * uRes) / uRes.y * 1000.0, t);
    col = mix(col, ir.rgb, ir.a);

    // a soft shadow behind the lettering keeps the name standing out from the sky
    vec2 hq = (frag - blockPx) / (uRes * vec2(0.50, mix(0.105, 0.21, uLandscape)));
    col *= 1.0 - 0.62 * exp(-dot(hq, hq) * 1.6);
    // faint glow from a backlight burst behind the letters at the hero flash
    vec2 bq = (frag - blockPx) / uRes.y * 1000.0;
    float back = pulse(t, HERO_T, 0.02, 0.16) * 0.22 + pulse(t, HERO_T, 0.02, 0.6) * 0.04;
    col += vec3(0.55, 0.70, 1.00) * back * exp(-dot(bq * vec2(0.55, 1.6), bq * vec2(0.55, 1.6)) / (2.0 * 140.0 * 140.0));
    return col;
}

// bright flash heads of the paparazzi bursts, with 6-point diffraction spikes
vec3 flashSources(vec2 frag, vec3 ro, float t) {
    vec3 acc = vec3(0.0);
    float u = uRes.y * 0.001;
    for (int i = 0; i < 4; i++) {
        float I = flashI(i, t);
        if (I < 0.003) continue;
        vec2 sp = projectPx(flashPos(i), ro) * (uRes / uScreen);
        vec2 dv = (frag - sp) / u;
        float d = length(dv);
        float g = 55.0 * exp(-d * d / (2.0 * 1.6 * 1.6)) + 4.0 * exp(-d / 5.0) + 0.5 * exp(-d / 38.0);
        float spikes = 0.0;
        for (int k = 0; k < 3; k++) {
            float a = 0.26 + float(k) * PI / 3.0 + float(i) * 0.4;
            vec2 ax = vec2(cos(a), sin(a));
            float along = abs(dot(dv, ax));
            float perp = abs(dot(dv, vec2(-ax.y, ax.x)));
            spikes += exp(-perp * perp / (2.0 * 0.55 * 0.55)) * exp(-along / (55.0 * I + 8.0));
        }
        acc += vec3(1.0, 0.97, 0.93) * I * (g + 6.0 * spikes);
    }
    return acc;
}

void main() {
    vec2 frag = gl_FragCoord.xy;
    vec2 ndc = frag / uRes * 2.0 - 1.0;
    float t = uTime;
    vec3 ro; mat3 R;
    camera(t, ro, R);
    vec3 rd = cameraRay(ndc);
    float pixAng = 2.0 * camTanX() / uRes.x;

    vec2 blockPx = vec2(0.5, 0.5 + 0.5 * lensShift()) * uRes;
    vec4 nm = renderName(ro, rd, R, t, pixAng);
    float mbShadow;
    vec4 mb = renderMadeBy(ro, rd, R, t, mbShadow);
    vec3 col = vec3(0.0);
    if (nm.a < 0.999) {
        vec3 bg = background(frag, t, blockPx);
        bg *= (1.0 - 0.65 * mbShadow) * mix(1.0, 0.6, uNight);   // at night the sky steps back from the name
        bg = bg * (1.0 - mb.a) + mb.rgb;
        col = bg * (1.0 - nm.a);
    }
    col += nm.rgb;
    col += flashSources(frag, ro, t);
    oColor = vec4(col, max(nm.a, mb.a));
}
