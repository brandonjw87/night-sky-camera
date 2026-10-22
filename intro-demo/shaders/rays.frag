// Light shafts from a burst behind the letters: radial blur of the open (unoccluded) source.
uniform sampler2D uScene;   // alpha = letter coverage
uniform vec2 uLight;        // light centre in uv
layout(location = 0) out vec4 oColor;
void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float t = uTime;
    float I = pulse(t, HERO_T, 0.02, 0.20) + 0.25 * pulse(t, HERO_T, 0.02, 0.65);
    if (I < 0.002) { oColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec2 delta = (uv - uLight) * (0.85 / 40.0);
    vec2 p = uv;
    float acc = 0.0, decay = 1.0;
    vec2 ext = vec2(0.40, mix(0.065, 0.11, uLandscape));
    for (int i = 0; i < 40; i++) {
        vec2 dq = (p - uLight) / ext;
        float src = exp(-dot(dq, dq) * 1.4);
        float open = 1.0 - texture(uScene, p).a;
        acc += src * open * decay;
        decay *= 0.955;
        p -= delta;
    }
    oColor = vec4(vec3(acc / 40.0 * I), 1.0);
}
