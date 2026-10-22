// ---------------------------------------------------------------------------------------------
// Flash intro — shared header (GLSL ES 3.00). Same file on the web demo and in the Android app.
// Everything that moves is a pure function of uTime, so every pass agrees on the timeline.
// ---------------------------------------------------------------------------------------------
uniform float uTime;        // seconds since the intro started (0 .. T_END)
uniform vec2  uRes;         // size of the target this pass draws into (px)
uniform vec2  uScreen;      // size of the final screen (px)
uniform vec4  uNameRect;    // name SDF texture rectangle in object space (em): x0, y0, w, h
uniform vec2  uNameInk;     // ink size of the name (em)
uniform float uNameSpread;  // em per unit of stored SDF value
uniform float uNameEps;     // normal sampling step (em)
uniform vec4  uMadeUV;      // label atlas uv rect of "MADE BY:"
uniform vec2  uMadeSize;    // its rect size (label em)
uniform vec4  uHudUV;       // label atlas uv rect of the viewfinder readout
uniform vec2  uHudSize;     // its rect size (label em)
uniform float uLabelSpread; // label em per unit of (stored - 0.5) * 2
uniform float uLandscape;   // 0 portrait (two lines), 1 landscape (one line)

#define T_END      2.2
#define PI         3.14159265
#define HALF_DEPTH 0.118     // half thickness of the 3D letters (em)
#define BEVEL      0.056     // 45 degree chamfer on the letter edges (em)
#define HERO_T     0.640     // the big on-camera flash

float sat(float x) { return clamp(x, 0.0, 1.0); }
float easeOutCubic(float x) { x = sat(x); float y = 1.0 - x; return 1.0 - y * y * y; }
float easeOutQuart(float x) { x = sat(x); float y = 1.0 - x; return 1.0 - y * y * y * y; }
float easeInOutCubic(float x) { x = sat(x); return x < 0.5 ? 4.0 * x * x * x : 1.0 - pow(-2.0 * x + 2.0, 3.0) * 0.5; }
float easeInOutSine(float x) { x = sat(x); return 0.5 - 0.5 * cos(PI * x); }

// fast linear attack, exponential decay
float pulse(float t, float t0, float a, float d) {
    float x = t - t0;
    if (x < 0.0) return 0.0;
    return x < a ? x / a : exp(-(x - a) / d);
}

// ---- paparazzi bursts before the hero flash: (time, peak) and position --------------------------
const vec2 FLT[4] = vec2[4](vec2(0.340, 1.00), vec2(0.430, 0.85), vec2(0.505, 0.95), vec2(0.565, 0.80));
// x, y in units of the name's half ink size, z in em in front of the letters
const vec3 FLP[4] = vec3[4](vec3(-0.80, 2.70, 2.4), vec3(0.93, -1.20, 2.0), vec3(-0.55, -3.10, 2.6), vec3(0.60, 3.00, 2.2));

float flashI(int i, float t) { return FLT[i].y * pulse(t, FLT[i].x, 0.010, 0.046); }
vec3 flashPos(int i) {
    vec3 f = FLP[i];
    float hy = mix(uNameInk.y * 0.5, 0.75, uLandscape);
    return vec3(f.x * uNameInk.x * 0.5, f.y * hy + 0.1, f.z);
}
float heroI(float t) { return pulse(t, HERO_T, 0.014, 0.085) + 0.22 * pulse(t, HERO_T, 0.014, 0.42); }
float papSum(float t) { return flashI(0, t) + flashI(1, t) + flashI(2, t) + flashI(3, t); }

// ---- labels placement (object space, name em) --------------------------------------------------
float labelScale() { return mix(0.33, 0.30, uLandscape); }
float madeCenterY() { return uNameInk.y * 0.5 + mix(0.36, 0.30, uLandscape) + 0.35 * labelScale(); }

// ---- camera ------------------------------------------------------------------------------------
mat3 rotY(float a) { float c = cos(a), s = sin(a); return mat3(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c); }
mat3 rotX(float a) { float c = cos(a), s = sin(a); return mat3(1.0, 0.0, 0.0, 0.0, c, s, 0.0, -s, c); }

float camK() { return 1.9; }
float camTanX() { return 0.5 / (mix(0.86, 0.78, uLandscape) * camK()); }
float camTanY() { return camTanX() * uScreen.y / uScreen.x; }
float lensShift() { return mix(0.075, 0.03, uLandscape); }   // ndc: block sits a little above centre
float camShake(float t) { return step(HERO_T, t) * exp(-max(t - HERO_T, 0.0) / 0.085); }

// world: y up, camera on +z looking at -z, letters face +z. Object rotation R (world = R * obj).
void camera(float t, out vec3 ro, out mat3 R) {
    float e = easeOutCubic(t / T_END);
    float D = camK() * uNameInk.x * mix(1.10, 0.985, e);
    float sh = camShake(t);
    vec2 shake = 0.032 * sh * vec2(sin(t * 173.0), cos(t * 131.0)) * vec2(1.0, 0.7);
    ro = vec3(shake, D);
    float yaw = radians(mix(-15.0, 5.0, e)) + radians(1.6) * sh;
    float pitch = radians(mix(7.0, 1.5, e)) - radians(0.6) * sh;
    R = rotY(yaw) * rotX(pitch);
}

vec3 cameraRay(vec2 ndc) {
    return normalize(vec3(ndc.x * camTanX(), (ndc.y - lensShift()) * camTanY(), -1.0));
}

// world point -> screen pixel (of the final screen)
vec2 projectPx(vec3 pw, vec3 ro) {
    vec3 pc = pw - ro;
    float iz = 1.0 / max(-pc.z, 1e-3);
    vec2 ndc = vec2(pc.x * iz / camTanX(), pc.y * iz / camTanY() + lensShift());
    return (ndc * 0.5 + 0.5) * uScreen;
}

// ---- hashes / noise ----------------------------------------------------------------------------
float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec3 hash32(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yxz + 33.33); return fract((p3.xxy + p3.yzz) * p3.zyx); }
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i), b = hash12(i + vec2(1.0, 0.0)), c = hash12(i + vec2(0.0, 1.0)), d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(vec2 p) {
    float s = 0.0, a = 0.5;
    mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    for (int i = 0; i < 5; i++) { s += a * vnoise(p); p = m * p; a *= 0.5; }
    return s;
}
