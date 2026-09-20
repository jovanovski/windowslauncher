/*
 * The Windows 98 screen savers, carried over from winos (the web Windows 98 at
 * ~/Documents/priv_workspace/winos, index.html). Everything below the shim is that
 * file's SaverGL and Savers objects word for word, so the two stay diffable; only the
 * Screen Saver page of Display Properties was left behind, because this launcher draws
 * its own.
 *
 * Each saver's Setup dialog came across with it. Nothing calls those - the phone has no
 * Settings button next to the list - so every saver runs on the defaults it declares.
 *
 * The desktop these savers grew up in is stubbed out here. index.html decides which one
 * runs and how big a screen it should draw for.
 */

// Textures live in savers/ next to this script, which is what B + 'savers/...' spells out.
const B = '';

// Setup dialogs are the only thing that writes a saver's config, and there are none here,
// so every read falls through to the saver's own defaults and nothing is kept.
const store = { get: (key, fallback) => fallback, set() {} };

// The logo Flying Windows throws at you
const iconSrc = () => 'savers/windows.webp';

// Savers.picture() looks in here for a texture picked with "Choose Texture...". Nothing can
// pick one, so `id` is always empty and the saver's built-in texture is what loads.
const FS = {};
const UserFiles = { read: () => null };
const SAMPLE_PIC = '';

// In winos the little Display Properties monitor stops drawing while a saver has the whole
// screen; here each WebView is one or the other, so there is nothing to stand down for.
const Saver = { el: null };

// UI.k is the desktop's zoom (1 on a phone) and UI.vw() the screen width in CSS pixels.
// A preview is the whole screen in miniature, so it asks for the real screen's width -
// index.html passes that in as vw, since the preview WebView is only a few hundred pixels wide.
const UI = { k: 1, vw: () => window.SAVER_VW || innerWidth };

const SaverGL = {
    VS: `attribute vec3 aPos; attribute vec3 aNrm; attribute vec2 aUV; attribute vec3 aCol;
uniform mat4 uP, uMV; uniform mat3 uN;
varying vec3 vPos, vNrm, vCol; varying vec2 vUV;
void main() { vec4 p = uMV * vec4(aPos, 1.0); vPos = p.xyz; vNrm = uN * aNrm; vUV = aUV; vCol = aCol; gl_Position = uP * p; }`,
    FS: `precision mediump float;
uniform int uMode;
uniform vec3 uGAmb, uKa, uKd, uKs, uKe, uBKd, uLA0, uLD0, uLS0, uLA1, uLD1, uLS1;
uniform vec4 uLP0, uLP1;
uniform float uShin, uTwo, uBack, uVCol, uFlat, uTexOn, uAlpha, uAlphaCut, uPalRot;
uniform sampler2D uTex, uPal;
varying vec3 vPos, vNrm, vCol; varying vec2 vUV;
vec3 lite(vec4 lp, vec3 la, vec3 ld, vec3 ls, vec3 N, vec3 ka, vec3 kd) {
    vec3 L = normalize(lp.xyz - vPos * lp.w);
    float d = dot(N, L);
    vec3 c = la * ka;
    if (d > 0.0) c += ld * kd * d + ls * uKs * pow(max(dot(N, normalize(L + vec3(0.0, 0.0, 1.0))), 0.0001), uShin);
    return c;
}
void main() {
    vec4 tex = vec4(1.0);
    if (uMode == 3) {
        float i = floor(texture2D(uTex, vUV).r * 255.0 + 0.5);
        tex = vec4(texture2D(uPal, vec2((mod(i + uPalRot, 256.0) + 0.5) / 256.0, 0.5)).rgb, 1.0);
    } else if (uTexOn > 0.5) tex = texture2D(uTex, vUV);
    if (tex.a < uAlphaCut) discard;
    vec3 col;
    if (uMode == 0) {
        vec3 N = normalize(vNrm);
        vec3 kd = uVCol > 0.5 ? vCol : uKd;
        vec3 ka = uKa;
#ifdef FLAT
        if (uFlat > 0.5) { N = normalize(cross(dFdx(vPos), dFdy(vPos))); if (!gl_FrontFacing && uTwo < 0.5) N = -N; }
        else
#endif
        if (!gl_FrontFacing && uTwo > 0.5) N = -N;
        if (!gl_FrontFacing && uBack > 0.5) { kd = uBKd; ka = uBKd; }
        col = uKe + uGAmb * ka + lite(uLP0, uLA0, uLD0, uLS0, N, ka, kd) + lite(uLP1, uLA1, uLD1, uLS1, N, ka, kd);
        col = min(col, vec3(1.0)) * tex.rgb;
    } else if (uMode == 1) col = (uVCol > 0.5 ? vCol : uKd) * tex.rgb;
    else col = tex.rgb;
    gl_FragColor = vec4(col, tex.a * uAlpha);
}`,

    context(canvas, o = {}) {
        const attrs = { alpha: false, depth: true, antialias: o.antialias !== false, stencil: !!o.stencil, preserveDrawingBuffer: !!o.preserve, powerPreference: 'low-power' };
        try { return canvas.getContext('webgl', attrs) || canvas.getContext('experimental-webgl', attrs); } catch (e) { return null; }
    },

    program(gl) {
        const flat = !!gl.getExtension('OES_standard_derivatives');
        const shader = (type, src) => {
            const s = gl.createShader(type);
            gl.shaderSource(s, src);
            gl.compileShader(s);
            return s;
        };
        const p = gl.createProgram();
        gl.attachShader(p, shader(gl.VERTEX_SHADER, this.VS));
        gl.attachShader(p, shader(gl.FRAGMENT_SHADER, (flat ? '#extension GL_OES_standard_derivatives : enable\n#define FLAT 1\n' : '') + this.FS));
        ['aPos', 'aNrm', 'aUV', 'aCol'].forEach((n, i) => gl.bindAttribLocation(p, i, n));
        gl.linkProgram(p);
        gl.useProgram(p);
        const locs = {};
        const loc = n => n in locs ? locs[n] : (locs[n] = gl.getUniformLocation(p, n));
        const P = {
            gl,
            f(n, ...v) { const l = loc(n); if (l) gl['uniform' + v.length + 'f'](l, ...v); return P; },
            i(n, v) { const l = loc(n); if (l) gl.uniform1i(l, v); return P; },
            matrices(proj, mv) {
                gl.uniformMatrix4fv(loc('uP'), false, proj);
                gl.uniformMatrix4fv(loc('uMV'), false, mv);
                gl.uniformMatrix3fv(loc('uN'), false, SaverGL.m4.normal(mv));
                return P;
            },
            // lights: [{ pos: [x, y, z, w] in eye space, amb, dif, spc }], gamb: GL_LIGHT_MODEL_AMBIENT
            lights(list, gamb) {
                P.f('uGAmb', ...gamb);
                for (let i = 0; i < 2; i++) {
                    const L = list[i] || { pos: [0, 0, 1, 0], amb: [0, 0, 0], dif: [0, 0, 0], spc: [0, 0, 0] };
                    let [x, y, z, w] = L.pos;
                    if (!w) { const len = Math.hypot(x, y, z) || 1; x /= len; y /= len; z /= len; }
                    P.f('uLP' + i, x, y, z, w).f('uLA' + i, ...(L.amb || [0, 0, 0])).f('uLD' + i, ...(L.dif || [1, 1, 1])).f('uLS' + i, ...(L.spc || [1, 1, 1]));
                }
                return P;
            },
            // Everything a draw call depends on, so no state leaks from the previous object
            set(o = {}) {
                let mode = o.mode || 0;
                gl.activeTexture(gl.TEXTURE0);
                if (o.tex && o.tex.pal) {
                    gl.bindTexture(gl.TEXTURE_2D, o.tex.idx);
                    gl.activeTexture(gl.TEXTURE1);
                    gl.bindTexture(gl.TEXTURE_2D, o.tex.pal);
                    gl.activeTexture(gl.TEXTURE0);
                    mode = 3;
                    P.f('uPalRot', Math.floor(o.palRot || 0) % 256);
                } else if (o.tex) gl.bindTexture(gl.TEXTURE_2D, o.tex);
                P.i('uMode', mode).f('uTexOn', o.tex ? 1 : 0).f('uAlphaCut', o.alphaCut || 0).f('uAlpha', o.alpha == null ? 1 : o.alpha)
                    .f('uFlat', o.flat ? 1 : 0).f('uTwo', o.two ? 1 : 0).f('uVCol', o.vcol ? 1 : 0).f('uBack', o.back ? 1 : 0);
                if (o.back) P.f('uBKd', ...o.back);
                const m = o.mat || { ka: [0.2, 0.2, 0.2], kd: [0.8, 0.8, 0.8] };
                P.f('uKa', ...m.ka).f('uKd', ...m.kd).f('uKs', ...(m.ks || [0, 0, 0])).f('uShin', m.shin || 0).f('uKe', ...(m.ke || [0, 0, 0]));
                return P;
            },
            free() { gl.deleteProgram(p); }
        };
        P.i('uTex', 0).i('uPal', 1);
        return P;
    },

    // Vertex buffers for positions, normals, texture coordinates and colours, optionally indexed
    mesh(gl) {
        const bufs = [0, 1, 2, 3].map(() => gl.createBuffer()), ib = gl.createBuffer();
        const SIZES = [3, 3, 2, 3], DEFS = [[0, 0, 0], [0, 0, 1], [0, 0], [1, 1, 1]];
        const big = !!gl.getExtension('OES_element_index_uint');
        const m = {
            count: 0, has: [false, false, false, false], indexed: false, wide: false,
            set(d) {
                [d.pos, d.nrm, d.uv, d.col].forEach((arr, i) => {
                    m.has[i] = !!arr;
                    if (!arr) return;
                    gl.bindBuffer(gl.ARRAY_BUFFER, bufs[i]);
                    gl.bufferData(gl.ARRAY_BUFFER, arr instanceof Float32Array ? arr : new Float32Array(arr), gl.DYNAMIC_DRAW);
                });
                m.indexed = !!d.idx;
                if (d.idx) {
                    m.wide = big && d.pos.length / 3 > 65535;
                    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ib);
                    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, m.wide ? new Uint32Array(d.idx) : new Uint16Array(d.idx), gl.DYNAMIC_DRAW);
                    m.count = d.idx.length;
                } else m.count = d.pos.length / 3;
                return m;
            },
            draw(mode) {
                if (!m.count) return;
                for (let i = 0; i < 4; i++) {
                    if (m.has[i]) {
                        gl.bindBuffer(gl.ARRAY_BUFFER, bufs[i]);
                        gl.enableVertexAttribArray(i);
                        gl.vertexAttribPointer(i, SIZES[i], gl.FLOAT, false, 0, 0);
                    } else {
                        gl.disableVertexAttribArray(i);
                        gl['vertexAttrib' + SIZES[i] + 'f'](i, ...DEFS[i]);
                    }
                }
                if (m.indexed) {
                    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ib);
                    gl.drawElements(mode == null ? gl.TRIANGLES : mode, m.count, m.wide ? gl.UNSIGNED_INT : gl.UNSIGNED_SHORT, 0);
                } else gl.drawArrays(mode == null ? gl.TRIANGLES : mode, 0, m.count);
            },
            free() { bufs.forEach(b => gl.deleteBuffer(b)); gl.deleteBuffer(ib); }
        };
        return m;
    },

    // Indexed triangle builder
    geo() {
        const g = {
            p: [], n: [], t: [], c: [], i: [],
            v(x, y, z, nx, ny, nz, u = 0, w = 0) { g.p.push(x, y, z); g.n.push(nx, ny, nz); g.t.push(u, w); return g.p.length / 3 - 1; },
            tri(a, b, c) { g.i.push(a, b, c); },
            quad(a, b, c, d) { g.i.push(a, b, c, a, c, d); },
            // A quad wound so it faces along n (so back-face culling keeps the right side)
            quadN(a, b, c, d, n) {
                const P = i => [g.p[i * 3], g.p[i * 3 + 1], g.p[i * 3 + 2]];
                const [pa, pb, pc, pd] = [a, b, c, d].map(P);
                const u = [pc[0] - pa[0], pc[1] - pa[1], pc[2] - pa[2]], v = [pd[0] - pb[0], pd[1] - pb[1], pd[2] - pb[2]];
                const cr = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]];
                if (cr[0] * n[0] + cr[1] * n[1] + cr[2] * n[2] < 0) g.quad(a, d, c, b); else g.quad(a, b, c, d);
            },
            data() { return { pos: new Float32Array(g.p), nrm: new Float32Array(g.n), uv: new Float32Array(g.t), col: g.c.length ? new Float32Array(g.c) : null, idx: g.i }; }
        };
        return g;
    },

    // A texture from a URL, an image or a promise of one. Shows 1×1 grey until it arrives.
    // WebGL 1 only repeats power-of-two textures; the NT savers resampled to powers of two too.
    texture(gl, src, o = {}) {
        const tex = gl.createTexture();
        const params = (nearest, mip) => {
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, nearest ? gl.NEAREST : gl.LINEAR);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, nearest ? gl.NEAREST : mip ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
        };
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(o.fill || [128, 128, 128, 255]));
        params(true);
        tex.w = tex.h = 1;
        const put = img => {
            if (!img || gl.isContextLost()) return;
            const pot = n => Math.min(512, 2 ** Math.max(0, Math.round(Math.log2(n))));
            const w = pot(img.width), hh = pot(img.height);
            let pic = img;
            if (w !== img.width || hh !== img.height) {
                pic = document.createElement('canvas');
                pic.width = w;
                pic.height = hh;
                const x = pic.getContext('2d');
                x.imageSmoothingEnabled = !o.nearest;
                x.drawImage(img, 0, 0, w, hh);
            }
            gl.bindTexture(gl.TEXTURE_2D, tex);
            gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, pic);
            gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
            params(o.nearest, !o.nearest);
            if (!o.nearest) gl.generateMipmap(gl.TEXTURE_2D);
            tex.w = img.width;
            tex.h = img.height;
            if (o.onload) o.onload(tex);
        };
        if (typeof src === 'string') SaverGL.image(src).then(put);
        else if (src && src.then) src.then(put);
        else put(src);
        return tex;
    },

    image(url) {
        return new Promise(res => {
            const im = new Image();
            im.onload = () => res(im);
            im.onerror = () => res(null);
            im.src = url;
        });
    },

    // The maze's fractal textures: 8-bit indices plus a palette that rotates every frame.
    // The file keeps the palette in its first row.
    palette(gl, url) {
        const idx = gl.createTexture(), pal = gl.createTexture();
        for (const t of [idx, pal]) {
            gl.bindTexture(gl.TEXTURE_2D, t);
            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 255]));
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, t === pal ? gl.CLAMP_TO_EDGE : gl.REPEAT);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, t === pal ? gl.CLAMP_TO_EDGE : gl.REPEAT);
        }
        const T = { idx, pal, w: 1, h: 1 };
        SaverGL.image(url).then(im => {
            if (!im || gl.isContextLost()) return;
            const c = document.createElement('canvas');
            c.width = im.width;
            c.height = im.height;
            const x = c.getContext('2d', { willReadFrequently: true });
            x.drawImage(im, 0, 0);
            const d = x.getImageData(0, 0, c.width, c.height).data;
            const w = c.width, hh = c.height - 1;
            const P = new Uint8Array(256 * 3), I = new Uint8Array(w * hh);
            for (let i = 0; i < 256; i++) P.set([d[i * 4], d[i * 4 + 1], d[i * 4 + 2]], i * 3);
            // Bottom row first, as OpenGL stores it
            for (let y = 0; y < hh; y++) for (let xx = 0; xx < w; xx++) I[(hh - 1 - y) * w + xx] = d[((y + 1) * w + xx) * 4];
            gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
            gl.bindTexture(gl.TEXTURE_2D, idx);
            gl.texImage2D(gl.TEXTURE_2D, 0, gl.LUMINANCE, w, hh, 0, gl.LUMINANCE, gl.UNSIGNED_BYTE, I);
            gl.bindTexture(gl.TEXTURE_2D, pal);
            gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, 256, 1, 0, gl.RGB, gl.UNSIGNED_BYTE, P);
            gl.pixelStorei(gl.UNPACK_ALIGNMENT, 4);
            T.w = w;
            T.h = hh;
        });
        return T;
    },

    // Column-major 4×4 matrices; translate/rotate/scale post-multiply like glTranslate and friends
    m4: {
        id() { return new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]); },
        mul(a, b) {
            const o = new Float32Array(16);
            for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) {
                let s = 0;
                for (let k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
                o[c * 4 + r] = s;
            }
            return o;
        },
        persp(fovy, aspect, n, f) {
            const t = 1 / Math.tan(fovy * Math.PI / 360);
            return new Float32Array([t / aspect, 0, 0, 0, 0, t, 0, 0, 0, 0, (f + n) / (n - f), -1, 0, 0, 2 * f * n / (n - f), 0]);
        },
        frustum(l, r, b, t, n, f) {
            return new Float32Array([2 * n / (r - l), 0, 0, 0, 0, 2 * n / (t - b), 0, 0, (r + l) / (r - l), (t + b) / (t - b), (f + n) / (n - f), -1, 0, 0, 2 * f * n / (n - f), 0]);
        },
        ortho(l, r, b, t, n, f) {
            return new Float32Array([2 / (r - l), 0, 0, 0, 0, 2 / (t - b), 0, 0, 0, 0, -2 / (f - n), 0, -(r + l) / (r - l), -(t + b) / (t - b), -(f + n) / (f - n), 1]);
        },
        lookAt(ex, ey, ez, cx, cy, cz, ux, uy, uz) {
            let fx = cx - ex, fy = cy - ey, fz = cz - ez;
            const fl = Math.hypot(fx, fy, fz) || 1;
            fx /= fl; fy /= fl; fz /= fl;
            let sx = fy * uz - fz * uy, sy = fz * ux - fx * uz, sz = fx * uy - fy * ux;
            const sl = Math.hypot(sx, sy, sz) || 1;
            sx /= sl; sy /= sl; sz /= sl;
            const vx = sy * fz - sz * fy, vy = sz * fx - sx * fz, vz = sx * fy - sy * fx;
            const m = new Float32Array([sx, vx, -fx, 0, sy, vy, -fy, 0, sz, vz, -fz, 0, 0, 0, 0, 1]);
            return this.translate(m, -ex, -ey, -ez);
        },
        translate(m, x, y, z) { return this.mul(m, new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1])); },
        rotate(m, deg, x, y, z) {
            const l = Math.hypot(x, y, z) || 1;
            x /= l; y /= l; z /= l;
            const a = deg * Math.PI / 180, c = Math.cos(a), s = Math.sin(a), t = 1 - c;
            return this.mul(m, new Float32Array([t * x * x + c, t * x * y + s * z, t * x * z - s * y, 0, t * x * y - s * z, t * y * y + c, t * y * z + s * x, 0,
                t * x * z + s * y, t * y * z - s * x, t * z * z + c, 0, 0, 0, 0, 1]));
        },
        scale(m, x, y, z) { return this.mul(m, new Float32Array([x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1])); },
        // Inverse transpose of the upper 3×3, for normals
        normal(m) {
            const a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], hh = m[9], i = m[10];
            const C00 = e * i - hh * f, C01 = -(b * i - hh * c), C02 = b * f - e * c;
            const C10 = -(d * i - g * f), C11 = a * i - g * c, C12 = -(a * f - d * c);
            const C20 = d * hh - g * e, C21 = -(a * hh - g * b), C22 = a * e - d * b;
            const det = (a * C00 + d * C01 + g * C02) || 1;
            return new Float32Array([C00 / det, C10 / det, C20 / det, C01 / det, C11 / det, C21 / det, C02 / det, C12 / det, C22 / det]);
        },
        apply(m, x, y, z, w = 1) {
            return [m[0] * x + m[4] * y + m[8] * z + m[12] * w, m[1] * x + m[5] * y + m[9] * z + m[13] * w, m[2] * x + m[6] * y + m[10] * z + m[14] * w, m[3] * x + m[7] * y + m[11] * z + m[15] * w];
        }
    },

    hsv(h, s = 1, v = 1) {
        h = ((h % 360) + 360) % 360 / 60;
        const i = Math.floor(h), f = h - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        return [[v, t, p], [q, v, p], [p, v, t], [p, q, v], [t, p, v], [v, p, q]][i % 6];
    },

    // Materials from the aux "teapots" demo: ambient, diffuse, specular, shininess (×128)
    TEA: [
        [0.0215, 0.1745, 0.0215, 0.07568, 0.61424, 0.07568, 0.633, 0.727811, 0.633, 0.6], [0.135, 0.2225, 0.1575, 0.54, 0.89, 0.63, 0.316228, 0.316228, 0.316228, 0.1],
        [0.05375, 0.05, 0.06625, 0.18275, 0.17, 0.22525, 0.332741, 0.328634, 0.346435, 0.3], [0.25, 0.20725, 0.20725, 1, 0.829, 0.829, 0.296648, 0.296648, 0.296648, 0.088],
        [0.1745, 0.01175, 0.01175, 0.61424, 0.04136, 0.04136, 0.727811, 0.626959, 0.626959, 0.6], [0.1, 0.18725, 0.1745, 0.396, 0.74151, 0.69102, 0.297254, 0.30829, 0.306678, 0.1],
        [0.329412, 0.223529, 0.027451, 0.780392, 0.568627, 0.113725, 0.992157, 0.941176, 0.807843, 0.21794872], [0.2125, 0.1275, 0.054, 0.714, 0.4284, 0.18144, 0.393548, 0.271906, 0.166721, 0.2],
        [0.25, 0.25, 0.25, 0.4, 0.4, 0.4, 0.774597, 0.774597, 0.774597, 0.6], [0.19125, 0.0735, 0.0225, 0.7038, 0.27048, 0.0828, 0.256777, 0.137622, 0.086014, 0.1],
        [0.24725, 0.1995, 0.0745, 0.75164, 0.60648, 0.22648, 0.628281, 0.555802, 0.366065, 0.4], [0.19225, 0.19225, 0.19225, 0.50754, 0.50754, 0.50754, 0.508273, 0.508273, 0.508273, 0.4],
        [0, 0, 0, 0.01, 0.01, 0.01, 0.5, 0.5, 0.5, 0.25], [0, 0.1, 0.06, 0, 0.50980392, 0.50980392, 0.50196078, 0.50196078, 0.50196078, 0.25],
        [0, 0, 0, 0.1, 0.35, 0.1, 0.45, 0.55, 0.45, 0.25], [0, 0, 0, 0.5, 0, 0, 0.7, 0.6, 0.6, 0.25],
        [0, 0, 0, 0.55, 0.55, 0.55, 0.7, 0.7, 0.7, 0.25], [0, 0, 0, 0.5, 0.5, 0, 0.6, 0.6, 0.5, 0.25],
        [0.02, 0.02, 0.02, 0.01, 0.01, 0.01, 0.4, 0.4, 0.4, 0.078125], [0, 0.05, 0.05, 0.4, 0.5, 0.5, 0.04, 0.7, 0.7, 0.078125],
        [0, 0.05, 0, 0.4, 0.5, 0.4, 0.04, 0.7, 0.04, 0.078125], [0.05, 0, 0, 0.5, 0.4, 0.4, 0.7, 0.04, 0.04, 0.078125],
        [0.05, 0.05, 0.05, 0.5, 0.5, 0.5, 0.7, 0.7, 0.7, 0.078125], [0.05, 0.05, 0, 0.5, 0.5, 0.4, 0.7, 0.7, 0.04, 0.078125]
    ],
    // Emerald, jade, pearl, ruby, turquoise, brass, bronze, copper, gold, silver, cyan/white/yellow plastic, cyan/green/white rubber
    GOOD: [0, 1, 3, 4, 5, 6, 7, 9, 10, 11, 13, 16, 17, 19, 20, 22],
    // Near-white materials used under a texture
    WHITES: [[0.2, 0.2, 0.2, 1, 1, 1, 1, 1, 1, 0.5], [0.2, 0.2, 0.2, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 0.5], [0.3, 0.2, 0.2, 1, 0.9, 0.8, 1, 0.9, 0.8, 0.5], [0.2, 0.2, 0.3, 0.8, 0.9, 1, 0.8, 0.9, 1, 0.5]],
    material(d) { return { ka: d.slice(0, 3), kd: d.slice(3, 6), ks: d.slice(6, 9), shin: d[9] * 128 }; },
    randomTea() { return this.material(this.TEA[this.GOOD[Math.floor(Math.random() * this.GOOD.length)]]); },
    randomWhite() { return this.material(this.WHITES[Math.floor(Math.random() * 4)]); },

    // The small window the savers draw in, drifting around the screen and bouncing off the edges.
    // size() gives its side in pixels; speed is the step per update as a fraction of that side.
    floater(env, speed, onBounce) {
        const f = {
            x: -1, y: 0, w: 1, h: 1, vx: 0, vy: 0, inc: 1,
            fit(side, aspect = 1) {
                let w = Math.max(1, Math.min(side, env.w)), hh = w;
                if (aspect > 1) hh = w / aspect; else w = hh * aspect;
                hh = Math.min(hh, env.h);
                f.w = Math.round(w);
                f.h = Math.round(hh);
                f.inc = Math.max(1, speed * side);
                if (f.x < 0) {
                    f.x = Math.random() * Math.max(0, env.w - f.w);
                    f.y = Math.random() * Math.max(0, env.h - f.h);
                    f.vx = f.inc * (Math.random() < 0.5 ? -1 : 1);
                    f.vy = f.inc * (Math.random() < 0.5 ? -1 : 1);
                }
                f.x = Math.max(0, Math.min(f.x, env.w - f.w));
                f.y = Math.max(0, Math.min(f.y, env.h - f.h));
            },
            step(k) {
                const vary = () => f.inc * (0.6 + Math.random() * 0.8);
                let bounce = false;
                f.x += f.vx * k;
                f.y += f.vy * k;
                if (f.x < 0) { f.x = 0; f.vx = vary(); bounce = true; }
                else if (f.x > env.w - f.w) { f.x = Math.max(0, env.w - f.w); f.vx = -vary(); bounce = true; }
                if (f.y < 0) { f.y = 0; f.vy = vary(); bounce = true; }
                else if (f.y > env.h - f.h) { f.y = Math.max(0, env.h - f.h); f.vy = -vary(); bounce = true; }
                if (bounce && onBounce) onBounce();
            },
            // GL's window origin is bottom-left
            viewport(gl) { gl.viewport(Math.round(f.x), Math.round(env.h - f.y - f.h), f.w, f.h); }
        };
        return f;
    }
};

