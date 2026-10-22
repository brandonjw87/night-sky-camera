// Flash intro renderer (WebGL2). Mirrors the Android GLES 3.0 renderer pass for pass; all animation
// lives in the shared GLSL (common.glsl), so the host only runs passes and feeds uTime.
"use strict";

const INTRO_LEN = 2.2;

class FlashIntro {
  constructor(canvas, assets) {
    this.canvas = canvas;
    this.assets = assets; // { shaders: {name: src}, manifest, namePortrait: Uint16Array, nameLandscape, labels: Uint8Array }
    const gl = canvas.getContext("webgl2", { antialias: false, alpha: false, depth: false, stencil: false,
      premultipliedAlpha: false, preserveDrawingBuffer: true, powerPreference: "high-performance" });
    if (!gl) throw new Error("WebGL2 is not available");
    this.gl = gl;
    this.hdr = !!gl.getExtension("EXT_color_buffer_float");
    if (!this.hdr) gl.getExtension("EXT_color_buffer_half_float");
    this.vao = gl.createVertexArray();
    this.programs = {};
    this.targets = null;
    this.w = 0; this.h = 0;
    this.night = 0;       // 0 the standard look, 1 the red night look
    this._init();
  }

  _compile(type, src, name) {
    const gl = this.gl;
    const sh = gl.createShader(type);
    gl.shaderSource(sh, src);
    gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
      const log = gl.getShaderInfoLog(sh);
      throw new Error(`shader ${name}: ${log}`);
    }
    return sh;
  }

  _program(name, vsName, fsName) {
    const gl = this.gl, S = this.assets.shaders;
    const head = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n" + S["common.glsl"] + "\n";
    const p = gl.createProgram();
    gl.attachShader(p, this._compile(gl.VERTEX_SHADER, head + S[vsName], vsName));
    gl.attachShader(p, this._compile(gl.FRAGMENT_SHADER, head + S[fsName], fsName));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(`link ${name}: ${gl.getProgramInfoLog(p)}`);
    const u = {};
    const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
    for (let i = 0; i < n; i++) {
      const info = gl.getActiveUniform(p, i);
      u[info.name] = gl.getUniformLocation(p, info.name);
    }
    this.programs[name] = { p, u };
  }

  _tex(w, h, internal, format, type, data, filter, mips) {
    const gl = this.gl;
    const t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texImage2D(gl.TEXTURE_2D, 0, internal, w, h, 0, format, type, data);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, mips ? gl.LINEAR_MIPMAP_LINEAR : filter);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    if (mips) gl.generateMipmap(gl.TEXTURE_2D);
    return t;
  }

  _init() {
    const gl = this.gl, A = this.assets, M = A.manifest;
    this._program("scene", "fullscreen.vert", "scene.frag");
    this._program("particles", "particles.vert", "particles.frag");
    this._program("bright", "fullscreen.vert", "bright.frag");
    this._program("down", "fullscreen.vert", "down.frag");
    this._program("up", "fullscreen.vert", "up.frag");
    this._program("streak", "fullscreen.vert", "streak.frag");
    this._program("rays", "fullscreen.vert", "rays.frag");
    this._program("final", "fullscreen.vert", "final.frag");
    this.texPortrait = this._tex(M.portrait.w, M.portrait.h, gl.R16F, gl.RED, gl.HALF_FLOAT, A.namePortrait, gl.LINEAR, false);
    this.texLandscape = this._tex(M.landscape.w, M.landscape.h, gl.R16F, gl.RED, gl.HALF_FLOAT, A.nameLandscape, gl.LINEAR, false);
    this.texLabels = this._tex(M.labels.w, M.labels.h, gl.R8, gl.RED, gl.UNSIGNED_BYTE, A.labels, gl.LINEAR, true);
  }

  _rt(w, h) {
    const gl = this.gl;
    const fmt = this.hdr ? gl.RGBA16F : gl.RGBA8;
    const type = this.hdr ? gl.HALF_FLOAT : gl.UNSIGNED_BYTE;
    const tex = this._tex(w, h, fmt, gl.RGBA, type, null, gl.LINEAR, false);
    const fb = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    const ok = gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    if (!ok) throw new Error("render target incomplete");
    return { tex, fb, w, h };
  }

  _freeTargets() {
    if (!this.targets) return;
    const gl = this.gl;
    const all = [this.targets.scene, ...this.targets.down, ...this.targets.up, this.targets.stA, this.targets.stB, this.targets.rays];
    for (const r of all) { if (r) { gl.deleteTexture(r.tex); gl.deleteFramebuffer(r.fb); } }
    this.targets = null;
  }

  resize(w, h) {
    if (w === this.w && h === this.h && this.targets) return;
    this.w = w; this.h = h;
    this.canvas.width = w; this.canvas.height = h;
    this._freeTargets();
    const T = { scene: this._rt(w, h), down: [], up: [] };
    let dw = w, dh = h;
    for (let i = 0; i < 6; i++) {
      dw = Math.max(1, Math.round(dw / 2)); dh = Math.max(1, Math.round(dh / 2));
      T.down.push(this._rt(dw, dh));
    }
    for (let i = 0; i < 5; i++) T.up.push(this._rt(T.down[i].w, T.down[i].h));
    const q = T.down[1];
    T.stA = this._rt(q.w, q.h); T.stB = this._rt(q.w, q.h);
    T.rays = this._rt(q.w, q.h);
    this.targets = T;
  }

  _use(name, target, t) {
    const gl = this.gl, P = this.programs[name], M = this.assets.manifest;
    gl.useProgram(P.p);
    if (target) { gl.bindFramebuffer(gl.FRAMEBUFFER, target.fb); gl.viewport(0, 0, target.w, target.h); }
    else { gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.viewport(0, 0, this.w, this.h); }
    const u = P.u;
    const land = this.w > this.h * 1.15;
    const N = land ? M.landscape : M.portrait;
    const set1 = (k, v) => { if (u[k]) gl.uniform1f(u[k], v); };
    const set2 = (k, a, b) => { if (u[k]) gl.uniform2f(u[k], a, b); };
    const set4 = (k, a) => { if (u[k]) gl.uniform4f(u[k], a[0], a[1], a[2], a[3]); };
    set1("uTime", t);
    set2("uRes", target ? target.w : this.w, target ? target.h : this.h);
    set2("uScreen", this.w, this.h);
    set4("uNameRect", N.rect);
    set2("uNameInk", N.ink[0], N.ink[1]);
    set1("uNameSpread", N.spreadEm);
    set1("uNameEps", 0.7 / N.emTex);
    const L = M.labels;
    set4("uMadeUV", L.items.madeBy.uv);
    set2("uMadeSize", L.items.madeBy.sizeEm[0], L.items.madeBy.sizeEm[1]);
    set4("uHudUV", L.items.hud.uv);
    set2("uHudSize", L.items.hud.sizeEm[0], L.items.hud.sizeEm[1]);
    set1("uLabelSpread", L.spreadEm);
    set1("uLandscape", land ? 1 : 0);
    set1("uNight", this.night);
    P.land = land;
    return P;
  }

  _bindTex(P, name, unit, tex) {
    const gl = this.gl;
    if (!P.u[name]) return;
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.uniform1i(P.u[name], unit);
  }

  _fs() { this.gl.drawArrays(this.gl.TRIANGLES, 0, 3); }

  render(t) {
    const gl = this.gl, T = this.targets;
    t = Math.max(0, Math.min(INTRO_LEN, t));
    gl.bindVertexArray(this.vao);
    gl.disable(gl.BLEND);
    gl.disable(gl.DEPTH_TEST);

    // 1. scene
    let P = this._use("scene", T.scene, t);
    this._bindTex(P, "uNameSdf", 0, P.land ? this.texLandscape : this.texPortrait);
    this._bindTex(P, "uLabels", 1, this.texLabels);
    this._fs();

    // 2. flash backscatter discs (additive)
    if (t > 0.6 && t < 1.6) {
      P = this._use("particles", T.scene, t);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE);
      gl.colorMask(true, true, true, false);
      gl.drawArrays(gl.TRIANGLES, 0, 56 * 6);
      gl.colorMask(true, true, true, true);
      gl.disable(gl.BLEND);
    }

    // 3. bloom chain
    P = this._use("bright", T.down[0], t);
    this._bindTex(P, "uSrc", 0, T.scene.tex);
    gl.uniform2f(P.u.uSrcTexel, 1 / T.scene.w, 1 / T.scene.h);
    gl.uniform1f(P.u.uThreshold, 1.0);
    this._fs();
    for (let i = 1; i < 6; i++) {
      P = this._use("down", T.down[i], t);
      this._bindTex(P, "uSrc", 0, T.down[i - 1].tex);
      gl.uniform2f(P.u.uSrcTexel, 1 / T.down[i - 1].w, 1 / T.down[i - 1].h);
      this._fs();
    }
    for (let i = 4; i >= 0; i--) {
      const low = i === 4 ? T.down[5] : T.up[i + 1];
      P = this._use("up", T.up[i], t);
      this._bindTex(P, "uLow", 0, low.tex);
      this._bindTex(P, "uCur", 1, T.down[i].tex);
      gl.uniform2f(P.u.uLowTexel, 1 / low.w, 1 / low.h);
      this._fs();
    }

    // 4. anamorphic streak from the quarter-res bright level
    const q = T.down[1];
    const passes = [[1.0, T.down[1], T.stA, 1.0], [5.0, T.stA, T.stB, 1.0], [25.0, T.stB, T.stA, 2.2]];
    for (const [step, src, dst, gain] of passes) {
      P = this._use("streak", dst, t);
      this._bindTex(P, "uSrc", 0, src.tex);
      gl.uniform2f(P.u.uStep, step / q.w, 0);
      gl.uniform1f(P.u.uGain, gain);
      this._fs();
    }

    // 5. light shafts
    P = this._use("rays", T.rays, t);
    this._bindTex(P, "uScene", 0, T.scene.tex);
    const land = P.land;
    gl.uniform2f(P.u.uLight, 0.5, 0.5 + 0.5 * (land ? 0.03 : 0.075) + (land ? 0.0 : 0.01));
    this._fs();

    // 6. final
    P = this._use("final", null, t);
    this._bindTex(P, "uScene", 0, T.scene.tex);
    this._bindTex(P, "uBloom", 1, T.up[0].tex);
    this._bindTex(P, "uStreak", 2, T.stA.tex);
    this._bindTex(P, "uRays", 3, T.rays.tex);
    this._bindTex(P, "uLabels", 4, this.texLabels);
    this._fs();
  }
}

// helpers to unpack embedded data
async function gunzipB64(b64) {
  const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  const ds = new DecompressionStream("gzip");
  const buf = await new Response(new Blob([bin]).stream().pipeThrough(ds)).arrayBuffer();
  return buf;
}

if (typeof window !== "undefined") { window.FlashIntro = FlashIntro; window.gunzipB64 = gunzipB64; window.INTRO_LEN = INTRO_LEN; }
