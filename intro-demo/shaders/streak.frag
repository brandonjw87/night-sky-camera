// Anamorphic flare: wide horizontal blur of the bright pass, applied in a few growing steps.
uniform sampler2D uSrc;
uniform vec2 uStep;      // uv step between taps
uniform float uGain;
layout(location = 0) out vec4 oColor;
void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec3 c = vec3(0.0);
    float ws = 0.0;
    for (int i = -7; i <= 7; i++) {
        float w = exp(-abs(float(i)) * 0.30);
        c += texture(uSrc, uv + uStep * float(i)).rgb * w;
        ws += w;
    }
    oColor = vec4(c / ws * uGain, 1.0);
}