// Each saver: defaults, create(env) → { frame(dt), resize(), destroy() }, and its Setup dialog
const Savers = {
    impl: {},

    cfg(kind) {
        const d = this.impl[kind] ? this.impl[kind].defaults : {};
        return Object.assign(JSON.parse(JSON.stringify(d)), store.get('saver.' + kind, {}));
    },
    save(kind, cfg) { store.set('saver.' + kind, cfg); },

    // Runs a saver on a canvas until the returned function is called.
    // env.w/env.h are the canvas size in device pixels; env.unit is device pixels per emulated screen pixel.
    run(canvas, kind, preview, cfg, real) {
        const def = this.impl[kind] || this.impl.blank;
        const env = { canvas, preview, kind, cfg: cfg || this.cfg(kind), w: 0, h: 0, unit: 1, gl: null, ctx: null, fixed: null };
        if (def.gl) {
            env.gl = SaverGL.context(canvas, def.glAttrs);
            // Without WebGL the 3D savers can't run; Mystify keeps the screen moving instead
            if (!env.gl) {
                const c = canvas.cloneNode();
                canvas.replaceWith(c);
                return this.run(c, 'mystify', preview);
            }
        } else env.ctx = canvas.getContext('2d');
        let inst = null, raf = 0, last = 0, dead = false;
        const fit = () => {
            const r = canvas.getBoundingClientRect();
            if (!r.width || !r.height) return false;
            let w, hh;
            if (env.fixed) [w, hh] = env.fixed;
            else {
                let s = Math.min(3, window.devicePixelRatio || 1);
                if (def.maxPixels) s = Math.min(s, Math.sqrt(def.maxPixels / (r.width * r.height)));
                w = Math.max(1, Math.round(r.width * s));
                hh = Math.max(1, Math.round(r.height * s));
            }
            canvas.style.imageRendering = env.fixed ? 'pixelated' : '';
            if (w === env.w && hh === env.h) return true;
            canvas.width = env.w = w;
            canvas.height = env.h = hh;
            // A preview is the whole screen in miniature; real=true draws at the screen's own scale
            env.unit = real ? w / (r.width / UI.k) : w / UI.vw();
            if (inst && inst.resize) inst.resize();
            return true;
        };
        const tick = t => {
            if (dead) return;
            raf = requestAnimationFrame(tick);
            // The little monitor rests while a saver has the whole screen
            if (preview && Saver.el && Saver.el !== canvas.parentNode) { last = 0; return; }
            if (!fit()) return;
            const dt = last ? Math.min(0.1, Math.max(0, (t - last) / 1000)) : 0;
            last = t;
            if (env.gl && env.gl.isContextLost()) return;
            if (!inst) inst = def.create(env);
            inst.frame(dt);
        };
        raf = requestAnimationFrame(tick);
        return () => {
            dead = true;
            cancelAnimationFrame(raf);
            if (inst && inst.destroy) inst.destroy();
            if (env.gl) {
                const lose = env.gl.getExtension('WEBGL_lose_context');
                if (lose) lose.loseContext();
            }
        };
    },

    // A texture chosen with "Choose Texture...", or the saver's own when there is none or it can't be read
    picture(id, fallback) {
        const load = async () => {
            const n = id && FS[id];
            if (n) {
                try {
                    const blob = n.local ? await UserFiles.read(n) : id === 'photo' ? await (await fetch(SAMPLE_PIC)).blob() : null;
                    if (blob && blob.size) {
                        const url = URL.createObjectURL(blob);
                        const im = await SaverGL.image(url);
                        URL.revokeObjectURL(url);
                        if (im) return im;
                    }
                } catch (e) { /* fall back to the built-in texture */ }
            }
            return fallback ? SaverGL.image(fallback) : null;
        };
        return load();
    },

    // Opens a saver's Setup dialog; done() runs after OK so the preview can restart
    setup(kind, done) {
        const def = this.impl[kind];
        if (def && def.setup) def.setup(this.cfg(kind), cfg => { this.save(kind, cfg); if (done) done(); });
    }
};

/* ---- Blank Screen ---- */
Savers.impl.blank = {
    defaults: {},
    create(env) {
        return { frame() { env.ctx.fillStyle = '#000'; env.ctx.fillRect(0, 0, env.w, env.h); } };
    }
};

/* ---- Flying Windows and Flying Through Space (the Windows 3.1 starfield) ---- */
const saverWarp = (kind, logos) => ({
    defaults: { warp: 5, density: logos ? 20 : 200 },
    create(env) {
        const x = env.ctx, cfg = env.cfg;
        const logo = logos ? new Image() : null;
        if (logo) logo.src = iconSrc('windows');
        const spawn = (p, far) => Object.assign(p, { x: (Math.random() - 0.5) * 2, y: (Math.random() - 0.5) * 2, z: far ? 1 : Math.random() * 0.9 + 0.1 });
        const pts = Array.from({ length: cfg.density }, () => spawn({}, false));
        const speed = 0.12 + cfg.warp * (logos ? 0.035 : 0.06);
        return {
            frame(dt) {
                const w = env.w, hh = env.h, f = Math.min(w, hh) * 0.5, F = Math.max(w, hh) * 0.5, u = env.unit;
                x.fillStyle = '#000';
                x.fillRect(0, 0, w, hh);
                x.imageSmoothingEnabled = false;
                for (const p of pts) {
                    p.z -= dt * speed;
                    const sx = w / 2 + p.x / p.z * f, sy = hh / 2 + p.y / p.z * f;
                    if (p.z <= 0.02 || sx < -80 * u || sy < -80 * u || sx > w + 80 * u || sy > hh + 80 * u) { spawn(p, true); continue; }
                    const near = 1 - p.z;
                    if (logos) {
                        // The logo bitmap grows in steps as it comes closer, as 3.1's did
                        const s = Math.max(2, Math.round(Math.min(F * 0.3, F * 0.012 / (p.z * p.z)) / 4) * 4);
                        if (logo.complete && logo.naturalWidth) x.drawImage(logo, sx - s / 2, sy - s / 2, s, s);
                    } else {
                        const s = Math.max(1, Math.round((near < 0.5 ? 1 : near < 0.8 ? 2 : 3) * u));
                        const c = Math.round(110 + 145 * Math.min(1, near * 1.4));
                        x.fillStyle = `rgb(${c},${c},${c})`;
                        x.fillRect(Math.round(sx), Math.round(sy), s, s);
                    }
                }
            }
        };
    },
    setup(cfg, done) {
        const warp = SaverUI.slider({ min: 0, max: 10, value: cfg.warp, ticks: 11, label: 'Warp speed', onchange: v => (cfg.warp = v) });
        const density = SaverUI.spin({ value: cfg.density, min: 10, max: 200, width: 44, label: 'Density', onchange: v => (cfg.density = v) });
        SaverUI.setup({
            id: 'ss-setup', title: logos ? 'Flying Windows Setup' : 'Flying Through Space Setup', width: 330,
            content: h('div', null,
                SaverUI.group('&Warp speed', SaverUI.range('Slow', 'Fast', warp)),
                h('label', { class: 'ss-line' }, SaverUI.lbl(logos ? '&Density of Windows:' : 'Starfield &density:'), density)),
            onOk: () => done(cfg)
        });
    }
});
Savers.impl.flying = saverWarp('flying', true);
Savers.impl.starfield = saverWarp('starfield', false);

/* ---- Mystify Your Mind ---- */
Savers.impl.mystify = {
    defaults: { shapes: [{ active: true, lines: 5, two: false, c1: '#ff0000', c2: '#0000ff' }, { active: true, lines: 5, two: false, c1: '#00ff00', c2: '#ffff00' }], clear: true },
    create(env) {
        const x = env.ctx, cfg = env.cfg, RATE = 25;
        const bright = () => SaverGL.hsv(Math.random() * 360, 0.6 + Math.random() * 0.4, 1).map(v => Math.round(v * 255));
        const hex = c => [1, 3, 5].map(i => parseInt(c.slice(i, i + 2), 16));
        const vel = () => (3 + Math.random() * 7) * (Math.random() < 0.5 ? -1 : 1);
        const polys = cfg.shapes.filter(s => s.active).map((s, n) => ({
            s, hist: [], t: 0, n,
            pts: Array.from({ length: 4 }, () => ({ x: Math.random(), y: Math.random(), vx: vel(), vy: vel() })),
            from: s.two ? hex(s.c1) : bright(), to: s.two ? hex(s.c2) : bright(), useSecond: true
        }));
        let acc = 0, wiped = false;
        const step = p => {
            const u = env.unit, w = env.w, hh = env.h;
            for (const q of p.pts) {
                q.x += q.vx * u / w;
                q.y += q.vy * u / hh;
                if (q.x < 0) { q.x = 0; q.vx = Math.abs(vel()); } else if (q.x > 1) { q.x = 1; q.vx = -Math.abs(vel()); }
                if (q.y < 0) { q.y = 0; q.vy = Math.abs(vel()); } else if (q.y > 1) { q.y = 1; q.vy = -Math.abs(vel()); }
            }
            // The colour drifts to a new one, then picks the next
            if (++p.t > 60) {
                p.t = 0;
                p.from = p.to;
                p.useSecond = !p.useSecond;
                p.to = p.s.two ? hex(p.useSecond ? p.s.c2 : p.s.c1) : bright();
            }
            const k = p.t / 60, c = p.from.map((v, i) => Math.round(v + (p.to[i] - v) * k));
            p.hist.unshift({ pts: p.pts.map(q => [q.x, q.y]), color: `rgb(${c})` });
            p.hist.length = Math.min(p.hist.length, p.s.lines);
        };
        const draw = (snap, lw) => {
            x.strokeStyle = snap.color;
            x.lineWidth = lw;
            x.beginPath();
            snap.pts.forEach(([px, py], i) => x[i ? 'lineTo' : 'moveTo'](Math.round(px * env.w) + 0.5, Math.round(py * env.h) + 0.5));
            x.closePath();
            x.stroke();
        };
        return {
            resize() { wiped = false; },
            frame(dt) {
                const lw = Math.max(1, Math.round(env.unit));
                if (!wiped || cfg.clear) { x.fillStyle = '#000'; x.fillRect(0, 0, env.w, env.h); wiped = true; }
                acc += dt;
                let n = 0;
                while (acc >= 1 / RATE && n++ < 4) {
                    acc -= 1 / RATE;
                    polys.forEach(step);
                    // Without "Clear screen" nothing is erased and the old lines pile up
                    if (!cfg.clear) polys.forEach(p => p.hist[0] && draw(p.hist[0], lw));
                }
                if (cfg.clear) polys.forEach(p => p.hist.forEach(snap => draw(snap, lw)));
            }
        };
    },
    setup(cfg, done) {
        let cur = 0;
        const shape = h('select', { class: 'field', 'aria-label': 'Shape' }, cfg.shapes.map((s, i) => h('option', { value: i }, `Polygon ${i + 1}`)));
        const active = checkbox(SaverUI.lbl('&Active'), true, v => { cfg.shapes[cur].active = v; sync(); });
        const lines = SaverUI.spin({ value: 5, min: 1, max: 15, width: 36, label: 'Lines', onchange: v => (cfg.shapes[cur].lines = v) });
        const two = radio('ss-myst-col', SaverUI.lbl('&Two colors'), false, 'two');
        const multi = radio('ss-myst-col', SaverUI.lbl('&Multiple random colors'), true, 'multi');
        const c1 = SaverUI.colorCombo({ value: '#ff0000', names: false, width: 50, label: 'First color', onchange: v => (cfg.shapes[cur].c1 = v) });
        const c2 = SaverUI.colorCombo({ value: '#0000ff', names: false, width: 50, label: 'Second color', onchange: v => (cfg.shapes[cur].c2 = v) });
        const clear = checkbox(SaverUI.lbl('C&lear screen'), cfg.clear, v => (cfg.clear = v));
        two.querySelector('input').addEventListener('change', () => { cfg.shapes[cur].two = true; sync(); });
        multi.querySelector('input').addEventListener('change', () => { cfg.shapes[cur].two = false; sync(); });
        // Replacing a control keeps the dialog in step with the polygon being edited
        const load = () => {
            const s = cfg.shapes[cur];
            active.querySelector('input').checked = s.active;
            lines.input.value = s.lines;
            two.querySelector('input').checked = s.two;
            multi.querySelector('input').checked = !s.two;
            const r1 = SaverUI.colorCombo({ value: s.c1, names: false, width: 50, label: 'First color', onchange: v => (cfg.shapes[cur].c1 = v) });
            const r2 = SaverUI.colorCombo({ value: s.c2, names: false, width: 50, label: 'Second color', onchange: v => (cfg.shapes[cur].c2 = v) });
            swatches.replaceChildren(r1, r2);
            sync();
        };
        const swatches = h('span', { class: 'ss-pair' }, c1, c2);
        const sync = () => {
            const s = cfg.shapes[cur], off = !s.active;
            lines.disable(off);
            two.querySelector('input').disabled = off;
            multi.querySelector('input').disabled = off;
            swatches.querySelectorAll('.ss-colors').forEach(c => c.disable(off || !s.two));
        };
        shape.addEventListener('change', () => { cur = +shape.value; load(); });
        load();
        SaverUI.setup({
            id: 'ss-setup', title: 'Mystify Your Mind Setup', width: 350,
            content: h('div', null,
                SaverUI.group('Shape',
                    h('div', { class: 'ss-line' }, h('div', { class: 'combo', style: { width: '110px' } }, shape), active),
                    h('label', { class: 'ss-line' }, SaverUI.lbl('&Lines:'), lines),
                    SaverUI.group('Colors', h('div', { class: 'ss-stack' }, h('div', { class: 'ss-line' }, two, swatches), multi))),
                h('div', { class: 'ss-line' }, clear)),
            onOk: () => done(cfg)
        });
    }
};

