// First bloom step: 13-tap downsample of the HDR scene with a soft-knee threshold.
uniform sampler2D uSrc;
uniform vec2 uSrcTexel;
uniform float uThreshold;
layout(location = 0) out vec4 oColor;
vec3 tap(vec2 uv) { return min(texture(uSrc, uv).rgb, vec3(40.0)); }
void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 o = uSrcTexel;
    vec3 a = tap(uv + o * vec2(-2.0, 2.0)), b = tap(uv + o * vec2(0.0, 2.0)), c = tap(uv + o * vec2(2.0, 2.0));
    vec3 d = tap(uv + o * vec2(-2.0, 0.0)), e = tap(uv), f = tap(uv + o * vec2(2.0, 0.0));
    vec3 g = tap(uv + o * vec2(-2.0, -2.0)), h = tap(uv + o * vec2(0.0, -2.0)), i = tap(uv + o * vec2(2.0, -2.0));
    vec3 j = tap(uv + o * vec2(-1.0, 1.0)), k = tap(uv + o * vec2(1.0, 1.0)), l = tap(uv + o * vec2(-1.0, -1.0)), m = tap(uv + o * vec2(1.0, -1.0));
    vec3 col = e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
    float br = max(col.r, max(col.g, col.b));
    float knee = uThreshold * 0.5;
    float rq = clamp(br - uThreshold + knee, 0.0, 2.0 * knee);
    rq = rq * rq / (4.0 * knee + 1e-4);
    float w = max(rq, br - uThreshold) / max(br, 1e-4);
    oColor = vec4(col * w, 1.0);
}
