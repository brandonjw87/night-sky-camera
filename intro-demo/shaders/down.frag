uniform sampler2D uSrc;
uniform vec2 uSrcTexel;
layout(location = 0) out vec4 oColor;
void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 o = uSrcTexel;
    vec3 a = texture(uSrc, uv + o * vec2(-2.0, 2.0)).rgb, b = texture(uSrc, uv + o * vec2(0.0, 2.0)).rgb, c = texture(uSrc, uv + o * vec2(2.0, 2.0)).rgb;
    vec3 d = texture(uSrc, uv + o * vec2(-2.0, 0.0)).rgb, e = texture(uSrc, uv).rgb, f = texture(uSrc, uv + o * vec2(2.0, 0.0)).rgb;
    vec3 g = texture(uSrc, uv + o * vec2(-2.0, -2.0)).rgb, h = texture(uSrc, uv + o * vec2(0.0, -2.0)).rgb, i = texture(uSrc, uv + o * vec2(2.0, -2.0)).rgb;
    vec3 j = texture(uSrc, uv + o * vec2(-1.0, 1.0)).rgb, k = texture(uSrc, uv + o * vec2(1.0, 1.0)).rgb;
    vec3 l = texture(uSrc, uv + o * vec2(-1.0, -1.0)).rgb, m = texture(uSrc, uv + o * vec2(1.0, -1.0)).rgb;
    oColor = vec4(e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125, 1.0);
}