/* ---- Scrolling Marquee ---- */
Savers.impl.marquee = {
    defaults: { centered: true, speed: 5, bg: '#000000', text: 'Windows 98', font: { face: 'Times New Roman', bold: false, italic: false, size: 36, underline: false, strike: false, color: '#ffffff' } },
    create(env) {
        const x = env.ctx, cfg = env.cfg, f = cfg.font;
        let pos = null, y = 0.5, textW = 0, px = 0;
        const place = () => {
            px = Math.max(4, f.size * 4 / 3 * env.unit);
            x.font = `${f.italic ? 'italic ' : ''}${f.bold ? 'bold ' : ''}${px}px "${f.face}", Arial, sans-serif`;
            textW = x.measureText(cfg.text || ' ').width;
        };
        const again = () => {
            pos = env.w;
            // Random picks the top, middle or bottom of the screen for each pass
            y = cfg.centered ? 0.5 : 0.15 + Math.random() * 0.7;
        };
        return {
            resize() { place(); },
            frame(dt) {
                if (pos === null) { place(); again(); }
                x.fillStyle = cfg.bg;
                x.fillRect(0, 0, env.w, env.h);
                pos -= dt * (25 + cfg.speed * 22) * env.unit;
                if (pos < -textW) again();
                place();
                x.fillStyle = f.color;
                x.textBaseline = 'middle';
                const ty = Math.round(env.h * y);
                x.fillText(cfg.text, Math.round(pos), ty);
                const lh = Math.max(1, px / 16);
                if (f.underline) x.fillRect(Math.round(pos), ty + px * 0.38, textW, lh);
                if (f.strike) x.fillRect(Math.round(pos), ty, textW, lh);
            }
        };
    },
    setup(cfg, done) {
        let stop = null;
        const canvas = h('canvas', { class: 'ss-example-canvas' });
        const restart = () => {
            if (stop) stop();
            // The example shows the text at its real size, scrolling through a strip of the screen
            stop = Savers.run(canvas, 'marquee', true, Object.assign({}, cfg, { centered: true }), true);
        };
        const centered = radio('ss-mq-pos', SaverUI.lbl('C&entered'), cfg.centered, 'c');
        const random = radio('ss-mq-pos', SaverUI.lbl('&Random'), !cfg.centered, 'r');
        centered.querySelector('input').addEventListener('change', () => (cfg.centered = true));
        random.querySelector('input').addEventListener('change', () => (cfg.centered = false));
        const speed = SaverUI.slider({ min: 0, max: 10, value: cfg.speed, ticks: 11, label: 'Speed', onchange: v => { cfg.speed = v; restart(); } });
        const bg = SaverUI.colorCombo({ value: cfg.bg, width: 118, label: 'Background Color', onchange: v => { cfg.bg = v; restart(); } });
        const text = h('input', { class: 'field', type: 'text', value: cfg.text, maxlength: 254, 'aria-label': 'Text', spellcheck: 'false', style: { flex: '1', minWidth: '0' } });
        text.addEventListener('input', () => { cfg.text = text.value; restart(); });
        const win = SaverUI.setup({
            id: 'ss-setup', title: 'Scrolling Marquee Setup', width: 460,
            content: h('div', null,
                h('div', { class: 'ss-cols' },
                    SaverUI.group('Position', h('div', { class: 'ss-stack' }, centered, random)),
                    h('div', { class: 'ss-grow' },
                        SaverUI.group('&Speed', SaverUI.range('Slow', 'Fast', speed)),
                        h('label', { class: 'ss-line' }, SaverUI.lbl('&Background Color:'), bg))),
                h('label', { class: 'ss-line' }, SaverUI.lbl('&Text:'), text),
                SaverUI.group('Text Example', h('div', { class: 'ss-example' }, canvas))),
            extra: [['&Format Text...', () => SaverUI.font(cfg.font, { size: true, effects: true }, fo => { cfg.font = fo; restart(); })]],
            onOk: () => done(cfg),
            onClose: () => stop && stop()
        });
        setTimeout(restart, 0);
        return win;
    }
};

/* ---- Curves and Colors ---- */
Savers.impl.curves = {
    defaults: { speed: 5, density: 5, shape: 0, colors: 0 },
    create(env) {
        const x = env.ctx, cfg = env.cfg;
        const N = cfg.shape === 1 ? 4 : cfg.shape === 2 ? 3 : 5;
        const vel = () => (0.08 + Math.random() * 0.22) * (Math.random() < 0.5 ? -1 : 1);
        const pts = Array.from({ length: N }, () => ({ x: Math.random(), y: Math.random(), vx: vel(), vy: vel() }));
        const trail = [];
        let hue = Math.random() * 360, acc = 0;
        const len = 12 + cfg.density * 8;
        const curve = (snap) => {
            const P = snap.map(([px, py]) => [px * env.w, py * env.h]);
            x.beginPath();
            if (cfg.shape === 1) {
                // An open Bezier through the four bouncing points
                x.moveTo(...P[0]);
                x.bezierCurveTo(...P[1], ...P[2], ...P[3]);
            } else if (cfg.shape === 2) {
                // Loops: a closed curve that swings through three points and their mirror images
                const all = P.concat(P.map(([px, py]) => [env.w - px, env.h - py]));
                spline(all);
            } else spline(P);
            x.stroke();
        };
        // Closed Catmull-Rom spline as Beziers
        const spline = P => {
            const n = P.length;
            x.moveTo((P[0][0] + P[1][0]) / 2, (P[0][1] + P[1][1]) / 2);
            for (let i = 1; i <= n; i++) {
                const a = P[i % n], b = P[(i + 1) % n];
                x.quadraticCurveTo(a[0], a[1], (a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
            }
        };
        return {
            frame(dt) {
                acc += dt;
                const rate = 1 / 30;
                while (acc >= rate) {
                    acc -= rate;
                    const sp = 0.25 + cfg.speed * 0.15;
                    for (const p of pts) {
                        p.x += p.vx * sp * rate;
                        p.y += p.vy * sp * rate;
                        if (p.x < 0) { p.x = 0; p.vx = Math.abs(vel()); } else if (p.x > 1) { p.x = 1; p.vx = -Math.abs(vel()); }
                        if (p.y < 0) { p.y = 0; p.vy = Math.abs(vel()); } else if (p.y > 1) { p.y = 1; p.vy = -Math.abs(vel()); }
                    }
                    hue = (hue + 1.2) % 360;
                    const col = cfg.colors === 1 ? SaverGL.hsv(Math.random() * 360, 1, 1) : cfg.colors === 2 ? SaverGL.hsv(hue, 0.35, 1) : SaverGL.hsv(hue, 1, 1);
                    trail.unshift({ pts: pts.map(p => [p.x, p.y]), col: col.map(v => Math.round(v * 255)) });
                    trail.length = Math.min(trail.length, len);
                }
                x.fillStyle = '#000';
                x.fillRect(0, 0, env.w, env.h);
                x.lineWidth = Math.max(1, env.unit);
                for (let i = trail.length - 1; i >= 0; i--) {
                    const t = trail[i], fade = 1 - i / len;
                    x.strokeStyle = `rgba(${t.col},${(0.25 + 0.75 * fade).toFixed(3)})`;
                    curve(t.pts);
                }
            }
        };
    },
    setup(cfg, done) {
        const speed = SaverUI.slider({ min: 0, max: 10, value: cfg.speed, ticks: 11, label: 'Speed', onchange: v => (cfg.speed = v) });
        const density = SaverUI.slider({ min: 0, max: 10, value: cfg.density, ticks: 11, label: 'Density', onchange: v => (cfg.density = v) });
        const shape = h('select', { class: 'field', 'aria-label': 'Shape', onchange: e => (cfg.shape = +e.target.value) },
            ['Curves', 'Waves', 'Loops'].map((l, i) => h('option', { value: i, selected: i === cfg.shape }, l)));
        const colors = h('select', { class: 'field', 'aria-label': 'Colors', onchange: e => (cfg.colors = +e.target.value) },
            ['Rainbow', 'Random', 'Pastel'].map((l, i) => h('option', { value: i, selected: i === cfg.colors }, l)));
        SaverUI.setup({
            id: 'ss-setup', title: 'Curves and Colors Settings', width: 340,
            content: h('div', null,
                SaverUI.group('&Speed', SaverUI.range('Slow', 'Fast', speed)),
                SaverUI.group('&Density', SaverUI.range('Low', 'High', density)),
                h('div', { class: 'form-grid ss-grid' },
                    h('span', null, SaverUI.lbl('S&hape:')), h('div', { class: 'combo' }, shape),
                    h('span', null, SaverUI.lbl('C&olors:')), h('div', { class: 'combo' }, colors))),
            onOk: () => done(cfg)
        });
    }
};

/* ---- 3D Pipes ---- */
Savers.impl.pipes = {
    gl: true, glAttrs: { preserve: true }, maxPixels: 2.5e6,
    defaults: { multi: false, flex: false, joints: 0, tessel: 0, textured: false, texture: null },
    create(env) {
        const gl = env.gl, cfg = env.cfg, G = SaverGL, M = G.m4;
        const P = G.program(gl), mesh = G.mesh(gl);
        const R = 1, DIV = 7, RATE = 18;
        const SLICES = (Math.floor(cfg.tessel / 100 * 4 / 2.0001) + 2) * 4;
        const tex = cfg.textured ? G.texture(gl, Savers.picture(cfg.texture, B + 'savers/pipes-stripe.webp'), { nearest: true }) : null;
        const DIRS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]], OPP = [1, 0, 3, 2, 5, 4];
        const rnd = n => Math.floor(Math.random() * n);
        let nx, ny, nz, nodes, pipes = [], maxPipes = 5, drawn = 0, yRot = 0, tilt = [0, 0], proj, view;
        // Joint styles: 0 elbows, 1 balls, 2 either; Cycle steps through them one screenful at a time
        const cycle = cfg.joints === 3;
        let jointStyle = cycle ? 2 : cfg.joints, state = 'reset', acc = 0, dissolve = null;

        const at = (x, y, z) => x + nx * (y + ny * z);
        const free = (x, y, z) => x >= 0 && y >= 0 && z >= 0 && x < nx && y < ny && z < nz && !nodes[at(x, y, z)];
        const centre = p => [(p[0] - (nx - 1) / 2) * DIV, (p[1] - (ny - 1) / 2) * DIV, (p[2] - (nz - 1) / 2) * DIV];
        const add = (a, d, k) => [a[0] + d[0] * k, a[1] + d[1] * k, a[2] + d[2] * k];

        // Straight on is weighted; otherwise any free turn, never straight back
        const choose = p => {
            const [x, y, z] = p.pos, dir = p.last;
            let ws = p.weight;
            const ok = DIRS.map(d => free(x + d[0], y + d[1], z + d[2]));
            if (!(ws && ok[dir])) ws = 0;
            else if (ws >= 100) { nodes[at(x + DIRS[dir][0], y + DIRS[dir][1], z + DIRS[dir][2])] = 1; return dir; }
            const turns = [];
            for (let d = 0; d < 6; d++) if (d !== dir && d !== OPP[dir] && ok[d]) turns.push(d);
            if (!ws && !turns.length) return -1;
            const c = rnd(ws + turns.length), nd = c < ws ? dir : turns[c - ws];
            nodes[at(x + DIRS[nd][0], y + DIRS[nd][1], z + DIRS[nd][2])] = 1;
            return nd;
        };

        // Geometry, all in world units around the node grid's centre
        const frame = (T) => {
            // Two unit vectors perpendicular to T
            const a = Math.abs(T[0]) < 0.9 ? [1, 0, 0] : [0, 1, 0];
            let u = [T[1] * a[2] - T[2] * a[1], T[2] * a[0] - T[0] * a[2], T[0] * a[1] - T[1] * a[0]];
            const ul = Math.hypot(...u);
            u = u.map(v => v / ul);
            const v = [T[1] * u[2] - T[2] * u[1], T[2] * u[0] - T[0] * u[2], T[0] * u[1] - T[1] * u[0]];
            return [u, v];
        };
        const tube = (g, a, b, T, rx = R, ry = R, U, V, s0 = 0) => {
            const [u, v] = U ? [U, V] : frame(T);
            const len = Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
            const base = g.p.length / 3;
            for (let j = 0; j <= SLICES; j++) {
                const ang = j / SLICES * Math.PI * 2, c = Math.cos(ang), s = Math.sin(ang);
                let nn = [c / rx * u[0] + s / ry * v[0], c / rx * u[1] + s / ry * v[1], c / rx * u[2] + s / ry * v[2]];
                const nl = Math.hypot(...nn);
                nn = nn.map(q => q / nl);
                for (const [pt, sv] of [[a, s0], [b, s0 + len / DIV]]) {
                    g.v(pt[0] + (c * rx * u[0] + s * ry * v[0]), pt[1] + (c * rx * u[1] + s * ry * v[1]), pt[2] + (c * rx * u[2] + s * ry * v[2]), nn[0], nn[1], nn[2], sv, j / SLICES);
                }
            }
            for (let j = 0; j < SLICES; j++) g.quad(base + j * 2, base + j * 2 + 2, base + j * 2 + 3, base + j * 2 + 1);
        };
        const ball = (g, c, r, sx = 1, U, V, T) => {
            const st = Math.max(6, SLICES / 2), base = g.p.length / 3;
            const [u, v] = U ? [U, V] : [[1, 0, 0], [0, 1, 0]], w = T || [0, 0, 1];
            for (let i = 0; i <= st; i++) {
                const th = i / st * Math.PI, ct = Math.cos(th), s = Math.sin(th);
                for (let j = 0; j <= SLICES; j++) {
                    const ph = j / SLICES * Math.PI * 2, x = s * Math.cos(ph), y = s * Math.sin(ph), z = ct;
                    const px = x * r * sx, py = y * r, pz = z * r;
                    let nn = [x / sx, y, z];
                    const nl = Math.hypot(...nn);
                    nn = nn.map(q => q / nl);
                    const W = (p, q, t) => [p * u[0] + q * v[0] + t * w[0], p * u[1] + q * v[1] + t * w[1], p * u[2] + q * v[2] + t * w[2]];
                    const P3 = W(px, py, pz), N3 = W(...nn);
                    g.v(c[0] + P3[0], c[1] + P3[1], c[2] + P3[2], N3[0], N3[1], N3[2], j / SLICES * 2, i / st);
                }
            }
            for (let i = 0; i < st; i++) for (let j = 0; j < SLICES; j++) {
                const a = base + i * (SLICES + 1) + j, b = a + SLICES + 1;
                g.quad(a, b, b + 1, a + 1);
            }
        };
        // A quarter-torus elbow with the tight inner corner the original has
        const elbow = (g, node, L, N) => {
            const C = add(add(node, L, -R), N, R);
            const B = [L[1] * N[2] - L[2] * N[1], L[2] * N[0] - L[0] * N[2], L[0] * N[1] - L[1] * N[0]];
            const segs = Math.max(4, SLICES / 2), base = g.p.length / 3;
            for (let i = 0; i <= segs; i++) {
                const th = i / segs * Math.PI / 2, ct = Math.cos(th), s = Math.sin(th);
                const rad = [-N[0] * ct + L[0] * s, -N[1] * ct + L[1] * s, -N[2] * ct + L[2] * s];
                const cl = add(C, rad, R);
                for (let j = 0; j <= SLICES; j++) {
                    const ph = j / SLICES * Math.PI * 2, c = Math.cos(ph), sn = Math.sin(ph);
                    const nn = [c * rad[0] + sn * B[0], c * rad[1] + sn * B[1], c * rad[2] + sn * B[2]];
                    g.v(cl[0] + nn[0] * R, cl[1] + nn[1] * R, cl[2] + nn[2] * R, nn[0], nn[1], nn[2], i / segs * 0.3, j / SLICES);
                }
            }
            for (let i = 0; i < segs; i++) for (let j = 0; j < SLICES; j++) {
                const a = base + i * (SLICES + 1) + j, b = a + SLICES + 1;
                g.quad(a, b, b + 1, a + 1);
            }
        };

        const draw = (p, build) => {
            const g = G.geo();
            build(g);
            mesh.set(g.data());
            P.set({ mat: p.mat, tex, two: false }).matrices(proj, view);
            mesh.draw();
        };

        const BIG = Math.SQRT2 * R / Math.cos(Math.PI / SLICES);
        const jointType = () => jointStyle === 0 ? 'elbow' : jointStyle === 1 ? 'ball' : rnd(3) ? 'elbow' : 'ball';

        const start = p => {
            let pos = null;
            for (let tries = 0; tries < nx * ny * nz * 2; tries++) {
                const c = [rnd(nx), rnd(ny), rnd(nz)];
                if (!nodes[at(...c)]) { pos = c; break; }
            }
            if (!pos) {
                const k = nodes.indexOf(0);
                if (k < 0) { p.out = true; p.stuck = true; return false; }
                pos = [k % nx, Math.floor(k / nx) % ny, Math.floor(k / (nx * ny))];
            }
            nodes[at(...pos)] = 1;
            Object.assign(p, { pos, stuck: false, out: false, last: rnd(6), mat: tex ? G.randomWhite() : G.randomTea() });
            p.weight = !rnd(20) ? 25 + rnd(76) : cfg.flex ? rnd(4) : 1 + rnd(4);
            if (cfg.flex) {
                p.rx = R * (1.2 + Math.random() * 0.8);
                p.s = 0;
            }
            const nd = choose(p);
            const c = centre(pos);
            if (nd < 0) { draw(p, g => ball(g, c, BIG)); p.stuck = true; return true; }
            const N = DIRS[nd];
            if (cfg.flex) {
                [p.U, p.V] = frame(N);
                draw(p, g => { ball(g, c, R, p.rx / R, p.U, p.V, N); tube(g, c, add(c, N, DIV / 2), N, p.rx, R, p.U, p.V, 0); });
                p.s = DIV / 2;
            } else draw(p, g => { ball(g, c, BIG); tube(g, add(c, N, R), add(c, N, DIV - R), N, R, R, null, null, R); });
            p.pos = add(pos, N, 1);
            p.last = nd;
            return true;
        };

        // Flex pipes bend through each node in a quarter circle and keep an elliptical cross-section
        const flexStep = (p, c, nd) => {
            const L = DIRS[p.last], N = DIRS[nd];
            if (nd === p.last) {
                draw(p, g => tube(g, add(c, L, -DIV / 2), add(c, N, DIV / 2), N, p.rx, R, p.U, p.V, p.s));
                p.s += DIV;
                return;
            }
            const r = DIV / 2, C = add(add(c, L, -r), N, r), segs = Math.max(6, SLICES / 2);
            const Bv = [L[1] * N[2] - L[2] * N[1], L[2] * N[0] - L[0] * N[2], L[0] * N[1] - L[1] * N[0]];
            // Rotating the cross-section frame with the bend
            const rot = (vec, th) => {
                const ct = Math.cos(th), s = Math.sin(th), d = vec[0] * Bv[0] + vec[1] * Bv[1] + vec[2] * Bv[2];
                const cr = [Bv[1] * vec[2] - Bv[2] * vec[1], Bv[2] * vec[0] - Bv[0] * vec[2], Bv[0] * vec[1] - Bv[1] * vec[0]];
                return [0, 1, 2].map(i => vec[i] * ct + cr[i] * s + Bv[i] * d * (1 - ct));
            };
            draw(p, g => {
                const base = g.p.length / 3;
                for (let i = 0; i <= segs; i++) {
                    const th = i / segs * Math.PI / 2;
                    const rad = [-N[0] * Math.cos(th) + L[0] * Math.sin(th), -N[1] * Math.cos(th) + L[1] * Math.sin(th), -N[2] * Math.cos(th) + L[2] * Math.sin(th)];
                    const cl = add(C, rad, r), U = rot(p.U, th), V = rot(p.V, th);
                    for (let j = 0; j <= SLICES; j++) {
                        const ph = j / SLICES * Math.PI * 2, co = Math.cos(ph), sn = Math.sin(ph);
                        let nn = [0, 1, 2].map(k => co / p.rx * U[k] + sn / R * V[k]);
                        const nl = Math.hypot(...nn);
                        nn = nn.map(q => q / nl);
                        g.v(...[0, 1, 2].map(k => cl[k] + co * p.rx * U[k] + sn * R * V[k]), ...nn, (p.s + i / segs * Math.PI * r / 2) / DIV, j / SLICES);
                    }
                }
                for (let i = 0; i < segs; i++) for (let j = 0; j < SLICES; j++) {
                    const a = base + i * (SLICES + 1) + j, b = a + SLICES + 1;
                    g.quad(a, a + 1, b + 1, b);
                }
            });
            p.U = rot(p.U, Math.PI / 2);
            p.V = rot(p.V, Math.PI / 2);
            p.s += Math.PI * r / 2;
        };

        const step = p => {
            const nd = choose(p), c = centre(p.pos);
            if (nd < 0) {
                if (cfg.flex) draw(p, g => ball(g, add(c, DIRS[p.last], -DIV / 2), R, p.rx / R, p.U, p.V, DIRS[p.last]));
                else draw(p, g => ball(g, c, BIG));
                p.stuck = true;
                return;
            }
            const L = DIRS[p.last], N = DIRS[nd];
            if (cfg.flex) flexStep(p, c, nd);
            else if (nd === p.last) draw(p, g => tube(g, add(c, N, -R), add(c, N, DIV - R), N));
            else if (jointType() === 'elbow') draw(p, g => { elbow(g, c, L, N); tube(g, add(c, N, R), add(c, N, DIV - R), N); });
            else draw(p, g => { ball(g, c, BIG); tube(g, add(c, N, R), add(c, N, DIV - R), N); });
            p.pos = add(p.pos, N, 1);
            p.last = nd;
        };

        const newFrame = normal => {
            if (env.w >= env.h) { nx = 15; ny = Math.max(1, Math.floor(15 / (env.w / env.h))); nz = 15; }
            else { ny = 15; nx = Math.max(1, Math.floor(15 * env.w / env.h)); nz = 15; }
            nodes = new Uint8Array(nx * ny * nz);
            if (cycle) jointStyle = (jointStyle + 1) % 3;
            if (normal) yRot = (yRot + 9.73156) % 360;
            tilt = cfg.flex ? [Math.random() * 10 - 5, Math.random() * 10 - 5] : [0, 0];
            maxPipes = cfg.textured ? 3 : 5;
            let threads = 1;
            if (cfg.multi) { maxPipes = Math.floor(maxPipes * 1.5); threads = Math.min(maxPipes, 2 + rnd(3)); }
            const world = 16 * DIV;
            proj = M.persp(90, env.w / env.h, 1, 75 + world * 2);
            view = M.rotate(M.translate(M.id(), 0, 0, -75), yRot, 0, 1, 0);
            view = M.rotate(M.rotate(view, tilt[0], 1, 0, 0), tilt[1], 0, 0, 1);
            P.lights([{ pos: [90, 90, 150, 0], amb: [0.1, 0.1, 0.1], dif: [1, 1, 1], spc: [1, 1, 1] }], tex ? [0.6, 0.6, 0.6] : [1, 1, 1]);
            gl.enable(gl.DEPTH_TEST);
            gl.depthFunc(gl.LEQUAL);
            gl.enable(gl.CULL_FACE);
            gl.cullFace(gl.BACK);
            gl.frontFace(gl.CCW);
            gl.clear(gl.DEPTH_BUFFER_BIT);
            pipes = [];
            drawn = 0;
            for (let i = 0; i < threads; i++) {
                const p = {};
                if (start(p)) pipes.push(p);
                drawn++;
            }
        };

        // One update of STATE::Draw: stuck pipes restart until the frame's quota is used up
        const update = () => {
            for (const p of pipes) {
                if (!p.stuck) continue;
                if (++drawn > maxPipes || p.out) p.dead = true;
                else if (!start(p) || p.out) { maxPipes = drawn; p.dead = true; }
            }
            pipes = pipes.filter(p => !p.dead);
            if (!pipes.length) return false;
            pipes.forEach(p => { if (!p.stuck) step(p); });
            return true;
        };

        // The screen clears square by square before the next set of pipes
        const startDissolve = () => {
            const size = Math.max(2, Math.round(Math.min(env.w, env.h) / 48));
            const cols = Math.ceil(env.w / size), rows = Math.ceil(env.h / size), order = Array.from({ length: cols * rows }, (_, i) => i);
            for (let i = order.length - 1; i > 0; i--) { const j = rnd(i + 1); [order[i], order[j]] = [order[j], order[i]]; }
            dissolve = { size, cols, order, done: 0, t: 0 };
        };
        const stepDissolve = dt => {
            const d = dissolve;
            d.t += dt;
            const target = Math.min(d.order.length, Math.floor(d.order.length * d.t / 1.6));
            gl.enable(gl.SCISSOR_TEST);
            gl.clearColor(0, 0, 0, 1);
            for (; d.done < target; d.done++) {
                const i = d.order[d.done];
                gl.scissor((i % d.cols) * d.size, Math.floor(i / d.cols) * d.size, d.size, d.size);
                gl.clear(gl.COLOR_BUFFER_BIT);
            }
            gl.disable(gl.SCISSOR_TEST);
            return d.done >= d.order.length;
        };

        return {
            resize() { state = 'reset'; },
            frame(dt) {
                gl.viewport(0, 0, env.w, env.h);
                if (state === 'reset') {
                    gl.clearColor(0, 0, 0, 1);
                    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
                    newFrame(false);
                    state = 'draw';
                    acc = 0;
                    return;
                }
                if (state === 'dissolve') {
                    if (stepDissolve(dt)) { newFrame(true); state = 'draw'; acc = 0; }
                    return;
                }
                acc += dt;
                for (let n = 0; acc >= 1 / RATE && n < 3; n++) {
                    acc -= 1 / RATE;
                    if (!update()) { startDissolve(); state = 'dissolve'; break; }
                }
            },
            destroy() { mesh.free(); P.free(); }
        };
    },
    setup(cfg, done) {
        const single = radio('ss-pp-n', SaverUI.lbl('&Single'), !cfg.multi, '1');
        const multiple = radio('ss-pp-n', SaverUI.lbl('&Multiple'), cfg.multi, 'n');
        const trad = radio('ss-pp-style', SaverUI.lbl('&Traditional'), !cfg.flex, 't');
        const flex = radio('ss-pp-style', SaverUI.lbl('&Flex'), cfg.flex, 'f');
        const joints = h('select', { class: 'field', 'aria-label': 'Joint Type', onchange: e => (cfg.joints = +e.target.value) },
            ['Elbow', 'Ball', 'Mixed', 'Cycle'].map((l, i) => h('option', { value: i, selected: i === cfg.joints }, l)));
        const jointLbl = h('div', { class: 'ss-small' }, SaverUI.lbl('&Joint Type:'));
        const tessel = SaverUI.slider({ min: 0, max: 200, value: cfg.tessel, label: 'Resolution', onchange: v => (cfg.tessel = v) });
        const solid = radio('ss-pp-surf', SaverUI.lbl('Soli&d'), !cfg.textured, 's');
        const textured = radio('ss-pp-surf', SaverUI.lbl('Te&xtured'), cfg.textured, 'x');
        const choose = button(SaverUI.lbl('&Choose Texture...'), () => SaverUI.pickTexture(id => (cfg.texture = id)));
        const sync = () => {
            joints.disabled = cfg.flex;
            jointLbl.classList.toggle('ss-dim', cfg.flex);
            choose.disabled = !cfg.textured;
        };
        single.querySelector('input').addEventListener('change', () => (cfg.multi = false));
        multiple.querySelector('input').addEventListener('change', () => (cfg.multi = true));
        trad.querySelector('input').addEventListener('change', () => { cfg.flex = false; sync(); });
        flex.querySelector('input').addEventListener('change', () => { cfg.flex = true; sync(); });
        solid.querySelector('input').addEventListener('change', () => { cfg.textured = false; sync(); });
        textured.querySelector('input').addEventListener('change', () => { cfg.textured = true; sync(); });
        sync();
        SaverUI.setup({
            id: 'ss-setup', title: '3D Pipes Setup', width: 470,
            content: h('div', { class: 'ss-grid2' },
                SaverUI.group('Pipes', h('div', { class: 'ss-stack' }, single, multiple)),
                SaverUI.group('Pipe Style', h('div', { class: 'ss-cols ss-tight' }, h('div', { class: 'ss-stack' }, trad, flex), h('div', null, jointLbl, h('div', { class: 'combo', style: { width: '90px' } }, joints)))),
                SaverUI.group('&Resolution', SaverUI.range('Min', 'Max', tessel)),
                SaverUI.group('Surface Style', h('div', { class: 'ss-stack' }, solid, h('div', { class: 'ss-line' }, textured, choose)))),
            onOk: () => done(cfg)
        });
    }
};

