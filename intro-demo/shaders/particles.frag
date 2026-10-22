in vec2 vLocal;
in float vI;
in float vRot;
layout(location = 0) out vec4 oColor;

// 9-blade aperture shaped bokeh with a bright rim and a little colour fringing
float bokeh(vec2 p) {
    float r = length(p);
    float a = atan(p.y, p.x) + vRot;
    float seg = 2.0 * PI / 9.0;
    float rp = r * cos(mod(a, seg) - 0.5 * seg) / cos(0.5 * seg);
    float body = smoothstep(1.0, 0.90, rp);
    return body * (0.62 + 0.38 * smoothstep(0.55, 0.97, rp));
}

void main() {
    vec3 c = vec3(bokeh(vLocal * 1.035), bokeh(vLocal), bokeh(vLocal * 0.965));
    oColor = vec4(c * vec3(1.0, 0.97, 0.92) * vI, 1.0);
}
