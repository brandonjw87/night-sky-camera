// Flash backscatter: dust motes near the lens light up as out-of-focus discs at the hero flash.
out vec2 vLocal;
out float vI;
out float vRot;
#define NPART 56

void main() {
    int pid = gl_VertexID / 6;
    int cid = gl_VertexID - pid * 6;
    vec2 corners[6] = vec2[6](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));
    vec2 corner = corners[cid];
    float t = uTime;
    vec3 ro; mat3 R;
    camera(t, ro, R);
    vec3 h = hash32(vec2(float(pid) * 1.37 + 3.1, 9.7));
    vec3 h2 = hash32(vec2(float(pid) * 2.11 + 7.9, 1.3));
    float D = ro.z;
    float zc = mix(1.6, D * 0.62, h.z * h.z);                 // distance in front of the camera
    float spanX = zc * camTanX() * 1.05, spanY = zc * camTanY() * 1.05;
    vec3 pw = vec3(ro.x + (h.x * 2.0 - 1.0) * spanX, (h.y * 2.0 - 1.0) * spanY + 0.3, D - zc);
    pw.xy += 0.10 * vec2(sin(t * 0.9 + h2.x * 6.28), cos(t * 0.7 + h2.y * 6.28));
    vec2 sp = projectPx(pw, ro);
    // circle of confusion: the letters are in focus, motes near the lens are big soft discs
    float coc = clamp(0.020 * uScreen.y * abs(zc - D) / zc, 6.0, 0.10 * uScreen.y) * mix(0.8, 1.25, h2.z);
    float I = heroI(t) * mix(0.25, 1.0, h2.x) * 0.42 / (1.0 + zc * zc * 0.05);
    I *= (34.0 / coc) * (34.0 / coc) * 0.9 + 0.025;
    // keep the name clear: dim discs whose centre falls on the lettering
    vec2 blk = vec2(0.5, 0.5 + 0.5 * lensShift()) * uScreen;
    vec2 hb = vec2(0.47 * uScreen.x, uScreen.x * mix(0.24, 0.09, uLandscape));
    vec2 dq = abs(sp - blk) / hb;
    I *= mix(0.05, 1.0, smoothstep(0.85, 1.25, max(dq.x, dq.y)));
    vI = I;
    vLocal = corner;
    vRot = h2.y * 6.28;
    vec2 ndc = (sp + corner * coc) / uScreen * 2.0 - 1.0;
    gl_Position = vec4(ndc, 0.0, 1.0);
    if (I < 0.0005) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}