/* ---- 3D Maze ---- */
Savers.impl.maze = {
    gl: true, glAttrs: { antialias: false }, maxPixels: 2.5e6,
    // Built-in surface textures: brick, wood, the "castle" ceiling tile, then four fractals whose palettes rotate
    TEXTURES: [['maze-brick.webp', 'Brick'], ['maze-wood.webp', 'Wood'], ['maze-castle.webp', 'Castle'], ['maze-curl4.png'], ['maze-bhole.png'], ['maze-snowflak.png'], ['maze-swirlx4.png']],
    defaults: { surfaces: [{ def: true, idx: 0, pic: null }, { def: true, idx: 1, pic: null }, { def: true, idx: 2, pic: null }], overlay: false, full: true, size: 0, quality: 0 },
    create(env) {
        const gl = env.gl, cfg = env.cfg, G = SaverGL, M = G.m4, self = Savers.impl.maze;
        const P = G.program(gl);
        const N = 12, SUB = 2, ARC = 18 * SUB, REV = 18 * SUB, RATE = 30 * SUB;
        const rnd = n => Math.floor(Math.random() * n);
        const pot = n => 2 ** Math.max(0, Math.round(Math.log2(n || 1)));
        const surf = cfg.surfaces.map((s, i) => {
            const d = self.TEXTURES[s.def ? s.idx : i] || self.TEXTURES[i];
            if (s.def && s.idx >= 3) return G.palette(gl, B + 'savers/' + d[0]);
            return G.texture(gl, s.def ? B + 'savers/' + d[0] : Savers.picture(s.pic, B + 'savers/' + self.TEXTURES[i][0]), { nearest: true });
        });
        const T = {
            cover: G.texture(gl, B + 'savers/maze-cover.webp', { nearest: true }), start: G.texture(gl, B + 'savers/maze-start.webp', { nearest: true }),
            end: G.texture(gl, B + 'savers/maze-end.webp', { nearest: true }), rat: G.texture(gl, B + 'savers/maze-rat.webp', { nearest: true }),
            ad: G.texture(gl, B + 'savers/maze-letters.webp', { nearest: true })
        };
        // Repeats per cell: 128-pixel textures once, smaller ones more often (CalcRep)
        const rep = t => { const s = pot(t.w); return s >= 128 ? 1 : 1 + 8 - Math.log2(s); };
        const meshes = { floor: G.mesh(gl), ceil: G.mesh(gl), walls: G.mesh(gl), cover: G.mesh(gl), obj: G.mesh(gl), lines: G.mesh(gl) };
        const solids = [G.mesh(gl), G.mesh(gl), G.mesh(gl), G.mesh(gl)];

        // Platonic solids for the spinning "specials" (icosahedron, octahedron, dodecahedron, tetrahedron)
        (() => {
            const faces = (verts, fs) => {
                const g = G.geo();
                for (const f of fs) {
                    const c = f.map(i => verts[i]);
                    const u = c[1].map((v, k) => v - c[0][k]), w = c[2].map((v, k) => v - c[0][k]);
                    let n = [u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0]];
                    const l = Math.hypot(...n);
                    n = n.map(v => v / l);
                    const cx = c.reduce((a, p) => a + p[0], 0), cy = c.reduce((a, p) => a + p[1], 0), cz = c.reduce((a, p) => a + p[2], 0);
                    if (n[0] * cx + n[1] * cy + n[2] * cz < 0) { n = n.map(v => -v); c.reverse(); }
                    const ids = c.map(p => { const L = Math.hypot(...p); return g.v(p[0] / L, p[1] / L, p[2] / L, ...n); });
                    for (let i = 1; i < ids.length - 1; i++) g.tri(ids[0], ids[i], ids[i + 1]);
                }
                return g.data();
            };
            const t = (1 + Math.sqrt(5)) / 2, it = 1 / t;
            solids[0].set(faces([[-1, t, 0], [1, t, 0], [-1, -t, 0], [1, -t, 0], [0, -1, t], [0, 1, t], [0, -1, -t], [0, 1, -t], [t, 0, -1], [t, 0, 1], [-t, 0, -1], [-t, 0, 1]],
                [[0, 11, 5], [0, 5, 1], [0, 1, 7], [0, 7, 10], [0, 10, 11], [1, 5, 9], [5, 11, 4], [11, 10, 2], [10, 7, 6], [7, 1, 8], [3, 9, 4], [3, 4, 2], [3, 2, 6], [3, 6, 8], [3, 8, 9], [4, 9, 5], [2, 4, 11], [6, 2, 10], [8, 6, 7], [9, 8, 1]]));
            solids[1].set(faces([[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]], [[0, 2, 4], [2, 1, 4], [1, 3, 4], [3, 0, 4], [2, 0, 5], [1, 2, 5], [3, 1, 5], [0, 3, 5]]));
            const dv = [[1, 1, 1], [1, 1, -1], [1, -1, 1], [1, -1, -1], [-1, 1, 1], [-1, 1, -1], [-1, -1, 1], [-1, -1, -1], [0, it, t], [0, it, -t], [0, -it, t], [0, -it, -t],
                [it, t, 0], [it, -t, 0], [-it, t, 0], [-it, -t, 0], [t, 0, it], [t, 0, -it], [-t, 0, it], [-t, 0, -it]];
            solids[2].set(faces(dv, [[0, 8, 10, 2, 16], [0, 16, 17, 1, 12], [0, 12, 14, 4, 8], [1, 17, 3, 11, 9], [1, 9, 5, 14, 12], [2, 10, 6, 15, 13],
                [2, 13, 3, 17, 16], [3, 13, 15, 7, 11], [4, 14, 5, 19, 18], [4, 18, 6, 10, 8], [5, 9, 11, 7, 19], [6, 18, 19, 7, 15]]));
            solids[3].set(faces([[1, 1, 1], [1, -1, -1], [-1, 1, -1], [-1, -1, 1]], [[0, 1, 2], [0, 3, 1], [0, 2, 3], [1, 3, 2]]));
        })();

        // Walls: hw[y][x] runs along the top of cell (x, y); vw[y][x] along its left side
        let hw, vw, walls, objects, rats, player, goals, height = 0, viewRot = 0, rotSteps = 0, found = null, state = 'new', acc = 0, palRot = 0, spot = null;
        const cellWalls = (x, y) => [vw[y][x], hw[y][x], vw[y][x + 1], hw[y + 1][x]];   // left, up, right, down

        const generate = () => {
            hw = Array.from({ length: N + 1 }, () => Array(N).fill(true));
            vw = Array.from({ length: N }, () => Array(N + 1).fill(true));
            const set = Array.from({ length: N * N }, (_, i) => i);
            const find = i => set[i] === i ? i : (set[i] = find(set[i]));
            const inner = [];
            for (let y = 0; y < N; y++) for (let x = 1; x < N; x++) inner.push(['v', x, y]);
            for (let y = 1; y < N; y++) for (let x = 0; x < N; x++) inner.push(['h', x, y]);
            for (let i = inner.length - 1; i > 0; i--) { const j = rnd(i + 1); [inner[i], inner[j]] = [inner[j], inner[i]]; }
            for (const [k, x, y] of inner) {
                const a = find(y * N + x), b = find(k === 'v' ? y * N + x - 1 : (y - 1) * N + x);
                if (a === b) continue;
                set[a] = b;
                if (k === 'v') vw[y][x] = false; else hw[y][x] = false;
            }
            walls = [];
            for (let y = 0; y <= N; y++) for (let x = 0; x < N; x++) if (hw[y][x]) walls.push({ f: [x, y], t: [x + 1, y] });
            for (let y = 0; y < N; y++) for (let x = 0; x <= N; x++) if (vw[y][x]) walls.push({ f: [x, y], t: [x, y + 1] });
            // A few walls carry the OpenGL poster instead of brick
            for (let i = 1 + rnd(5); i > 0; i--) walls[rnd(walls.length)].cover = true;
        };

        // The wall-following walk from slvmaze.c. Directions: 0 left, 1 up, 2 right, 3 down.
        const LEFT_TURN = [3, 0, 1, 2], RIGHT_TURN = [1, 2, 3, 0], OFF = [[-1, 0], [0, -1], [1, 0], [0, 1]], ANG = [180, 90, 0, 270];
        const DOFF = [[1 - 1e-4, 0.5], [0.5, 1 - 1e-4], [0, 0.5], [0.5, 0]];
        const fsin = a => -Math.sin(a * Math.PI / 180), fcos = a => Math.cos(a * Math.PI / 180);
        const walker = (cx, cy, dir, goalsList) => {
            const right = rnd(1000) > 500;
            const s = { cx, cy, dir, turnTo: right ? LEFT_TURN : RIGHT_TURN, turnAway: right ? RIGHT_TURN : LEFT_TURN, sign: right ? 1 : -1, anim: null, count: 0, goals: goalsList, x: 0, y: 0, ang: 0 };
            const setView = () => { s.ang = ANG[s.dir]; s.x = s.cx + DOFF[s.dir][0]; s.y = s.cy + DOFF[s.dir][1]; };
            setView();
            s.step = () => {
                if (s.anim && --s.count === 0) { s.anim = null; setView(); }
                const arc = Math.PI / 2 / (ARC * 2);
                switch (s.anim) {
                    case 'to': case 'away':
                        s.x += fcos(s.ang) * arc;
                        s.y += fsin(s.ang) * arc;
                        s.ang += (s.anim === 'to' ? 1 : -1) * s.sign * 90 / ARC;
                        return null;
                    case 'fwd':
                        s.x += fcos(s.ang) / ARC;
                        s.y += fsin(s.ang) / ARC;
                        return null;
                    case 'rev':
                        s.ang += s.sign * 180 / REV;
                        return null;
                }
                const hit = s.goals && s.goals.find(g => g.x === s.cx && g.y === s.cy);
                if (hit) return hit;
                const wl = cellWalls(s.cx, s.cy);
                let dir = s.dir, i;
                for (i = 0; i < 3; i++) {
                    const t = s.turnTo[dir];
                    if (!wl[t]) {
                        s.cx += OFF[t][0];
                        s.cy += OFF[t][1];
                        s.dir = t;
                        s.count = ARC;
                        s.anim = ['to', 'fwd', 'away'][i];
                        break;
                    }
                    dir = s.turnAway[dir];
                }
                if (i === 3) {
                    // Dead end: turn around
                    s.dir = s.turnTo[s.turnTo[s.dir]];
                    s.cx += OFF[s.dir][0];
                    s.cy += OFF[s.dir][1];
                    s.anim = 'rev';
                    s.count = REV;
                }
                return null;
            };
            return s;
        };

        const newMaze = () => {
            generate();
            const cells = Array.from({ length: N * N }, (_, i) => i);
            const take = (x, y) => { const i = cells.indexOf(y * N + x); if (i >= 0) cells.splice(i, 1); };
            const pick = () => { const i = cells.splice(rnd(cells.length), 1)[0]; return [i % N, Math.floor(i / N)]; };
            const sy = rnd(N), ey = rnd(N);
            take(0, sy);
            take(N - 1, ey);
            objects = [
                { kind: 'start', x: 0.5, y: sy + 0.5, w: 1 / 6, h: 0.166, z: 0.5, col: [1, 0, 0], tex: T.start, alpha: 0.58 },
                { kind: 'end', x: N - 0.5, y: ey + 0.5, w: 1 / 6, h: 0.166, z: 0.5, col: [0, 1, 0], tex: T.end, alpha: 0.6 }
            ];
            goals = [{ x: N - 1, y: ey, end: true }];
            for (let i = rnd(9); i > 0; i--) {
                const [x, y] = pick();
                const o = { kind: 'special', x: x + 0.5, y: y + 0.5, w: 0.25, h: 0.25, z: 0.25, col: [1, 1, 1], solid: rnd(4), ang: 0, spinZ: 2 + rnd(6), spinY: rnd(5), tilt: 0 };
                objects.push(o);
                goals.push({ x, y, obj: o });
            }
            for (let i = rnd(6) - 2; i > 0; i--) {
                const [x, y] = pick();
                objects.push({ kind: 'ad', x: x + 0.5, y: y + 0.5, w: 1 / 3, h: 0.33, z: 0.5, col: [1, 1, 1], tex: T.ad, alpha: 0.6 });
            }
            rats = [];
            const [rx, ry] = pick();
            const rat = { kind: 'rat', w: 0.25, h: 0.125, z: 0.125, col: [0.75, 0.39, 0], tex: T.rat, alpha: 1, walk: walker(rx, ry, 0, null) };
            rats.push(rat);
            objects.push(rat);
            player = walker(0, sy, 2, goals);
            state = 'grow';
            height = 0;
            viewRot = 0;
            // Not full screen: the maze window moves somewhere else for each new maze
            spot = spot ? { x: Math.random(), y: Math.random() } : { x: 0.5, y: 0.5 };
            buildStatic();
        };

        const buildStatic = () => {
            const plane = (m, z, t, ceiling) => {
                const g = G.geo(), r = rep(t) * N;
                if (ceiling) { g.v(0, 0, z, 0, 0, -1, r, 0); g.v(N, 0, z, 0, 0, -1, 0, 0); g.v(N, N, z, 0, 0, -1, 0, r); g.v(0, N, z, 0, 0, -1, r, r); g.quad(0, 3, 2, 1); }
                else { g.v(0, 0, z, 0, 0, 1, 0, 0); g.v(N, 0, z, 0, 0, 1, r, 0); g.v(N, N, z, 0, 0, 1, r, r); g.v(0, N, z, 0, 0, 1, 0, r); g.quad(0, 1, 2, 3); }
                m.set(g.data());
                m.rep = rep(t);
            };
            plane(meshes.floor, 0, surf[1], false);
            plane(meshes.ceil, 1, surf[2], true);
            for (const [m, cover] of [[meshes.walls, false], [meshes.cover, true]]) {
                const g = G.geo(), t = cover ? T.cover : surf[0], r = cover ? 1 : rep(t);
                for (const wl of walls) {
                    if (!!wl.cover !== cover) continue;
                    const [fx, fy] = wl.f, [tx, ty] = wl.t;
                    // Both faces, each with the texture the right way round
                    const a = g.v(fx, fy, 0, 0, 0, 1, 0, 0), b = g.v(tx, ty, 0, 0, 0, 1, r, 0), c = g.v(tx, ty, 1, 0, 0, 1, r, r), d = g.v(fx, fy, 1, 0, 0, 1, 0, r);
                    g.quad(a, b, c, d);
                    const a2 = g.v(fx, fy, 0, 0, 0, 1, r, 0), b2 = g.v(tx, ty, 0, 0, 0, 1, 0, 0), c2 = g.v(tx, ty, 1, 0, 0, 1, 0, r), d2 = g.v(fx, fy, 1, 0, 0, 1, r, r);
                    g.quad(a2, d2, c2, b2);
                }
                m.set(g.data());
                m.tex = t;
                m.built = pot(t.w);
            }
            const g = G.geo();
            for (const wl of walls) { g.v(wl.f[0], wl.f[1], 0, 0, 0, 1); g.v(wl.t[0], wl.t[1], 0, 0, 0, 1); }
            meshes.lines.set({ pos: new Float32Array(g.p) });
        };

        const update = () => {
            palRot += 1 / SUB;
            if (state === 'new') newMaze();
            else if (state === 'grow') { height += 0.025 / SUB; if (height >= 1) { height = 1; state = 'solve'; } }
            else if (state === 'shrink') { height -= 0.025 / SUB; if (height <= 0) { height = 0; state = 'new'; } }
            else if (state === 'rotate') {
                viewRot += 10 / SUB;
                if (++rotSteps === 18 * SUB) {
                    objects = objects.filter(o => o !== found.obj);
                    goals = goals.filter(g => g !== found);
                    player.goals = goals;
                    viewRot = viewRot >= 360 ? 0 : 180;
                    found = null;
                    state = 'solve';
                }
            } else if (state === 'solve') {
                for (const r of rats) { r.walk.step(); r.x = r.walk.x; r.y = r.walk.y; r.ang = -r.walk.ang; }
                const g = player.step();
                if (g && g.end) state = 'shrink';
                else if (g) { state = 'rotate'; found = g; rotSteps = 0; }
            }
            for (const o of objects) if (o.kind === 'special') { o.ang += o.spinZ / SUB; o.tilt += o.spinY / SUB; }
            // Rebuild when a picture texture finishes loading and turns out to be a different size
            if (walls && (meshes.walls.built !== pot(surf[0].w) || meshes.floor.rep !== rep(surf[1]) || meshes.ceil.rep !== rep(surf[2]))) buildStatic();
        };

        const render = () => {
            const full = cfg.full;
            // Full Screen draws a 320×200 picture and stretches it, as 98 did
            const q = cfg.quality ? 2 : 1;
            env.fixed = full ? (env.canvas.clientWidth >= env.canvas.clientHeight ? [320 * q, 200 * q] : [200 * q, 320 * q]) : null;
            let vx = 0, vy = 0, vw2 = env.w, vh = env.h;
            gl.viewport(0, 0, env.w, env.h);
            gl.disable(gl.SCISSOR_TEST);
            gl.clearColor(0, 0, 0, 1);
            gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
            if (!full) {
                vw2 = Math.round(Math.min(env.w, (1 / 3 + 2 * cfg.size / 300) * env.w));
                vh = Math.round(Math.min(env.h, vw2 * 9 / 16));
                vx = Math.round((env.w - vw2) * spot.x);
                vy = Math.round((env.h - vh) * spot.y);
                gl.viewport(vx, vy, vw2, vh);
            }
            // A square field of view squeezed into the window, as the original did; portrait phones keep their proportions
            const aspect = env.w >= env.h || !full ? 1 : env.w / env.h;
            const proj = M.mul(M.rotate(M.id(), viewRot, 0, 0, 1), M.persp(90, aspect, 0.02, 40));
            const view = M.lookAt(player.x, player.y, 0.5, player.x + fcos(player.ang), player.y + fsin(player.ang), 0.5, 0, 0, 1);
            gl.enable(gl.DEPTH_TEST);
            gl.depthFunc(gl.LEQUAL);
            gl.disable(gl.BLEND);
            gl.enable(gl.CULL_FACE);
            gl.frontFace(gl.CCW);
            gl.cullFace(gl.BACK);
            const opaque = (m, tex, model) => { P.set({ mode: 2, tex, palRot }).matrices(proj, model ? M.mul(view, model) : view); m.draw(); };
            opaque(meshes.floor, surf[1]);
            opaque(meshes.ceil, surf[2]);
            const grow = M.scale(M.id(), 1, 1, height);
            if (height > 0) { opaque(meshes.walls, surf[0], grow); opaque(meshes.cover, T.cover, grow); }
            // Specials: lit white solids, the light hanging over the middle of the maze
            const lp = M.apply(view, N / 2, N / 2, 10, 1);
            P.lights([{ pos: lp, amb: [0, 0, 0], dif: [1, 1, 1], spc: [0, 0, 0] }], [0.2, 0.2, 0.2]);
            for (const o of objects) {
                if (o.kind !== 'special') continue;
                let m = M.translate(M.id(), o.x, o.y, o.z * height);
                m = M.rotate(M.rotate(M.scale(m, 1, 1, height || 0.001), o.ang, 0, 0, 1), o.tilt, 0, 1, 0);
                m = M.scale(m, o.w, o.w, o.w);
                P.set({ mat: { ka: [0.2, 0.2, 0.2], kd: [0.8, 0.8, 0.8] } }).matrices(proj, M.mul(view, m));
                solids[o.solid].draw();
            }
            gl.disable(gl.CULL_FACE);
            // Billboards, farthest first, always square to the view
            const cs = fcos(player.ang), sn = fsin(player.ang);
            const boards = objects.filter(o => o.tex).map(o => ({ o, d: (o.x - player.x) * cs + (o.y - player.y) * sn })).sort((a, b) => b.d - a.d);
            gl.enable(gl.BLEND);
            gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
            gl.depthMask(false);
            for (const { o } of boards) {
                const bx = -sn * o.w, by = cs * o.w, lo = (o.z - o.h) * height, hi = (o.z + o.h) * height;
                const g = G.geo();
                g.v(o.x - bx, o.y - by, lo, 0, 0, 1, 1, 0); g.v(o.x + bx, o.y + by, lo, 0, 0, 1, 0, 0);
                g.v(o.x + bx, o.y + by, hi, 0, 0, 1, 0, 1); g.v(o.x - bx, o.y - by, hi, 0, 0, 1, 1, 1);
                g.quad(0, 1, 2, 3);
                meshes.obj.set(g.data());
                P.set({ mode: 2, tex: o.tex, alpha: o.alpha, alphaCut: 0.02 }).matrices(proj, view);
                meshes.obj.draw();
            }
            gl.depthMask(true);
            if (cfg.overlay) {
                // Maze Overlay: the walls in white, turned so the way ahead is up
                gl.disable(gl.DEPTH_TEST);
                const a = (full ? env.w : vw2) / (full ? env.h : vh);
                const op = M.ortho(-N / 2, N / 2, -N / 2 / a, N / 2 / a, -1, 1);
                let om = M.rotate(M.id(), 90 - player.ang, 0, 0, 1);
                om = M.scale(om, 1, -1, 1);
                om = M.translate(om, -player.x, -player.y, 0);
                P.set({ mode: 1, mat: { ka: [1, 1, 1], kd: [1, 1, 1] } }).matrices(op, om);
                meshes.lines.draw(gl.LINES);
                const tri = (x, y, ang, w, col, m) => {
                    const g = G.geo();
                    const r = ang * Math.PI / 180, c = Math.cos(r), s = Math.sin(r), pt = (px, py) => [x + px * c - py * s, y + px * s + py * c, 0];
                    g.v(...pt(w, 0), 0, 0, 1); g.v(...pt(-w * 0.707, w * 0.5), 0, 0, 1); g.v(...pt(-w * 0.707, -w * 0.5), 0, 0, 1);
                    g.tri(0, 1, 2);
                    meshes.obj.set(g.data());
                    P.set({ mode: 1, mat: { ka: col, kd: col } }).matrices(op, m);
                    meshes.obj.draw();
                };
                for (const o of objects) tri(o.x, o.y, o.ang || 0, o.w, o.col, om);
                tri(0, 0, 90, 0.25, [0, 0, 1], M.id());
            }
        };

        return {
            resize() { },
            frame(dt) {
                acc += dt;
                let n = 0;
                if (state === 'new') update();
                while (acc >= 1 / RATE && n++ < 6) { acc -= 1 / RATE; update(); }
                if (acc > 1) acc = 0;
                render();
            },
            destroy() { Object.values(meshes).forEach(m => m.free()); solids.forEach(m => m.free()); P.free(); }
        };
    },
    setup(cfg, done) {
        const self = Savers.impl.maze;
        // A little picture of each surface, like the owner-drawn boxes in the real dialog
        const previews = cfg.surfaces.map(() => h('canvas', { class: 'ss-texbox', width: 75, height: 40 }));
        const paint = i => {
            const s = cfg.surfaces[i], c = previews[i], x = c.getContext('2d');
            x.fillStyle = '#000';
            x.fillRect(0, 0, c.width, c.height);
            const src = s.def ? SaverGL.image(B + 'savers/' + self.TEXTURES[s.idx][0]) : Savers.picture(s.pic, B + 'savers/' + self.TEXTURES[i][0]);
            src.then(im => {
                if (!im) return;
                if (s.def && s.idx >= 3) {
                    // Fractal textures keep their palette in the first row
                    const t = document.createElement('canvas');
                    t.width = im.width;
                    t.height = im.height;
                    const tx = t.getContext('2d', { willReadFrequently: true });
                    tx.drawImage(im, 0, 0);
                    const d = tx.getImageData(0, 0, t.width, t.height), out = tx.createImageData(t.width, t.height - 1);
                    for (let p = 0; p < out.data.length; p += 4) {
                        const k = d.data[p + t.width * 4] * 4;
                        out.data.set([d.data[k], d.data[k + 1], d.data[k + 2], 255], p);
                    }
                    tx.putImageData(out, 0, 0);
                    im = t;
                }
                x.imageSmoothingEnabled = false;
                const tile = Math.max(20, Math.min(40, im.width / 3.2));
                for (let yy = 0; yy < c.height; yy += tile) for (let xx = 0; xx < c.width; xx += tile) x.drawImage(im, 0, 0, im.width, s.def && s.idx >= 3 ? im.height - 1 : im.height, xx, yy, tile, tile);
            });
        };
        const rows = ['&Walls...', '&Floor...', '&Ceiling...'].map((label, i) => {
            const spin = h('span', { class: 'ss-arrows ss-vspin' },
                ...[1, -1].map(dir => h('button', {
                    class: 'ss-ud ' + (dir > 0 ? 'up' : 'down'), type: 'button', 'aria-label': (dir > 0 ? 'Next ' : 'Previous ') + label.replace(/[&.]/g, '') + ' texture',
                    onclick: () => { const s = cfg.surfaces[i]; s.idx = (s.idx + dir + self.TEXTURES.length) % self.TEXTURES.length; paint(i); }
                })));
            const syncSpin = () => spin.querySelectorAll('button').forEach(b => (b.disabled = !cfg.surfaces[i].def));
            const btn = button(SaverUI.lbl(label), () => self.texDialog(cfg.surfaces[i], () => { paint(i); syncSpin(); }));
            syncSpin();
            paint(i);
            return h('div', { class: 'ss-texrow' }, btn, previews[i], spin);
        });
        const size = SaverUI.slider({ min: 0, max: 100, value: cfg.size, label: 'Size', onchange: v => (cfg.size = v) });
        const sizeRange = SaverUI.range('Min', 'Max', size);
        const overlay = checkbox(SaverUI.lbl('Maze &Overlay'), cfg.overlay, v => (cfg.overlay = v));
        const full = checkbox(SaverUI.lbl('F&ull Screen'), cfg.full, v => { cfg.full = v; sizeRange.disable(v); });
        sizeRange.disable(cfg.full);
        const quality = h('select', { class: 'field', 'aria-label': 'Image Quality', onchange: e => (cfg.quality = +e.target.value) },
            ['Default', 'High'].map((l, i) => h('option', { value: i, selected: i === cfg.quality }, l)));
        SaverUI.setup({
            id: 'ss-setup', title: 'Maze Setup', width: 380,
            content: h('div', { class: 'ss-grid2 ss-maze' },
                SaverUI.group('Textures', h('div', { class: 'ss-stack' }, rows)),
                h('div', { class: 'ss-stack ss-maze-opts' }, h('fieldset', { class: 'group ss-group' }, overlay), h('fieldset', { class: 'group ss-group' }, full)),
                SaverUI.group('&Size', sizeRange),
                SaverUI.group('Image &Quality', h('div', { class: 'combo' }, quality))),
            onOk: () => done(cfg)
        });
    },
    // Configure Texture: the built-in one, or a picture of your own
    texDialog(s, done) {
        const draft = { def: s.def, pic: s.pic };
        const def = radio('ss-tex', SaverUI.lbl('&Default'), draft.def, 'd');
        const user = radio('ss-tex', SaverUI.lbl('&User'), !draft.def, 'u');
        const choose = button(SaverUI.lbl('&Choose...'), () => SaverUI.pickTexture(id => { draft.pic = id; }));
        const sync = () => (choose.disabled = draft.def);
        def.querySelector('input').addEventListener('change', () => { draft.def = true; sync(); });
        user.querySelector('input').addEventListener('change', () => { draft.def = false; sync(); if (!draft.pic) choose.click(); });
        sync();
        SaverUI.setup({
            id: 'ss-tex', title: 'Configure Texture', width: 280,
            content: SaverUI.group('Texture', h('div', { class: 'ss-cols ss-tight' }, h('div', { class: 'ss-stack' }, def, user), choose)),
            onOk: () => {
                s.def = draft.def || !draft.pic;
                s.pic = draft.pic;
                done();
            }
        });
    }
};

