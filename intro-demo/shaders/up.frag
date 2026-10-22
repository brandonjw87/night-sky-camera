// Bloom upsample: 3x3 tent of the smaller level added onto this level.
uniform sampler2D uLow;
uniform sampler2D uCur;
uniform vec2 uLowTexel;
layout(location = 0) out vec4 oColor;
void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 o = uLowTexel;
    vec3 s = texture(uLow, uv).rgb * 4.0;
    s += (texture(uLow, uv + vec2(o.x, 0.0)).rgb + texture(uLow, uv - vec2(o.x, 0.0)).rgb
        + texture(uLow, uv + vec2(0.0, o.y)).rgb + texture(uLow, uv - vec2(0.0, o.y)).rgb) * 2.0;
    s += texture(uLow, uv + o).rgb + texture(uLow, uv - o).rgb + texture(uLow, uv + vec2(o.x, -o.y)).rgb + texture(uLow, uv + vec2(-o.x, o.y)).rgb;
    oColor = vec4(s / 16.0 + texture(uCur, uv).rgb, 1.0);
}