/* ---- 3D Flower Box ---- */
Savers.impl.flowerbox = {
    gl: true, maxPixels: 2.5e6,
    defaults: { color: 1, smooth: true, slanted: false, cycle: false, spin: true, bloom: true, two: false, geom: 0, subdiv: 10, size: 55 },
    GEOMS: ['Cube', 'Tetrahedron', 'Pyramids', 'Cylinder', 'Spring'],
    create(env) {
        const gl = env.gl, cfg = env.cfg, G = SaverGL, M = G.m4;
        const P = G.program(gl), mesh = G.mesh(gl);
        const sub = Math.max(2, Math.min(10, cfg.subdiv));
        const pts = [], strips = []; // strips[side] = [[global indices]]
        let range;
        const map = (pl, x, y) => [x * pl[3] + y * pl[6] + pl[0], x * pl[4] + y * pl[7] + pl[1], x * pl[5] + y * pl[8] + pl[2]];
        const S3 = 1.73205, T = -S3 / 8;
        if (cfg.geom === 0) {
            range = [-1.1, 5.1, 2.0];
            const planes = [[-.5, -.5, .5, 1, 0, 0, 0, 1, 0], [.5, -.5, -.5, -1, 0, 0, 0, 1, 0], [.5, .5, -.5, -1, 0, 0, 0, 0, 1], [-.5, -.5, -.5, 1, 0, 0, 0, 0, 1], [.5, -.5, -.5, 0, 1, 0, 0, 0, 1], [-.5, .5, -.5, 0, -1, 0, 0, 0, 1]];
            const sidePts = (sub + 1) * (sub + 1);
            planes.forEach((pl, side) => {
                for (let x = 0; x <= sub; x++) for (let y = 0; y <= sub; y++) pts.push(map(pl, x / sub, y / sub));
                const list = [];
                for (let x = 0; x < sub; x++) {
                    const s = [];
                    for (let y = 0; y <= sub; y++) s.push(side * sidePts + x * (sub + 1) + y, side * sidePts + (x + 1) * (sub + 1) + y);
                    list.push(s);
                }
                strips.push(list);
            });
        } else if (cfg.geom === 1 || cfg.geom === 2) {
            const planes = cfg.geom === 1
                ? [[-.5, T, S3 / 6, 1, 0, 0, 0, S3 / 2, -S3 / 6], [0, T, -S3 / 3, -.5, 0, S3 / 2, .25, S3 / 2, S3 / 12], [.5, T, S3 / 6, -.5, 0, -S3 / 2, -.25, S3 / 2, S3 / 12], [.5, T, S3 / 6, -1, 0, 0, 0, 0, -S3 / 2]]
                : [[-.5, 0, .5, 1, 0, 0, 0, .5, -.5], [.5, 0, .5, -1, 0, 0, 0, -.5, -.5], [.5, 0, .5, 0, 0, -1, -.5, .5, 0], [.5, 0, -.5, 0, 0, 1, -.5, -.5, 0],
                    [.5, 0, -.5, -1, 0, 0, 0, .5, .5], [-.5, 0, -.5, 1, 0, 0, 0, -.5, .5], [-.5, 0, -.5, 0, 0, 1, .5, .5, 0], [-.5, 0, .5, 0, 0, -1, .5, -.5, 0]];
            range = cfg.geom === 1 ? [-1.1, 5.2, 3.75] : [-1.1, 5.2, 3.0];
            planes.forEach(pl => {
                let base = pts.length;
                for (let x = 0; x <= sub; x++) for (let y = 0; y <= sub - x; y++) pts.push(map(pl, x / sub + y / (sub * 2), y / sub));
                const list = [];
                for (let x = 0; x < sub; x++) {
                    const row = sub - x + 1, s = [base];
                    for (let y = 0; y < row - 1; y++) s.push(base + row + y, base + 1 + y);
                    list.push(s);
                    base += row;
                }
                strips.push(list);
            });
        } else if (cfg.geom === 3) {
            range = [-2.5, 8.5, 2.1];
            for (let x = 0; x < sub; x++) {
                const a = x * 2 * Math.PI / sub;
                for (let y = 0; y <= sub; y++) pts.push([Math.cos(a) * 0.5, y / sub - 0.5, Math.sin(a) * 0.5]);
            }
            const list = [], row = sub + 1;
            for (let x = 0, base = 0; x < sub; x++, base += row) {
                const s = [];
                for (let y = 0; y < row; y++) s.push(x === sub - 1 ? y : base + row + y, base + y);
                list.push(s);
            }
            strips.push(list);
        } else {
            range = [-2.2, 0.2, 1.0];
            const spin = 4 * sub + 1, SR = 0.1, SC = 0.5 - SR;
            for (let x = 0; x < spin; x++) {
                const ac = x * 4 * Math.PI / (spin - 1), cs = Math.cos(ac), sn = Math.sin(ac), rad = 0.5 - x / (spin - 1) * (SC / 2);
                const pl = [cs * rad, -0.5 + x / (spin - 1), sn * rad, cs * SR, 0, sn * SR, 0, SR, 0];
                for (let y = 0; y < sub; y++) { const as = y * 2 * Math.PI / sub; pts.push(map(pl, Math.cos(as), Math.sin(as))); }
            }
            const list = [];
            for (let x = 0; x < sub; x++) {
                const s = [];
                for (let y = 0; y < spin; y++) s.push(x + sub * y, x === sub - 1 ? sub * y : x + sub * y + 1);
                list.push(s);
            }
            strips.push(list);
        }
        const [minSf, maxSf, initSf] = range;
        // Every point is pushed out or pulled in along its direction from the centre: the "bloom"
        const vlen = pts.map(p => { const d = Math.max(0.01, Math.hypot(...p)) * initSf; return (1 - d) / d; });
        const npts = pts.map(p => p.slice()), normals = pts.map(() => [0, 0, 0]);
        let sf = 0, sfi = 0.05, xr = 0, yr = 0, phase = 0;

        const BASE_CHECK = [[[1, 0, 0], [0, 1, 0]], [[0, 1, 0], [0, 0, 1]], [[0, 0, 1], [1, 0, 1]], [[1, 0, 1], [0, 1, 1]], [[0, 1, 1], [1, 1, 0]], [[1, 1, 0], [.5, .5, 1]], [[.5, .5, 1], [1, .5, .5]], [[1, .5, .5], [1, 0, 0]]];
        const BASE_SIDE = [[1, 0, 0], [0, 1, 0], [0, 0, 1], [1, 0, 1], [0, 1, 1], [1, 1, 0], [.5, .5, 1], [1, .5, .5]];
        const checker = BASE_CHECK.map(p => p.map(c => c.slice())), sideCols = BASE_SIDE.map(c => c.slice()), solid = [1, 1, 1];
        const cycleColors = () => {
            const list = cfg.color === 0 ? checker.flat() : cfg.color === 1 ? sideCols : [solid];
            list.forEach((c, i) => c.splice(0, 3, ...G.hsv((phase + i * 2 * Math.PI / list.length) * 180 / Math.PI)));
        };

        const floater = G.floater(env, 0.005);
        const proj = M.mul(M.persp(45, 1, 2, 5), M.lookAt(0, 0, 3.5, 0, 0, 0, 0, 1, 0));

        const build = () => {
            for (let i = 0; i < pts.length; i++) {
                const f = vlen[i] * sf + 1;
                npts[i][0] = pts[i][0] * f;
                npts[i][1] = pts[i][1] * f;
                npts[i][2] = pts[i][2] * f;
                normals[i][0] = normals[i][1] = normals[i][2] = 0;
            }
            const tris = [];
            strips.forEach((list, side) => list.forEach((s, strip) => {
                for (let k = 2; k < s.length; k++) {
                    const tri = k % 2 === 0 ? [s[k - 2], s[k - 1], s[k]] : [s[k - 2], s[k], s[k - 1]];
                    const [a, b, c] = tri.map(i => npts[i]);
                    const u = [b[0] - a[0], b[1] - a[1], b[2] - a[2]], v = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
                    const n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]];
                    for (const i of tri) { normals[i][0] += n[0]; normals[i][1] += n[1]; normals[i][2] += n[2]; }
                    tris.push({ tri, side, strip, k, vk: [k - 2, k % 2 === 0 ? k - 1 : k, k % 2 === 0 ? k : k - 1] });
                }
            }));
            const pos = new Float32Array(tris.length * 9), nrm = new Float32Array(tris.length * 9), col = new Float32Array(tris.length * 9);
            // Checkerboard colours go by a vertex's place in its strip; flat shading uses the triangle's last vertex
            const colorOf = (side, strip, k) => cfg.color === 1 ? sideCols[side] : cfg.color === 2 ? solid : checker[side][((cfg.slanted ? (k + 1) >> 1 : k >> 1) + strip) & 1];
            tris.forEach(({ tri, side, strip, k, vk }, t) => {
                const flatCol = colorOf(side, strip, k);
                tri.forEach((i, j) => {
                    const o = t * 9 + j * 3, n = normals[i], l = Math.hypot(...n) || 1;
                    pos.set(npts[i], o);
                    nrm.set([n[0] / l, n[1] / l, n[2] / l], o);
                    col.set(cfg.smooth ? colorOf(side, strip, vk[j]) : flatCol, o);
                });
            });
            mesh.set({ pos, nrm, col });
        };

        return {
            frame(dt) {
                const k = dt * 30;
                floater.fit((0.25 + 0.5 * cfg.size / 100) * (env.w + env.h) / 2);
                floater.step(k);
                if (cfg.spin) { xr += 3 * k; yr += 2 * k; }
                if (cfg.bloom) {
                    sf += sfi * k;
                    if (sf > maxSf) { sf = maxSf; sfi = -Math.abs(sfi); } else if (sf < minSf) { sf = minSf; sfi = Math.abs(sfi); }
                }
                if (cfg.cycle) { cycleColors(); phase += 2.5 * Math.PI / 180 * k; }
                build();
                gl.viewport(0, 0, env.w, env.h);
                gl.clearColor(0, 0, 0, 1);
                gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
                floater.viewport(gl);
                gl.enable(gl.DEPTH_TEST);
                gl.depthFunc(gl.LESS);
                gl.frontFace(gl.CCW);
                if (cfg.two) gl.disable(gl.CULL_FACE); else { gl.enable(gl.CULL_FACE); gl.cullFace(gl.BACK); }
                let mv = M.rotate(M.id(), xr, 1, 0, 0);
                mv = M.rotate(mv, yr, 0, 1, 0);
                P.lights([{ pos: [2, 2, 10, 1], amb: [0, 0, 0], dif: [1, 1, 1], spc: [1, 1, 1] }], [0.2, 0.2, 0.2]);
                P.set({ mat: { ka: [0.2, 0.2, 0.2], kd: [1, 1, 1], ks: [0.8, 0.8, 0.8], shin: 30 }, vcol: true, two: cfg.two, flat: !cfg.smooth }).matrices(proj, mv);
                mesh.draw();
            },
            destroy() { mesh.free(); P.free(); }
        };
    },
    setup(cfg, done) {
        const rad = (label, v) => {
            const r = radio('ss-fb-col', SaverUI.lbl(label), cfg.color === v, String(v));
            r.querySelector('input').addEventListener('change', () => (cfg.color = v));
            return r;
        };
        const chk = (label, key) => checkbox(SaverUI.lbl(label), cfg[key], v => (cfg[key] = v));
        const geom = h('select', { class: 'field', 'aria-label': 'Shape', onchange: e => (cfg.geom = +e.target.value) },
            this.GEOMS.map((l, i) => h('option', { value: i, selected: i === cfg.geom }, l)));
        const complexity = SaverUI.slider({ min: 2, max: 10, value: cfg.subdiv, label: 'Complexity', onchange: v => (cfg.subdiv = v) });
        const size = SaverUI.slider({ min: 10, max: 100, value: cfg.size, label: 'Size', onchange: v => (cfg.size = v) });
        SaverUI.setup({
            id: 'ss-setup', title: '3D FlowerBox Setup', width: 390,
            content: h('div', null,
                h('div', { class: 'ss-cols' },
                    SaverUI.group('Coloring', h('div', { class: 'ss-stack' }, rad('&Checkerboard', 0), rad('&Per Side', 1), rad('&One Color', 2),
                        h('div', { class: 'ss-gap' }), chk('S&mooth', 'smooth'), chk('S&lanted', 'slanted'), chk('C&ycle', 'cycle'))),
                    h('div', { class: 'ss-grow' },
                        h('div', { class: 'ss-stack ss-pad' }, chk('&Spin', 'spin'), chk('&Bloom', 'bloom'), checkbox(SaverUI.lbl('&Two-sided'), cfg.two, v => (cfg.two = v))),
                        SaverUI.group('S&hape', h('div', { class: 'combo' }, geom)))),
                SaverUI.group('Comple&xity', SaverUI.range('Min', 'Max', complexity)),
                SaverUI.group('Si&ze', SaverUI.range('Smaller', 'Larger', size))),
            onOk: () => done(cfg)
        });
    }
};

/* ---- 3D Flying Objects ---- */
Savers.impl.flyingobjects = {
    gl: true, maxPixels: 2.5e6,
    STYLES: ['Windows Logo', 'Explode', 'Ribbon', 'Two Ribbons', 'Splash', 'Twist', 'Textured Flag'],
    defaults: { style: 0, cycle: false, smooth: true, tessel: 100, size: 50, texture: null },
    create(env) {
        const gl = env.gl, cfg = env.cfg, G = SaverGL, M = G.m4;
        const P = G.program(gl), mesh = G.mesh(gl), RATE = 30;
        const tess = cfg.tessel <= 100 ? cfg.tessel / 100 : 1 + (cfg.tessel - 100) / 100;
        const rnd = () => Math.random();
        let bounce = false;
        const floater = G.floater(env, 0.01, () => (bounce = true));
        const WHITE = [1, 1, 1];
        const setMat = (col, o = {}) => P.set(Object.assign({ mat: { ka: col, kd: col, ks: o.ks || WHITE, shin: o.shin == null ? 128 : o.shin }, two: true, flat: !cfg.smooth }, o));
        const draw = (g, proj, mv, col, o) => { mesh.set(g.data()); setMat(col, o).matrices(proj, mv); mesh.draw(); };
        const norm = v => { const l = Math.hypot(...v) || 1; return [v[0] / l, v[1] / l, v[2] / l]; };
        const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
        // The spinning objects switch their spin axis whenever the window bounces
        const spin = { x: 0, y: 0, z: 0, ix: 0, iy: 0.1, iz: 0 };
        const turnAxis = () => {
            if (!bounce) return;
            bounce = false;
            if (spin.ix) { spin.ix = 0; spin.iy = 0.1; } else if (spin.iy) { spin.iy = 0; spin.iz = 0.1; } else { spin.iz = 0; spin.ix = 0.1; }
        };
        const LIGHT = [{ pos: [100, 100, 100, 0], amb: [0, 0, 0], dif: [0.7, 0.7, 0.7], spc: [1, 1, 1] }];
        const deg = r => r * 180 / Math.PI;
        let hue = 0;
        const style = [

            // Windows Logo: the four-pane flag in its frame, waving, with its trail of blocks
            () => {
                const TB = 0.1, CROSS = 0.6522 * TB, GAP = TB / 8, HEIGHT = GAP * 6 + 7 * TB, WIDTH = 0.7024 * HEIGHT, D = CROSS, TOTAL = TB * 1.1 * 6 + WIDTH, XT = 0.2;
                const frames = Math.max(5, Math.min(20, Math.floor(10 * tess))), prec = Math.max(5, Math.min(15, Math.floor(tess * 10.5)));
                const COLORS = [[0.3, 0.3, 0.3], [0.94, 0.37, 0.13], [0.22, 0.42, 0.78], [0.35, 0.71, 0.35], [0.95, 0.82, 0.12]];
                let wave = 0, rotY = 23, rotInc = 3;
                const zAt = x => {
                    const a = x - XT;
                    return Math.sin(wave + 2 * Math.PI * a / TOTAL) / 4 * Math.sqrt(Math.max(0, TOTAL - (a + TOTAL / 2)) / TOTAL);
                };
                const slope = x => (zAt(x + 1e-3) - zAt(x - 1e-3)) / 2e-3;
                // A block bent along the wave: faces is a subset of 'tbfklr' (top, bottom, front, back, left, right)
                const block = (g, x0, y0, w, hh, n, faces) => {
                    const xs = Array.from({ length: n + 1 }, (_, i) => x0 + w * i / n);
                    const strip = (pt, nr) => {
                        const base = g.p.length / 3;
                        xs.forEach(x => { const [a, b] = pt(x); g.v(...a, ...nr(x)); g.v(...b, ...nr(x)); });
                        for (let i = 0; i < n; i++) g.quadN(base + i * 2, base + i * 2 + 2, base + i * 2 + 3, base + i * 2 + 1, nr(xs[i]));
                    };
                    if (faces.includes('t')) strip(x => [[x, y0 + hh, zAt(x)], [x, y0 + hh, zAt(x) + D]], () => [0, 1, 0]);
                    if (faces.includes('b')) strip(x => [[x, y0, zAt(x)], [x, y0, zAt(x) + D]], () => [0, -1, 0]);
                    if (faces.includes('f')) strip(x => [[x, y0, zAt(x) + D], [x, y0 + hh, zAt(x) + D]], x => norm([-slope(x), 0, 1]));
                    if (faces.includes('k')) strip(x => [[x, y0, zAt(x)], [x, y0 + hh, zAt(x)]], x => norm([slope(x), 0, -1]));
                    for (const [side, x, nx] of [['l', x0, -1], ['r', x0 + w, 1]]) {
                        if (!faces.includes(side)) continue;
                        const z = zAt(x), b = g.p.length / 3;
                        g.v(x, y0, z, nx, 0, 0); g.v(x, y0 + hh, z, nx, 0, 0); g.v(x, y0 + hh, z + D, nx, 0, 0); g.v(x, y0, z + D, nx, 0, 0);
                        g.quadN(b, b + 1, b + 2, b + 3, [nx, 0, 0]);
                    }
                };
                return {
                    update(k) {
                        wave += 2 * Math.PI / frames * k;
                        rotY += rotInc * k;
                        if (rotY < -45 || rotY > 45) { rotY = Math.max(-45, Math.min(45, rotY)); rotInc = -rotInc; }
                        if (cfg.cycle) { COLORS[0] = G.hsv(hue); hue = (hue + k) % 360; }
                    },
                    draw() {
                        const proj = M.translate(M.ortho(-1, 1, -0.75, 1.25, 0, 3), 0, 0, -1.5);
                        const mv = M.rotate(M.rotate(M.rotate(M.id(), 23, 1, 0, 0), rotY, 0, 1, 0), 5.7, 0, 0, 1);
                        P.lights([{ pos: [20, -10, 20, 0], amb: [0, 0, 0], dif: [0.7, 0.7, 0.7], spc: [1, 1, 1] }, { pos: [-20, 5, 0, 0], amb: [0, 0, 0], dif: [0.4, 0.4, 0.4], spc: [0, 0, 0] }], [0.21, 0.21, 0.21]);
                        gl.enable(gl.CULL_FACE);
                        // Frame
                        let g = G.geo();
                        const inner = WIDTH - TB;
                        block(g, XT, 0, inner, TB, prec, 'tbfkl');
                        block(g, XT, HEIGHT - TB, inner, TB, prec, 'tbfkl');
                        const half = (inner - CROSS) / 2, mid = (HEIGHT - CROSS) / 2;
                        block(g, XT, mid, half, CROSS, Math.ceil(prec / 2), 'tbfkl');
                        block(g, XT + half + CROSS, mid, half, CROSS, Math.ceil(prec / 2), 'tbfk');
                        block(g, XT + WIDTH - TB, 0, TB, HEIGHT, 3, 'tbfklr');
                        block(g, XT + half, TB, CROSS, HEIGHT - 2 * TB, 1, 'fklr');
                        draw(g, proj, mv, COLORS[0], { shin: 60 });
                        // The four panes, set into the back of the frame
                        const ph = (HEIGHT - 2 * TB - CROSS) / 2, pn = Math.ceil(prec / 2);
                        [[XT, TB, 2], [XT + half + CROSS, TB, 4], [XT, TB + ph + CROSS, 1], [XT + half + CROSS, TB + ph + CROSS, 3]].forEach(([x0, y0, c]) => {
                            g = G.geo();
                            const base = 0;
                            for (let i = 0; i <= pn; i++) {
                                const x = x0 + half * i / pn, z = zAt(x) + D / 2, nr = norm([-slope(x), 0, 1]);
                                g.v(x, y0, z, ...nr);
                                g.v(x, y0 + ph, z, ...nr);
                            }
                            for (let i = 0; i < pn; i++) g.quadN(base + i * 2, base + i * 2 + 2, base + i * 2 + 3, base + i * 2 + 1, [0, 0, 1]);
                            gl.disable(gl.CULL_FACE);
                            draw(g, proj, mv, COLORS[c], { ks: [0, 0, 0] });
                            gl.enable(gl.CULL_FACE);
                        });
                        // The streamers: columns of blocks that shrink away to the left
                        const groups = [[], [], []];
                        let w = TB * 1.1, hh = TB, cx = XT - w - GAP * 2;
                        for (let i = 0; i < 6; i++) {
                            for (let j = 0; j < 7; j++) {
                                const m = (j % 3 === 0 || i === 0) ? 0 : j < 3 ? 2 : 1;
                                groups[m].push([cx + (TB * 1.1 - w) / 2, j * (TB + GAP) + (TB - hh) / 2, w, hh]);
                            }
                            cx -= TB * 1.1 + GAP * 2;
                            w *= 0.8;
                            hh *= 0.8;
                        }
                        groups.forEach((list, m) => {
                            g = G.geo();
                            list.forEach(([x, y, bw, bh]) => block(g, x, y, bw, bh, 1, 'tbfklr'));
                            draw(g, proj, mv, COLORS[m], { ks: [0.5, 0.5, 0.5], shin: 60 });
                        });
                        gl.disable(gl.CULL_FACE);
                    }
                };
            },

            // Explode: a ball that bursts into its facets and pulls itself back together
            () => {
                const prec = Math.max(5, Math.min(20, Math.floor(tess * 10.5))), R = 0.3, STEPS = 30;
                const circle = Array.from({ length: prec }, (_, i) => { const a = Math.PI / 2 - i * Math.PI / (prec - 1); return [R * Math.cos(a), R * Math.sin(a)]; });
                const faces = [];
                for (let j = 0; j < prec; j++) {
                    const r0 = j * 2 * Math.PI / (prec - 1), r1 = r0 + 2 * Math.PI / (prec - 1);
                    for (let i = 0; i < prec - 1; i++) {
                        const pt = (c, r) => [c[0] * Math.cos(r), c[1], -c[0] * Math.sin(r)];
                        faces.push({ p: [pt(circle[i], r1), pt(circle[i], r0), pt(circle[i + 1], r0), pt(circle[i + 1], r1)], rot: [0, 0, 0], step: [0, 0, 0] });
                    }
                }
                const reseed = () => faces.forEach(f => { f.rot = [0, 0, 0]; f.step = [0, 1, 2].map(() => Math.floor(rnd() * 4) * Math.PI / (STEPS + 1)); });
                reseed();
                let r = 0, maxR = 0, count = 0, out = true, rest = 0, lightSpin = 0, lightInc = 5, color = G.hsv(0);
                const rotate = (v, [ax, ay, az]) => {
                    let [x, y, z] = v;
                    [y, z] = [y * Math.cos(ax) - z * Math.sin(ax), y * Math.sin(ax) + z * Math.cos(ax)];
                    [x, z] = [x * Math.cos(ay) + z * Math.sin(ay), -x * Math.sin(ay) + z * Math.cos(ay)];
                    [x, y] = [x * Math.cos(az) - y * Math.sin(az), x * Math.sin(az) + y * Math.cos(az)];
                    return [x, y, z];
                };
                return {
                    step() {
                        turnAxis();
                        if (cfg.cycle) { color = G.hsv(hue); hue = (hue + 1) % 360; }
                        lightSpin += lightInc;
                        if (lightSpin > 90 || lightSpin < 0) lightInc = -lightInc;
                        if (rest) { rest--; return; }
                        faces.forEach(f => f.rot = f.rot.map((a, i) => a + f.step[i]));
                        if (out) { maxR = r; r += 0.3 * Math.pow((STEPS - count) / STEPS, 4); } else r -= maxR / STEPS;
                        if (++count > STEPS) {
                            out = !out;
                            count = 0;
                            if (out) { rest = 10; r = 0; reseed(); }
                        }
                    },
                    draw() {
                        const proj = M.translate(M.frustum(-0.33, 0.33, -0.33, 0.33, 0.3, 3), 0, 0, -1.5);
                        const lr = -lightSpin * Math.PI / 180, lx = Math.cos(lr) * 100 + Math.sin(lr) * 100, lz = -Math.sin(lr) * 100 + Math.cos(lr) * 100;
                        P.lights([{ pos: [lx, 100, lz, 0], amb: [0, 0, 0], dif: [0.7, 0.7, 0.7], spc: [1, 1, 1] }], [0.21, 0.21, 0.21]);
                        const g = G.geo();
                        for (const f of faces) {
                            const piv = f.p[0], ids = f.p.map(p => {
                                const q = rotate([p[0] - piv[0], p[1] - piv[1], p[2] - piv[2]], f.rot);
                                const nn = rotate(norm(p), f.rot);
                                return g.v(q[0] + piv[0] * (1 + r), q[1] + piv[1] * (1 + r), q[2] + piv[2] * (1 + r), ...nn);
                            });
                            g.quad(...ids);
                        }
                        gl.disable(gl.CULL_FACE);
                        draw(g, proj, M.id(), color, { shin: 100, back: [0.8, 0.8, 0.8] });
                    }
                };
            },

            // Ribbon (and Two Ribbons): a Hermite curve swept into a band
            two => () => {
                const prec = Math.max(4, Math.floor(tess * 40.5));
                let rotA = 0, rotB = Math.PI / 2, stepA = Math.PI / (2 * prec), counter = 0, color = [0, 1, 0];
                const band = () => {
                    const p1 = [-0.5, 0], p2 = [0.5 * Math.sin(rotB), 0], v1 = [4 * Math.cos(rotA), 4 * Math.sin(rotA)], v2 = [0, 3];
                    const H = t => [2 * t ** 3 - 3 * t ** 2 + 1, -2 * t ** 3 + 3 * t ** 2, t ** 3 - 2 * t ** 2 + t, t ** 3 - t ** 2];
                    const g = G.geo(), pts = [];
                    for (let i = 1; i <= prec; i++) {
                        const [a, b, c, d] = H(i / prec);
                        pts.push([a * p1[0] + b * p2[0] + c * v1[0] + d * v2[0], a * p1[1] + b * p2[1] + c * v1[1] + d * v2[1]]);
                    }
                    // Normals across the band, smoothed along it
                    const fn = pts.slice(0, -1).map((p, i) => norm(cross([pts[i + 1][0] - p[0], 0, pts[i + 1][1] - p[1]], [0, -0.5, 0])));
                    pts.forEach(([x, y], i) => {
                        const a = fn[Math.max(0, i - 1)], b = fn[Math.min(fn.length - 1, i)], nn = norm([a[0] + b[0], a[1] + b[1], a[2] + b[2]]);
                        g.v(x, 0.25, y, ...nn);
                        g.v(x, -0.25, y, ...nn);
                    });
                    for (let i = 0; i < prec - 1; i++) g.quadN(i * 2, i * 2 + 2, i * 2 + 3, i * 2 + 1, g.n.slice(i * 6, i * 6 + 3));
                    return g;
                };
                return {
                    step() {
                        turnAxis();
                        rotA += stepA;
                        rotB += Math.PI / (4 * prec);
                        if (++counter >= 2 * prec) { stepA = -stepA; counter = 0; }
                        spin.x += spin.ix; spin.y += spin.iy; spin.z += spin.iz;
                        if (cfg.cycle) { color = G.hsv(hue); hue = (hue + 1) % 360; }
                    },
                    draw() {
                        let proj = M.translate(M.ortho(-1.1, 1.1, -1.1, 1.1, 0, 3), 0, 0, -1.5);
                        proj = M.rotate(M.rotate(M.rotate(proj, 50, 1, 0, 0), 50, 0, 1, 0), 12, 0, 0, 1);
                        P.lights(LIGHT, [0.21, 0.21, 0.21]);
                        gl.disable(gl.CULL_FACE);
                        const g = band();
                        const mv = M.rotate(M.rotate(M.rotate(M.id(), deg(spin.x), 1, 0, 0), deg(spin.y), 0, 1, 0), deg(spin.z), 0, 0, 1);
                        draw(g, proj, mv, cfg.cycle ? color : [0, 1, 0]);
                        if (two) {
                            const mv2 = M.rotate(M.rotate(M.rotate(M.translate(M.id(), 0.05, 0, 0), deg(spin.y), 1, 0, 0), deg(spin.x), 0, 1, 0), deg(spin.z), 0, 0, 1);
                            draw(g, proj, mv2, cfg.cycle ? color.map(c => 1 - c) : [0, 0, 1]);
                        }
                    }
                };
            },

            // Splash: drops falling into a round pool that ripples
            () => {
                const n = Math.max(4, Math.floor(tess * 10.5)), DROPS = 10, radiusFact = Math.max(0.35, tess);
                const outY = new Float32Array(n), inY = new Float32Array(n);
                // The drop's profile morphs from a teardrop to a ball as it falls
                const drops = [];
                for (let d = 0; d < DROPS; d++) {
                    const fa = d / (n - 1), fb = 1 - fa, prof = [];
                    for (let i = 0; i < n; i++) {
                        const ca = Math.PI / 2 - i * Math.PI / (n - 1), cx = 0.5 * Math.cos(ca), cy = 0.5 * Math.sin(ca);
                        const da = Math.PI / 4 - i * (Math.PI / 4) / (n - 1), rr = 1.5 * Math.sqrt(Math.abs(Math.sin(2 * da))), x = rr * Math.cos(da), y = rr * Math.sin(da);
                        const dx = x * Math.SQRT1_2 - y * Math.SQRT1_2, dy = x * Math.SQRT1_2 + y * Math.SQRT1_2 - 1;
                        prof.push([fa * cx + fb * dx, fa * cy + fb * dy]);
                    }
                    const g = G.geo();
                    for (let j = 0; j <= n; j++) {
                        const r = j * 2 * Math.PI / n;
                        prof.forEach(([px, py], i) => {
                            const prev = prof[Math.max(0, i - 1)], next = prof[Math.min(n - 1, i + 1)];
                            const tx = next[0] - prev[0], ty = next[1] - prev[1];
                            const nr = norm([-ty * Math.cos(r), tx, ty * Math.sin(r)]);
                            g.v(px * Math.cos(r), py, -px * Math.sin(r), ...(Math.abs(px) < 1e-6 && Math.abs(nr[1]) < 0.1 ? [0, Math.sign(py) || 1, 0] : nr));
                        });
                    }
                    for (let j = 0; j < n; j++) for (let i = 0; i < n - 1; i++) { const a = j * n + i, b = a + n; g.quad(a, a + 1, b + 1, b); }
                    drops.push(g);
                }
                let zrot = 0, zInc = 3, yrot = 0, yInc = 1.5, ypos = 1, dropnum = 0, radius = 0.3, damp = 1, mag = 0, w = 1, freq = 1, minr = 0, dist = 0, myrot = 0, color = [0.235, 0, 0.78];
                const ripple = () => {
                    const posInc = 1 / n;
                    for (let i = n - 1, r = 1; i >= 0; i--, r -= posInc) {
                        const v = i === 0 ? (minr !== 0 ? -mag * Math.cos(w + r * freq) * Math.exp(-damp * r / 2) : inY[0] * 0.95) : outY[i - 1] * 0.95;
                        outY[i] = v;
                    }
                    for (let i = 0; i < n; i++) inY[i] = i === n - 1 ? -outY[i] : inY[i + 1] * 0.95;
                };
                return {
                    step() {
                        zrot += zInc;
                        if (zrot >= 45) { zrot = 45; zInc = -(2 + rnd() * 3); } else if (zrot <= -45) { zrot = -45; zInc = 2 + rnd() * 3; }
                        yrot += yInc;
                        if (yrot >= 10) { yrot = 10; yInc = -(1 + rnd() * 2); }
                        if (ypos + 0.5 < -radius && mag < 0.05) { radius = rnd() / 6 + 0.1; ypos = 1; dropnum = 0; }
                        dist = ypos + 0.5;
                        if (dist > -radius / 2 && dist < radius / 2) {
                            dist = dist <= 0 ? radius / 2 : radius / 2 - dist;
                            freq = Math.max(0.2, 0.25 * Math.PI / dist);
                            minr = radius;
                            damp = 20;
                            mag = 0.35 / radiusFact + 0.2 * dist;
                            w = 0;
                        } else {
                            minr = Math.max(0, minr - 0.05);
                            mag *= 0.95;
                            if (minr === 0) { w -= Math.PI / 6; mag *= 0.75; }
                            if (damp > 0) damp -= 1;
                        }
                        ripple();
                        myrot += 0.1;
                        ypos -= 0.08;
                        dropnum = Math.max(0, Math.min(DROPS - 1, Math.floor((DROPS - 1) - ypos * (DROPS - 1))));
                        if (cfg.cycle) { color = G.hsv(hue); hue = (hue + 1) % 360; }
                    },
                    draw() {
                        const proj = M.ortho(-1.5, 1.5, -1.5, 1.5, 0, 3);
                        P.lights(LIGHT, [0.21, 0.21, 0.21]);
                        gl.disable(gl.CULL_FACE);
                        const base = M.rotate(M.rotate(M.translate(M.id(), 0, 0, -1.5), zrot, 0, 0, 1), 30, 1, 0, 0);
                        const pool = M.rotate(M.translate(base, 0, -0.5, 0), deg(myrot), 0, 1, 0);
                        // Water: rings of heights; normals from the neighbouring points
                        const g = G.geo(), y = i => i === n - 1 ? 0 : outY[i] + inY[i];
                        for (let i = 0; i < n; i++) for (let j = 0; j <= n; j++) {
                            const a = j * 2 * Math.PI / n, rr = i / (n - 1);
                            const dy = (y(Math.min(n - 1, i + 1)) - y(Math.max(0, i - 1))) / (2 / (n - 1));
                            const nr = norm([-dy * Math.cos(a), 1, -dy * Math.sin(a)]);
                            g.v(rr * Math.cos(a), y(i), rr * Math.sin(a), ...nr);
                        }
                        for (let i = 0; i < n - 1; i++) for (let j = 0; j < n; j++) { const a = i * (n + 1) + j, b = a + n + 1; g.quadN(a, b, b + 1, a + 1, [0, 1, 0]); }
                        draw(g, proj, pool, cfg.cycle ? color : [0.235, 0, 0.78]);
                        const rim = G.geo();
                        for (let j = 0; j <= n; j++) {
                            const a = j * 2 * Math.PI / n, c = Math.cos(a), s = Math.sin(a);
                            rim.v(c, 0, s, c, 0, s);
                            rim.v(c, -0.5, s, c, 0, s);
                        }
                        for (let j = 0; j < n; j++) rim.quadN(j * 2, j * 2 + 1, j * 2 + 3, j * 2 + 2, rim.n.slice(j * 6, j * 6 + 3));
                        draw(rim, proj, pool, cfg.cycle ? color : [0, 0, 1], { flat: true });
                        if (dist > -radius) {
                            const dm = M.rotate(M.scale(M.translate(base, 0, ypos, 0), radius, radius, radius), 180, 1, 0, 0);
                            draw(drops[dropnum], proj, dm, cfg.cycle ? color : [0.235, 0, 0.78]);
                        }
                    }
                };
            },

            // Twist: a tube round a figure of eight that keeps twisting through itself
            () => {
                const n = Math.max(5, Math.floor(tess * 32.5)), RING = 9;
                const lem = i => {
                    let a = i * Math.PI / (n - 1);
                    if (a >= Math.PI) a -= Math.PI;
                    const ang = a > Math.PI / 2 ? 2 * Math.PI - a : a, r = 0.5 * Math.sqrt(Math.max(0, Math.sin(2 * ang)));
                    return [r * Math.cos(ang), r * Math.sin(ang)];
                };
                let twist = 0, twistInc = 0.05, zrot = 0.2, zInc = 0.1, color = [1, 1, 0];
                return {
                    step() {
                        spin.x += spin.ix; spin.y += spin.iy; spin.z += spin.iz;
                        turnAxis();
                        zrot += zInc;
                        if (zrot >= Math.PI / 4) { zrot = Math.PI / 4; zInc = -0.03; } else if (zrot <= -Math.PI / 4) { zrot = -Math.PI / 4; zInc = 0.03; }
                        if (twist >= 1) twistInc = -0.01; else if (twist <= -1) twistInc = 0.01;
                        twist += twistInc;
                        if (cfg.cycle) { color = G.hsv(hue); hue = (hue + 1) % 360; }
                    },
                    draw() {
                        let proj = M.translate(M.ortho(-1.5, 1.5, -1.5, 1.5, 0, 3), 0, 0, -1.5);
                        proj = M.rotate(M.rotate(M.rotate(proj, deg(zrot), 0, 1, 0), 50, 1, 0, 0), 50, 0, 0, 1);
                        P.lights(LIGHT, [0, 0, 0]);
                        gl.disable(gl.CULL_FACE);
                        const g = G.geo();
                        for (let i = 0; i < n - 1; i++) {
                            const [x1, y1] = lem(i), [x2, y2] = lem(i + 0.001);
                            const t = norm([x2 - x1, y2 - y1, 0]), nn = [-t[1], t[0], 0], z = twist * Math.cos(2 * Math.PI * i / (n - 1));
                            for (let j = 0; j <= RING; j++) {
                                const a = j * 2 * Math.PI / RING, c = Math.cos(a), s = Math.sin(a);
                                g.v(x1 + 0.15 * c * nn[0], y1 + 0.15 * c * nn[1], z + 0.15 * s, c * nn[0], c * nn[1], s);
                            }
                        }
                        for (let i = 0; i < n - 1; i++) {
                            const next = (i + 1) % (n - 1);
                            for (let j = 0; j < RING; j++) { const a = i * (RING + 1) + j, b = next * (RING + 1) + j; g.quadN(a, b, b + 1, a + 1, g.n.slice(a * 3, a * 3 + 3)); }
                        }
                        const mv = M.rotate(M.rotate(M.rotate(M.translate(M.id(), 0, -0.5, 0), deg(spin.x), 1, 0, 0), deg(spin.y), 0, 1, 0), deg(spin.z), 0, 0, 1);
                        draw(g, proj, mv, cfg.cycle ? color : [1, 1, 0]);
                    }
                };
            },

            // Textured Flag: a picture flapping in the wind
            () => {
                const tex = G.texture(gl, Savers.picture(cfg.texture, B + 'savers/maze-cover.webp'));
                const frames = Math.max(5, Math.min(20, Math.floor(10 * tess)));
                let wave = 0, rotY = 23, rotInc = 3;
                return {
                    update(k) {
                        wave += 2 * Math.PI / frames * k;
                        rotY += rotInc * k;
                        if (rotY < -65 || rotY > 25) { rotY = Math.max(-65, Math.min(25, rotY)); rotInc = -rotInc; }
                    },
                    draw() {
                        // Keep the picture's proportions, stretched a little wider as the original does
                        const aspect = (tex.h / tex.w) * 1.4;
                        const W = aspect < 1 ? 0.75 : 0.75 / aspect, Ht = aspect < 1 ? 0.75 * aspect : 0.75;
                        const zAt = x => Math.sin(wave + 2 * Math.PI * x / W) / 4 * Math.sqrt(Math.max(0, W - x) / W);
                        const g = G.geo(), cols = 16;
                        for (let i = 0; i < cols; i++) {
                            const x = W * i / cols, z = zAt(x), dz = (zAt(x + 1e-3) - zAt(x - 1e-3)) / 2e-3, nr = norm([-dz, 0, 1]);
                            g.v(x, 0, z, ...nr, i / (cols - 1), 0);
                            g.v(x, Ht, z, ...nr, i / (cols - 1), 1);
                        }
                        for (let i = 0; i < cols - 1; i++) g.quad(i * 2, i * 2 + 2, i * 2 + 3, i * 2 + 1);
                        const proj = M.translate(M.ortho(-0.25, 1, -0.25, 1, 0, 3), 0, 0, -1.5);
                        const mv = M.rotate(M.rotate(M.rotate(M.id(), 23, 1, 0, 0), rotY, 0, 1, 0), 5.7, 0, 0, 1);
                        P.lights([{ pos: [20, 5, 20, 0], amb: [0, 0, 0], dif: [0.7, 0.7, 0.7], spc: [1, 1, 1] }, { pos: [-20, 5, 0, 0], amb: [0, 0, 0], dif: [0.4, 0.4, 0.4], spc: [0, 0, 0] }], [0.21, 0.21, 0.21]);
                        gl.disable(gl.CULL_FACE);
                        gl.disable(gl.DEPTH_TEST);
                        draw(g, proj, mv, WHITE, { tex, shin: 60 });
                    }
                };
            }
        ];
        const make = [style[0], style[1], style[2](false), style[2](true), style[3], style[4], style[5]][cfg.style] || style[0];
        const obj = make();
        let acc = 0;
        const side = () => (0.25 + 0.30 * cfg.size / 100) * (env.w + env.h) / 2;
        return {
            frame(dt) {
                const k = dt * RATE;
                floater.fit(side());
                floater.step(k);
                if (obj.update) { turnAxis(); obj.update(k); }
                if (obj.step) {
                    acc += dt;
                    for (let i = 0; acc >= 1 / RATE && i < 4; i++) { acc -= 1 / RATE; obj.step(); }
                }
                gl.viewport(0, 0, env.w, env.h);
                gl.clearColor(0, 0, 0, 1);
                gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
                floater.viewport(gl);
                gl.enable(gl.DEPTH_TEST);
                gl.depthFunc(gl.LESS);
                gl.frontFace(gl.CCW);
                obj.draw();
            },
            destroy() { mesh.free(); P.free(); }
        };
    },
    setup(cfg, done) {
        const OPTS = [0x1b, 0x1b, 0x1b, 0x1b, 0x1b, 0x1b, 0x3a];   // colour cycling, smooth, resolution, size, texture
        const types = h('select', { class: 'field', 'aria-label': 'Style' }, this.STYLES.map((l, i) => h('option', { value: i, selected: i === cfg.style }, l)));
        const cycle = checkbox(SaverUI.lbl('&Color-cycling'), cfg.cycle, v => (cfg.cycle = v));
        const smooth = checkbox(SaverUI.lbl('&Smooth shading'), cfg.smooth, v => (cfg.smooth = v));
        const tessel = SaverUI.range('Min', 'Max', SaverUI.slider({ min: 0, max: 200, value: cfg.tessel, label: 'Resolution', onchange: v => (cfg.tessel = v) }));
        const size = SaverUI.range('Min', 'Max', SaverUI.slider({ min: 0, max: 100, value: cfg.size, label: 'Size', onchange: v => (cfg.size = v) }));
        let win;
        const sync = () => {
            const o = OPTS[cfg.style];
            cycle.querySelector('input').disabled = !(o & 1);
            smooth.querySelector('input').disabled = !(o & 2);
            tessel.disable(!(o & 8));
            size.disable(!(o & 0x10));
            if (win) win.extras[0].disabled = !(o & 0x20);
        };
        types.addEventListener('change', () => { cfg.style = +types.value; sync(); });
        win = SaverUI.setup({
            id: 'ss-setup', title: '3D Flying Objects Setup', width: 390,
            content: h('div', { class: 'ss-grid2' },
                SaverUI.group('Object', h('label', { class: 'ss-line' }, SaverUI.lbl('S&tyle:'), h('div', { class: 'combo', style: { flex: '1' } }, types))),
                SaverUI.group('Color Usage', h('div', { class: 'ss-cols ss-tight' }, cycle, smooth)),
                SaverUI.group('&Resolution', tessel),
                SaverUI.group('Si&ze', size)),
            extra: [['Te&xture...', () => SaverUI.pickTexture(id => (cfg.texture = id))]],
            onOk: () => done(cfg)
        });
        sync();
    }
};

/* ---- 3D Text ---- */
Savers.impl.text3d = {
    gl: true, glAttrs: { stencil: true }, maxPixels: 2.5e6,
    ROTS: ['None', 'See-Saw', 'Wobble', 'Random'],
    defaults: { clock: false, text: 'Windows 98', textured: false, texture: null, size: 50, speed: 50, tessel: 10, rot: 3, font: { face: 'Arial', bold: false, italic: false } },

    // The letters' outlines: the text drawn on a canvas, traced with marching squares and simplified.
    // Returns closed loops in em units (y up), with the solid on the left of each loop.
    outline(text, font, tessel) {
        const PX = 96, c = document.createElement('canvas'), x = c.getContext('2d', { willReadFrequently: true });
        const css = `${font.italic ? 'italic ' : ''}${font.bold ? 'bold ' : ''}${PX}px "${font.face}", Arial, sans-serif`;
        x.font = css;
        const tw = Math.ceil(x.measureText(text || ' ').width);
        c.width = tw + PX / 2 + 8;
        c.height = Math.ceil(PX * 1.5) + 8;
        x.font = css;
        x.fillStyle = '#fff';
        const baseline = Math.round(PX * 1.1) + 4;
        x.fillText(text, 4 + PX / 8, baseline);
        const cw = c.width, ch = c.height, d = x.getImageData(0, 0, cw, ch).data, T = 0.5;
        const v = (i, j) => d[(j * cw + i) * 4 + 3] / 255;
        const lerp = (a, b) => (T - a) / ((b - a) || 1e-9);
        // Edge points are keyed by the grid edge they sit on, so segments join up into loops
        const point = (edge, i, j) => [
            () => ['h' + i + ',' + j, i + lerp(v(i, j), v(i + 1, j)), j],
            () => ['v' + (i + 1) + ',' + j, i + 1, j + lerp(v(i + 1, j), v(i + 1, j + 1))],
            () => ['h' + i + ',' + (j + 1), i + lerp(v(i, j + 1), v(i + 1, j + 1)), j + 1],
            () => ['v' + i + ',' + j, i, j + lerp(v(i, j), v(i, j + 1))]
        ][edge]();
        const TABLE = { 1: [[3, 2]], 2: [[2, 1]], 3: [[3, 1]], 4: [[1, 0]], 6: [[2, 0]], 7: [[3, 0]], 8: [[0, 3]], 9: [[0, 2]], 11: [[0, 1]], 12: [[1, 3]], 13: [[1, 2]], 14: [[2, 3]] };
        const segs = new Map();
        for (let j = 0; j < ch - 1; j++) for (let i = 0; i < cw - 1; i++) {
            const cs = (v(i, j) >= T) * 8 + (v(i + 1, j) >= T) * 4 + (v(i + 1, j + 1) >= T) * 2 + (v(i, j + 1) >= T);
            if (!cs || cs === 15) continue;
            let list = TABLE[cs];
            if (cs === 5 || cs === 10) {
                const mid = (v(i, j) + v(i + 1, j) + v(i + 1, j + 1) + v(i, j + 1)) / 4 >= T;
                list = cs === 5 ? (mid ? [[3, 0], [1, 2]] : [[3, 2], [1, 0]]) : (mid ? [[0, 1], [2, 3]] : [[0, 3], [2, 1]]);
            }
            for (const [a, b] of list) {
                const A = point(a, i, j), B = point(b, i, j);
                segs.set(A[0], [B[0], A[1], A[2]]);
            }
        }
        const eps = Math.max(0.2, 1.6 - 1.4 * tessel / 100);
        const simplify = (pts, lo, hi, keep) => {
            let best = -1, far = 0;
            const [ax, ay] = pts[lo], [bx, by] = pts[hi], len = Math.hypot(bx - ax, by - ay) || 1e-9;
            for (let k = lo + 1; k < hi; k++) {
                const dist = Math.abs((bx - ax) * (ay - pts[k][1]) - (ax - pts[k][0]) * (by - ay)) / len;
                if (dist > far) { far = dist; best = k; }
            }
            if (far > eps) { simplify(pts, lo, best, keep); keep[best] = true; simplify(pts, best, hi, keep); }
        };
        const loops = [], seen = new Set();
        for (const k0 of segs.keys()) {
            if (seen.has(k0)) continue;
            const pts = [];
            for (let k = k0; segs.has(k) && !seen.has(k); k = segs.get(k)[0]) { seen.add(k); pts.push([segs.get(k)[1], segs.get(k)[2]]); }
            if (pts.length < 3) continue;
            const n = pts.length;
            let far = 0;
            pts.forEach((p, k) => { if (Math.hypot(p[0] - pts[0][0], p[1] - pts[0][1]) > Math.hypot(pts[far][0] - pts[0][0], pts[far][1] - pts[0][1])) far = k; });
            const ring = pts.concat([pts[0]]), keep = { 0: true, [far]: true };
            simplify(ring, 0, far, keep);
            simplify(ring, far, n, keep);
            const out = pts.filter((p, k) => keep[k]).map(([px, py]) => [px / PX, (baseline - py) / PX]);
            if (out.length >= 3) loops.push(out);
        }
        return loops;
    },

    create(env) {
        const gl = env.gl, cfg = env.cfg, G = SaverGL, M = G.m4, self = Savers.impl.text3d, RATE = 20;
        const P = G.program(gl), sides = G.mesh(gl), fans = G.mesh(gl), caps = G.mesh(gl);
        const rnd = (a, b) => a + Math.random() * (b - a);
        const tex = cfg.textured ? G.texture(gl, Savers.picture(cfg.texture, B + 'savers/text3d-rainbow.webp')) : null;
        const depth = rnd(0.15, 0.6);
        const fovy = rnd(60, 90);
        let view = { aspect: 1, tanV: 1, dist: 3 }, ext = [1, 1], shown = null;

        const build = text => {
            const loops = self.outline(text, cfg.font, cfg.tessel);
            let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
            loops.forEach(l => l.forEach(([px, py]) => { x0 = Math.min(x0, px); x1 = Math.max(x1, px); y0 = Math.min(y0, py); y1 = Math.max(y1, py); }));
            if (!loops.length) { x0 = y0 = 0; x1 = y1 = 1; }
            const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, hz = depth / 2;
            ext = [x1 - x0, y1 - y0];
            const g = G.geo(), f = G.geo();
            for (const loop of loops) {
                const pts = loop.map(([px, py]) => [px - cx, py - cy]), n = pts.length;
                const edgeN = pts.map((p, i) => { const q = pts[(i + 1) % n], dx = q[0] - p[0], dy = q[1] - p[1], l = Math.hypot(dx, dy) || 1; return [dy / l, -dx / l]; });
                for (let i = 0; i < n; i++) {
                    const a = pts[i], b = pts[(i + 1) % n], en = edgeN[i];
                    // Curves get smooth normals; corners sharper than about 40 degrees stay crisp
                    const vn = (k, other) => {
                        const o = edgeN[other], dot = en[0] * o[0] + en[1] * o[1];
                        if (dot < 0.77) return en;
                        const s = [en[0] + o[0], en[1] + o[1]], l = Math.hypot(...s) || 1;
                        return [s[0] / l, s[1] / l];
                    };
                    const na = vn(i, (i - 1 + n) % n), nb = vn(i, (i + 1) % n);
                    const A1 = g.v(a[0], a[1], hz, na[0], na[1], 0, a[0], a[1]), A0 = g.v(a[0], a[1], -hz, na[0], na[1], 0, a[0], a[1]);
                    const B0 = g.v(b[0], b[1], -hz, nb[0], nb[1], 0, b[0], b[1]), B1 = g.v(b[0], b[1], hz, nb[0], nb[1], 0, b[0], b[1]);
                    g.quadN(A1, A0, B0, B1, [en[0], en[1], 0]);
                }
                // Fans for the stencil fill of the faces
                const f0 = f.v(pts[0][0], pts[0][1], 0, 0, 0, 1);
                for (let i = 1; i < n - 1; i++) f.tri(f0, f.v(pts[i][0], pts[i][1], 0, 0, 0, 1), f.v(pts[i + 1][0], pts[i + 1][1], 0, 0, 0, 1));
            }
            sides.set(g.data());
            fans.set(f.data());
            const hx = ext[0] / 2 + 0.05, hy = ext[1] / 2 + 0.05, c = G.geo();
            c.v(-hx, -hy, 0, 0, 0, 1, -hx, -hy); c.v(hx, -hy, 0, 0, 0, 1, hx, -hy); c.v(hx, hy, 0, 0, 0, 1, hx, hy); c.v(-hx, hy, 0, 0, 0, 1, -hx, hy);
            c.quad(0, 1, 2, 3);
            caps.set(c.data());
            // The space the text sweeps through as it turns decides the window's shape and how far back the eye sits
            const hw = ext[0] / 2, hh = ext[1] / 2, tanV = Math.tan(fovy * Math.PI / 360);
            if (cfg.rot === 0) {
                // Far enough back that long text isn't smeared by perspective
                const dist = Math.max(hz + hh * 1.08 / tanV, 1.6 * hw);
                view = { aspect: hw / hh, tanV: hh * 1.08 / (dist - hz), dist };
            }
            else if (cfg.rot === 1) {
                // Swinging round the vertical axis, the nearer end of the text grows: leave room for it
                const r = Math.hypot(hw, hz), dist = 2 * r, tv = hh * 1.08 / (dist - r);
                view = { aspect: hw * 1.08 / (dist - hz) / tv, tanV: tv, dist };
            } else {
                const r = Math.hypot(hw, hh, hz);
                view = { aspect: 1, tanV, dist: r / Math.sin(Math.atan(tanV)) };
            }
        };

        // Sinusoidal turning, each axis with its own swing and speed, re-picked as it passes through zero
        const baseStep = Math.round(1 + cfg.speed * 19 / 100);
        const range = cfg.rot === 3 ? Math.round(2 + Math.min(1, cfg.speed / 50) * 4) : cfg.rot === 2 ? 1 : 2;
        const lim = [[[0, 0], [0, 0], [0, 0]], [[0, 0], [63, 88], [0, 0]], [[0, 0], [40, 80], [55, 55]], [[45, 89], [45, 89], [45, 89]]][cfg.rot];
        const axes = lim.map(([lo, hi]) => ({ lo, hi, amp: rnd(lo, hi), step: Math.round(rnd(Math.max(1, baseStep - range), baseStep + range)), phase: 0 }));
        const turn = k => axes.forEach((a, i) => {
            if (!a.hi) return;
            const before = Math.floor(a.phase / 180);
            a.phase += a.step * k;
            if (Math.floor(a.phase / 180) !== before && !(cfg.rot === 2)) {
                a.amp = rnd(a.lo, a.hi);
                a.step = Math.round(rnd(Math.max(1, baseStep - range), baseStep + range));
            }
            if (cfg.rot === 2 && i === 2) a.phase = axes[1].phase;
        });
        const angle = i => {
            const a = axes[i];
            if (!a.hi) return 0;
            return cfg.rot === 2 && i === 2 ? a.amp * Math.cos(a.phase * Math.PI / 180) : a.amp * Math.sin(a.phase * Math.PI / 180);
        };

        // Every ten seconds the letters take on a new material
        let mat = tex ? G.randomWhite() : G.randomTea(), next = null, clock = 0;
        const CYCLE = 10;
        const blend = (a, b, t) => ({ ka: a.ka.map((v, i) => v + (b.ka[i] - v) * t), kd: a.kd.map((v, i) => v + (b.kd[i] - v) * t), ks: a.ks.map((v, i) => v + (b.ks[i] - v) * t), shin: a.shin + (b.shin - a.shin) * t });
        const floater = G.floater(env, 0.01);
        const timeText = () => new Date().toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' });

        return {
            frame(dt) {
                const text = cfg.clock ? timeText() : cfg.text || ' ';
                if (text !== shown) { build(text); shown = text; }
                const k = dt * RATE;
                turn(k);
                clock += dt;
                let m = mat;
                if (clock > CYCLE * 0.75) {
                    next = next || (tex ? G.randomWhite() : G.randomTea());
                    m = blend(mat, next, Math.min(1, (clock - CYCLE * 0.75) / (CYCLE * 0.25)));
                    if (clock >= CYCLE) { mat = next; next = null; clock = 0; m = mat; }
                }
                floater.fit((0.25 + 0.5 * cfg.size / 100) * (env.w + env.h) / 2, view.aspect);
                floater.step(dt * 30);
                gl.viewport(0, 0, env.w, env.h);
                gl.clearColor(0, 0, 0, 1);
                gl.clearStencil(0);
                gl.stencilMask(0xff);
                gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT | gl.STENCIL_BUFFER_BIT);
                floater.viewport(gl);
                // A window squeezed by the screen edge keeps the width in view by widening the vertical angle
                const winAspect = floater.w / floater.h;
                const tanV = view.aspect > winAspect ? view.tanV * view.aspect / winAspect : view.tanV;
                const proj = M.persp(2 * Math.atan(tanV) * 180 / Math.PI, winAspect, view.dist * 0.05, view.dist * 3);
                let mv = M.translate(M.id(), 0, 0, -view.dist);
                mv = M.rotate(M.rotate(M.rotate(mv, angle(2), 0, 0, 1), angle(1), 0, 1, 0), angle(0), 1, 0, 0);
                P.lights([{ pos: [0, 50, 150, 0], amb: [0.2, 0.2, 0.2], dif: [0.7, 0.7, 0.7], spc: [1, 1, 1] }, { pos: [25, 150, 50, 0], amb: [0.1, 0.1, 0.1], dif: [0.7, 0.7, 0.7], spc: [0, 0, 0] }], [1, 1, 1]);
                const look = { mat: m, tex };
                gl.enable(gl.DEPTH_TEST);
                gl.depthFunc(gl.LEQUAL);
                gl.enable(gl.CULL_FACE);
                gl.cullFace(gl.BACK);
                gl.frontFace(gl.CCW);
                P.set(look).matrices(proj, mv);
                sides.draw();
                // Front and back faces: count coverage in the stencil, then paint where it's odd
                gl.enable(gl.STENCIL_TEST);
                for (const zs of [1, -1]) {
                    // The back face is the front one mirrored through the text's middle, so it winds the other way
                    const face = M.scale(M.translate(mv, 0, 0, zs * depth / 2), 1, 1, zs);
                    gl.frontFace(zs > 0 ? gl.CCW : gl.CW);
                    gl.clear(gl.STENCIL_BUFFER_BIT);
                    gl.colorMask(false, false, false, false);
                    gl.depthMask(false);
                    gl.disable(gl.DEPTH_TEST);
                    gl.disable(gl.CULL_FACE);
                    gl.stencilFunc(gl.ALWAYS, 0, 1);
                    gl.stencilOp(gl.KEEP, gl.KEEP, gl.INVERT);
                    gl.stencilMask(1);
                    P.set(look).matrices(proj, face);
                    fans.draw();
                    gl.colorMask(true, true, true, true);
                    gl.depthMask(true);
                    gl.enable(gl.DEPTH_TEST);
                    gl.enable(gl.CULL_FACE);
                    gl.stencilFunc(gl.EQUAL, 1, 1);
                    gl.stencilOp(gl.KEEP, gl.KEEP, gl.KEEP);
                    P.set(look).matrices(proj, face);
                    caps.draw();
                }
                gl.disable(gl.STENCIL_TEST);
                gl.stencilMask(0xff);
                gl.frontFace(gl.CCW);
            },
            destroy() { sides.free(); fans.free(); caps.free(); P.free(); }
        };
    },
    setup(cfg, done) {
        const textRad = radio('ss-t3-demo', SaverUI.lbl('Te&xt'), !cfg.clock, 'text');
        const timeRad = radio('ss-t3-demo', SaverUI.lbl('Ti&me'), cfg.clock, 'time');
        const text = h('input', { class: 'field', type: 'text', value: cfg.text, maxlength: 16, 'aria-label': 'Text', spellcheck: 'false', style: { flex: '1', minWidth: '0' } });
        const solid = radio('ss-t3-surf', SaverUI.lbl('Solid &Color'), !cfg.textured, 's');
        const textured = radio('ss-t3-surf', SaverUI.lbl('T&extured'), cfg.textured, 't');
        const texBtn = button(SaverUI.lbl('&Texture...'), () => SaverUI.pickTexture(id => (cfg.texture = id)));
        const rots = h('select', { class: 'field', 'aria-label': 'Spin Style', onchange: e => (cfg.rot = +e.target.value) }, this.ROTS.map((l, i) => h('option', { value: i, selected: i === cfg.rot }, l)));
        const sync = () => { text.disabled = cfg.clock; texBtn.disabled = !cfg.textured; };
        textRad.querySelector('input').addEventListener('change', () => { cfg.clock = false; sync(); });
        timeRad.querySelector('input').addEventListener('change', () => { cfg.clock = true; sync(); });
        solid.querySelector('input').addEventListener('change', () => { cfg.textured = false; sync(); });
        textured.querySelector('input').addEventListener('change', () => { cfg.textured = true; sync(); });
        text.addEventListener('input', () => (cfg.text = text.value));
        sync();
        const slider = (key, label) => SaverUI.slider({ min: 0, max: 100, value: cfg[key], label, onchange: v => (cfg[key] = v) });
        SaverUI.setup({
            id: 'ss-setup', title: '3D Text Setup', width: 470,
            content: h('div', { class: 'ss-grid2' },
                SaverUI.group('Display', h('div', { class: 'ss-stack' }, h('div', { class: 'ss-line' }, textRad, text), timeRad)),
                SaverUI.group('Surface Style', h('div', { class: 'ss-stack' }, solid, h('div', { class: 'ss-line' }, textured, texBtn))),
                SaverUI.group('Si&ze', SaverUI.range('Small', 'Large', slider('size', 'Size'))),
                SaverUI.group('&Speed', SaverUI.range('Slow', 'Fast', slider('speed', 'Speed'))),
                SaverUI.group('&Resolution', SaverUI.range('Min', 'Max', slider('tessel', 'Resolution'))),
                SaverUI.group('Spin St&yle', h('div', { class: 'combo' }, rots))),
            extra: [['Choose &Font ...', () => SaverUI.font(cfg.font, {}, f => (cfg.font = { face: f.face, bold: f.bold, italic: f.italic }))]],
            onOk: () => done(cfg)
        });
    }
};

Savers.impl.blank.setup = () => msgBox({ title: 'Blank Screen', icon: 'info', text: 'This screen saver has no options that you can set.' });
