// Burst Stack Cam - native alignment, stacking, tone mapping and rendering.
#include <jni.h>
#include <cstring>
#include <new>
#include <exception>
#include "common.h"
#include "align.h"

using namespace bs;

namespace {

struct YuvFrame {
    Buf<uint8_t> y, u, v;
};

struct Ctx {
    int w = 0, h = 0, cw = 0, ch = 0;
    bool raw = false;
    int capacity = 0;
    std::vector<YuvFrame> yuv;
    std::vector<Buf<uint16_t>> rawFrames;
    // Stacked result (float32): luma full-res, chroma half-res.
    Buf<float> sy, su, sv;
    Buf<uint16_t> rawOut;
    float bp = 0.f, wp = 255.f, med = 100.f;
    // Low-res luma "base layer" for local tone mapping (iPhone-style shadow/highlight handling).
    Buf<float> base;
    int bw = 0, bh = 0;
    // Very soft glow layer (1/32 res) for bloom/halation and large light/dark masses.
    Buf<float> glow;
    int gw = 0, gh = 0;
    // Scene analysis: what the photo lacks decides what the look exaggerates.
    float sStd = 0.25f, sChroma = 0.14f, sWarm = 0.12f, sMass = 0.10f, sHi = 0.f;
    // Semantic scene understanding (soft region masks at 1/8 res): sky, foliage, earth/brown, skin, neutral/gray.
    Buf<float> semSky, semFol, semEarth, semSkin, semNeu;
    int sgw = 0, sgh = 0;
    float semMed = -1.f;           // luma median metered on non-sky, face-weighted cells (exposure target)
    float castU = 0.f, castV = 0.f;  // mild white-balance correction anchored on neutral surfaces
};

constexpr int kBaseScale = 16;
constexpr int kGlowScale = 32;

struct Progress {
    JNIEnv* env = nullptr;
    jobject obj = nullptr;
    jmethodID mid = nullptr;
    Progress(JNIEnv* e, jobject o) : env(e), obj(o) {
        if (o) {
            jclass c = e->FindClass("com/burststack/cam/ProgressListener");
            if (c) {
                mid = e->GetMethodID(c, "onProgress", "(F)V");
                e->DeleteLocalRef(c);
            }
            if (e->ExceptionCheck()) { e->ExceptionClear(); mid = nullptr; }
        }
    }
    void report(float f) {
        if (obj && mid) env->CallVoidMethod(obj, mid, (jfloat)f);
    }
};

// ---------------------------------------------------------------- YUV stacking

void stackYuv(Ctx& c, int ref, const std::vector<Flow>& flows, Progress& pg, float p0, float p1) {
    const int w = c.w, h = c.h, cw = c.cw, ch = c.ch;
    const int n = (int)c.yuv.size();
    c.sy = alloc<float>((size_t)w * h);
    c.su = alloc<float>((size_t)cw * ch);
    c.sv = alloc<float>((size_t)cw * ch);

    int gw = 0;
    for (int f = 0; f < n; f++) if (f != ref) { gw = flows[f].gw; break; }
    const uint8_t* refY = c.yuv[ref].y.get();
    const uint8_t* refU = c.yuv[ref].u.get();
    const uint8_t* refV = c.yuv[ref].v.get();

    if (gw == 0) {  // single frame
        for (size_t i = 0; i < (size_t)w * h; i++) c.sy[i] = (float)refY[i];
        for (size_t i = 0; i < (size_t)cw * ch; i++) { c.su[i] = (float)refU[i]; c.sv[i] = (float)refV[i]; }
        return;
    }

    std::vector<int> xt0(w), xt1(w);
    std::vector<float> xfr(w);
    for (int x = 0; x < w; x++) {
        float g = clampf(((float)x - 7.5f) / (float)TS, 0.f, (float)(gw - 1));
        int t0 = (int)g;
        xt0[x] = t0;
        xt1[x] = std::min(t0 + 1, gw - 1);
        xfr[x] = g - (float)t0;
    }

    const int bands = 8;
    for (int band = 0; band < bands; band++) {
        const int ya = (int)((int64_t)h * band / bands);
        const int yb = (int)((int64_t)h * (band + 1) / bands);
        const int chunks = (yb - ya + 3) / 4;
        parallelFor(chunks, [&](int ck) {
            const int r0 = ya + ck * 4, r1 = std::min(yb, r0 + 4);
            std::vector<float> accY(w), ws(w), rvx(gw), rvy(gw), rw(gw), accU(cw), accV(cw), wsc(cw);
            for (int y = r0; y < r1; y++) {
                const uint8_t* rr = refY + (size_t)y * w;
                for (int x = 0; x < w; x++) { accY[x] = (float)rr[x]; ws[x] = 1.f; }
                const bool chroma = ((y & 1) == 0) && ((y >> 1) < ch);
                if (chroma) {
                    const uint8_t* ru = refU + (size_t)(y >> 1) * cw;
                    const uint8_t* rv = refV + (size_t)(y >> 1) * cw;
                    for (int x = 0; x < cw; x++) { accU[x] = (float)ru[x]; accV[x] = (float)rv[x]; wsc[x] = 1.f; }
                }
                for (int f = 0; f < n; f++) {
                    if (f == ref) continue;
                    const Flow& fl = flows[f];
                    const float gy = clampf(((float)y - 7.5f) / (float)TS, 0.f, (float)(fl.gh - 1));
                    const int t0 = (int)gy;
                    const int t1 = std::min(t0 + 1, fl.gh - 1);
                    const float fy = gy - (float)t0;
                    const float* vx0 = &fl.vx[(size_t)t0 * gw];
                    const float* vx1 = &fl.vx[(size_t)t1 * gw];
                    const float* vy0 = &fl.vy[(size_t)t0 * gw];
                    const float* vy1 = &fl.vy[(size_t)t1 * gw];
                    const float* w0p = &fl.wt[(size_t)t0 * gw];
                    const float* w1p = &fl.wt[(size_t)t1 * gw];
                    for (int t = 0; t < gw; t++) {
                        rvx[t] = vx0[t] + (vx1[t] - vx0[t]) * fy;
                        rvy[t] = vy0[t] + (vy1[t] - vy0[t]) * fy;
                        rw[t] = w0p[t] + (w1p[t] - w0p[t]) * fy;
                    }
                    const uint8_t* fY = c.yuv[f].y.get();
                    const uint8_t* fU = c.yuv[f].u.get();
                    const uint8_t* fV = c.yuv[f].v.get();
                    const float sig = fl.sigma;
                    for (int x = 0; x < w; x++) {
                        const int a = xt0[x], b = xt1[x];
                        const float fx = xfr[x];
                        const float wt = rw[a] + (rw[b] - rw[a]) * fx;
                        if (wt <= 0.002f) continue;
                        const float u = rvx[a] + (rvx[b] - rvx[a]) * fx;
                        const float v = rvy[a] + (rvy[b] - rvy[a]) * fx;
                        const float val = sampleU8(fY, w, h, (float)x + u, (float)y + v);
                        const float d = std::fabs(val - (float)rr[x]);
                        const float pf = clampf((4.f * sig - d) / (2.f * sig), 0.f, 1.f);
                        const float wg = wt * pf;
                        if (wg <= 0.f) continue;
                        accY[x] += wg * val;
                        ws[x] += wg;
                        if (chroma && (x & 1) == 0) {
                            const int cx = x >> 1;
                            if (cx < cw) {
                                const float cu = sampleU8(fU, cw, ch, (float)cx + 0.5f * u, (float)(y >> 1) + 0.5f * v);
                                const float cv = sampleU8(fV, cw, ch, (float)cx + 0.5f * u, (float)(y >> 1) + 0.5f * v);
                                accU[cx] += wg * cu;
                                accV[cx] += wg * cv;
                                wsc[cx] += wg;
                            }
                        }
                    }
                }
                float* o = c.sy.get() + (size_t)y * w;
                for (int x = 0; x < w; x++) o[x] = accY[x] / ws[x];
                if (chroma) {
                    float* ou = c.su.get() + (size_t)(y >> 1) * cw;
                    float* ov = c.sv.get() + (size_t)(y >> 1) * cw;
                    for (int x = 0; x < cw; x++) { ou[x] = accU[x] / wsc[x]; ov[x] = accV[x] / wsc[x]; }
                }
            }
        });
        pg.report(p0 + (p1 - p0) * (float)(band + 1) / (float)bands);
    }
}

// Black/white point from the stacked luma histogram (used by the light tone mapping).
void computeTone(Ctx& c) {
    std::vector<uint32_t> hist(1024, 0);
    const float* Y = c.sy.get();
    uint64_t total = 0;
    for (int y = 0; y < c.h; y += 2) {
        const float* r = Y + (size_t)y * c.w;
        for (int x = 0; x < c.w; x += 2) {
            int b = clampi((int)(r[x] * 4.f), 0, 1023);
            hist[b]++;
            total++;
        }
    }
    if (total == 0) { c.bp = 0.f; c.wp = 255.f; c.med = 100.f; return; }
    const uint64_t lo = (uint64_t)((double)total * 0.001);
    const uint64_t hi = (uint64_t)((double)total * 0.999);
    uint64_t acc = 0;
    int bLo = 0, bHi = 1023;
    bool gotLo = false;
    for (int i = 0; i < 1024; i++) {
        acc += hist[i];
        if (!gotLo && acc > lo) { bLo = i; gotLo = true; }
        if (acc >= hi) { bHi = i; break; }
    }
    float bp = (float)bLo / 4.f;
    float wp = (float)(bHi + 1) / 4.f;
    bp = std::min(bp, 24.f);
    wp = std::max(wp, 200.f);
    if (wp - bp < 100.f) wp = bp + 100.f;
    c.bp = bp;
    c.wp = std::min(wp, 255.f);
    {
        const uint64_t half = total / 2;
        uint64_t a = 0;
        int bm = 0;
        for (int i = 0; i < 1024; i++) { a += hist[i]; if (a >= half) { bm = i; break; } }
        c.med = ((float)bm + 0.5f) / 4.f;
    }
}

void blur3(float* d, int w, int h, int passes) {
    std::vector<float> tmp((size_t)w * h);
    for (int pass = 0; pass < passes; pass++) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0.f;
                for (int dy = -1; dy <= 1; dy++) {
                    const int yy = clampi(y + dy, 0, h - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        const int xx = clampi(x + dx, 0, w - 1);
                        s += d[(size_t)yy * w + xx];
                    }
                }
                tmp[(size_t)y * w + x] = s * (1.f / 9.f);
            }
        }
        std::copy(tmp.begin(), tmp.end(), d);
    }
}

// Box-downsamples stacked luma by S (sampling every 2nd pixel) into out[(w+S-1)/S x (h+S-1)/S].
void downLuma(const Ctx& c, int S, Buf<float>& out, int& ow, int& oh) {
    ow = (c.w + S - 1) / S;
    oh = (c.h + S - 1) / S;
    const size_t n = (size_t)ow * oh;
    out = alloc<float>(n);
    std::vector<float> acc(n, 0.f), cnt(n, 0.f);
    const float* Y = c.sy.get();
    for (int y = 0; y < c.h; y += 2) {
        const float* r = Y + (size_t)y * c.w;
        float* a = acc.data() + (size_t)(y / S) * ow;
        float* k = cnt.data() + (size_t)(y / S) * ow;
        for (int x = 0; x < c.w; x += 2) { a[x / S] += r[x]; k[x / S] += 1.f; }
    }
    for (size_t i = 0; i < n; i++) out[i] = cnt[i] > 0.f ? acc[i] / cnt[i] : 128.f;
}

// Base (1/16) and glow (1/32) luma layers used by the look.
void computeBase(Ctx& c) {
    downLuma(c, kBaseScale, c.base, c.bw, c.bh);
    blur3(c.base.get(), c.bw, c.bh, 4);
    // glow: 2x2 average of the base layer, then blurred more (≈1/32 res, wide soft radius)
    c.gw = (c.bw + 1) / 2;
    c.gh = (c.bh + 1) / 2;
    c.glow = alloc<float>((size_t)c.gw * c.gh);
    for (int y = 0; y < c.gh; y++) {
        for (int x = 0; x < c.gw; x++) {
            float s = 0.f;
            for (int dy = 0; dy < 2; dy++)
                for (int dx = 0; dx < 2; dx++)
                    s += c.base[(size_t)std::min(y * 2 + dy, c.bh - 1) * c.bw + std::min(x * 2 + dx, c.bw - 1)];
            c.glow[(size_t)y * c.gw + x] = s * 0.25f;
        }
    }
    blur3(c.glow.get(), c.gw, c.gh, 3);
}

// Measures the stacked scene: tonal spread, color richness, warmth, large-scale light structure.
void computeScene(Ctx& c) {
    const float* Y = c.sy.get();
    double sum = 0, sum2 = 0;
    uint64_t n = 0, hi = 0;
    for (int y = 0; y < c.h; y += 4) {
        const float* r = Y + (size_t)y * c.w;
        for (int x = 0; x < c.w; x += 4) {
            const float v = r[x] * (1.f / 255.f);
            sum += v; sum2 += (double)v * v; n++;
            if (v > 0.85f) hi++;
        }
    }
    if (n == 0) return;
    const double m = sum / (double)n;
    c.sStd = (float)std::sqrt(std::max(0.0, sum2 / (double)n - m * m));
    c.sHi = (float)((double)hi / (double)n);
    double ch = 0, wm = 0;
    uint64_t k = 0;
    for (int y = 0; y < c.ch; y += 2) {
        for (int x = 0; x < c.cw; x += 2) {
            const float u = c.su[(size_t)y * c.cw + x] - 128.f, v = c.sv[(size_t)y * c.cw + x] - 128.f;
            ch += std::sqrt(u * u + v * v);
            wm += v - u;
            k++;
        }
    }
    if (k > 0) {
        c.sChroma = (float)(ch / (double)k) / 128.f;
        c.sWarm = (float)(wm / (double)k) / 128.f;
    }
    double gs = 0, gs2 = 0;
    const size_t gn = (size_t)c.gw * c.gh;
    for (size_t i = 0; i < gn; i++) {
        const double v = c.glow[i] / 255.0;
        gs += v; gs2 += v * v;
    }
    if (gn > 0) {
        const double gm = gs / (double)gn;
        c.sMass = (float)std::sqrt(std::max(0.0, gs2 / (double)gn - gm * gm));
    }
}

// ---------------------------------------------------------------- Semantic scene understanding
// Classical (no ML model) soft segmentation on a 1/8 grid. Every cell gets a membership in [0,1] for
// sky / foliage / earth(brown) / skin / neutral(gray) from hue, chroma, brightness and texture, then the
// masks are smoothed so regions are spatially coherent. The renderer tunes light and color per region.
constexpr int kSemScale = 8;

inline float semStep(float e0, float e1, float x) {
    const float t = clampf((x - e0) / (e1 - e0), 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}
inline float angDiff(float a, float b) { return std::fmod(a - b + 540.f, 360.f) - 180.f; }  // a-b wrapped to [-180,180)
inline float hueBump(float th, float center, float halfWidth) {
    const float d = std::fabs(angDiff(th, center)) / halfWidth;
    return d >= 1.f ? 0.f : 1.f - d * d;
}

void computeSemantic(Ctx& c) {
    const int S = kSemScale;
    const int gw = (c.w + S - 1) / S, gh = (c.h + S - 1) / S;
    if (gw < 2 || gh < 2) return;
    const size_t n = (size_t)gw * gh;
    std::vector<float> sky(n), fol(n), ear(n), skn(n), neu(n), blue(n), yn(n), cuG(n), cvG(n), smo(n);
    const float* Y = c.sy.get();
    parallelFor(gh, [&](int gy) {
        for (int gx = 0; gx < gw; gx++) {
            double s = 0, s2 = 0, su = 0, sv = 0;
            int k = 0;
            const int y0 = gy * S, y1 = std::min(y0 + S, c.h), x0 = gx * S, x1 = std::min(x0 + S, c.w);
            for (int y = y0; y < y1; y += 2) {
                for (int x = x0; x < x1; x += 2) {
                    const float v = Y[(size_t)y * c.w + x];
                    s += v; s2 += (double)v * v;
                    const size_t ci = (size_t)std::min(y / 2, c.ch - 1) * c.cw + std::min(x / 2, c.cw - 1);
                    su += c.su[ci] - 128.f; sv += c.sv[ci] - 128.f;
                    k++;
                }
            }
            const size_t i = (size_t)gy * gw + gx;
            if (k == 0) continue;
            const float m = (float)(s / k), u = (float)(su / k), v = (float)(sv / k);
            const float sd = (float)std::sqrt(std::max(0.0, s2 / k - (double)m * m));
            const float Yn = m * (1.f / 255.f);
            const float mag = std::sqrt(u * u + v * v);
            const float th = std::atan2(v, u) * 57.29578f;
            const float smooth = 1.f - semStep(2.5f, 10.f, sd);   // flat (sky, walls) vs textured (leaves)
            const float tex = semStep(3.f, 12.f, sd);
            yn[i] = Yn; cuG[i] = u; cvG[i] = v; smo[i] = smooth;
            // Blue sky: blue/cyan hue, some chroma, not too dark.
            blue[i] = hueBump(th, -35.f, 55.f) * semStep(5.f, 16.f, mag) * semStep(0.18f, 0.35f, Yn) * (0.35f + 0.65f * smooth);
            // Foliage: green .. yellow-green hue.
            fol[i] = hueBump(th, -135.f, 52.f) * semStep(6.f, 14.f, mag) * (0.7f + 0.3f * tex);
            // Earth / brown / ochre: warm hue, moderate chroma, mid-dark.
            ear[i] = hueBump(th, 140.f, 36.f) * semStep(6.f, 12.f, mag) * (1.f - semStep(40.f, 60.f, mag)) * (1.f - semStep(0.45f, 0.70f, Yn));
            // Skin: narrow orange-pink hue, soft chroma, mid-bright.
            skn[i] = hueBump(th, 122.f, 16.f) * semStep(10.f, 16.f, mag) * (1.f - semStep(34.f, 48.f, mag)) *
                     semStep(0.28f, 0.42f, Yn) * (1.f - semStep(0.88f, 0.97f, Yn));
            // Neutral gray (concrete, asphalt, clouds before context check).
            neu[i] = (1.f - semStep(4.f, 10.f, mag)) * semStep(0.06f, 0.14f, Yn) * (1.f - semStep(0.94f, 0.99f, Yn));
        }
    });
    // Clouds are bright, flat and colorless, so they only count as sky when connected to blue sky: the sky
    // membership propagates from blue cells through cloud-like cells (geodesic sweeps, a few rounds).
    std::vector<float> cand(n), prop(blue);
    for (size_t i = 0; i < n; i++) {
        cand[i] = semStep(0.45f, 0.72f, yn[i]) * (1.f - semStep(4.f, 12.f, std::sqrt(cuG[i] * cuG[i] + cvG[i] * cvG[i]))) *
                  (0.3f + 0.7f * smo[i]);
    }
    auto relax = [&](int x, int y) {
        const size_t i = (size_t)y * gw + x;
        float nb = 0.f;
        if (x > 0) nb = std::max(nb, prop[i - 1]);
        if (x + 1 < gw) nb = std::max(nb, prop[i + 1]);
        if (y > 0) nb = std::max(nb, prop[i - gw]);
        if (y + 1 < gh) nb = std::max(nb, prop[i + gw]);
        prop[i] = std::max(prop[i], cand[i] * nb);
    };
    for (int round = 0; round < 6; round++) {
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) relax(x, y);
        for (int y = gh - 1; y >= 0; y--) for (int x = gw - 1; x >= 0; x--) relax(x, y);
    }
    for (size_t i = 0; i < n; i++) {
        sky[i] = std::max(blue[i], prop[i]);
        neu[i] *= 1.f - sky[i];
        // Competition: if the total exceeds 1, scale down so classes never stack up.
        const float tot = sky[i] + fol[i] + ear[i] + skn[i] + neu[i];
        if (tot > 1.f) {
            const float inv = 1.f / tot;
            sky[i] *= inv; fol[i] *= inv; ear[i] *= inv; skn[i] *= inv; neu[i] *= inv;
        }
    }
    blur3(sky.data(), gw, gh, 1); blur3(fol.data(), gw, gh, 1); blur3(ear.data(), gw, gh, 1);
    blur3(skn.data(), gw, gh, 1); blur3(neu.data(), gw, gh, 1);

    auto store = [&](Buf<float>& dst, const std::vector<float>& src) {
        dst = alloc<float>(n);
        std::copy(src.begin(), src.end(), dst.get());
    };
    store(c.semSky, sky); store(c.semFol, fol); store(c.semEarth, ear); store(c.semSkin, skn); store(c.semNeu, neu);
    c.sgw = gw; c.sgh = gh;

    // Exposure metering: ignore the bright sky, favor faces (what a phone meters on).
    {
        std::vector<double> hist(256, 0.0);
        double tot = 0;
        for (size_t i = 0; i < n; i++) {
            const double wgt = (1.0 - 0.85 * sky[i]) * (1.0 + 1.0 * skn[i]);
            hist[clampi((int)(yn[i] * 255.f), 0, 255)] += wgt;
            tot += wgt;
        }
        if (tot > 1e-6) {
            double a = 0;
            for (int i = 0; i < 256; i++) { a += hist[i]; if (a >= tot * 0.5) { c.semMed = (float)i + 0.5f; break; } }
        }
    }
    // Neutral-anchored white balance: if real gray surfaces lean in one direction, remove half of it (capped).
    {
        double wsum = 0, su = 0, sv = 0;
        for (size_t i = 0; i < n; i++) {
            const float mg = std::sqrt(cuG[i] * cuG[i] + cvG[i] * cvG[i]);
            if (mg > 10.f || yn[i] < 0.25f || yn[i] > 0.85f) continue;
            const double wgt = (double)neu[i] * (0.4 + 0.6 * smo[i]);
            wsum += wgt; su += wgt * cuG[i]; sv += wgt * cvG[i];
        }
        if (wsum > 0.03 * (double)n) {
            c.castU = clampf((float)(su / wsum), -10.f, 10.f);   // raw measured cast; each look scales it
            c.castV = clampf((float)(sv / wsum), -10.f, 10.f);
        }
    }
}

struct Sem { float sky = 0.f, fol = 0.f, earth = 0.f, skin = 0.f, neu = 0.f; };

inline Sem sampleSem(const Ctx& c, int sx, int sy) {
    Sem m;
    if (!c.semSky) return m;
    const float gx = ((float)sx + 0.5f) / (float)kSemScale - 0.5f;
    const float gy = ((float)sy + 0.5f) / (float)kSemScale - 0.5f;
    m.sky = sampleF(c.semSky.get(), c.sgw, c.sgh, gx, gy);
    m.fol = sampleF(c.semFol.get(), c.sgw, c.sgh, gx, gy);
    m.earth = sampleF(c.semEarth.get(), c.sgw, c.sgh, gx, gy);
    m.skin = sampleF(c.semSkin.get(), c.sgw, c.sgh, gx, gy);
    m.neu = sampleF(c.semNeu.get(), c.sgw, c.sgh, gx, gy);
    return m;
}

int stackYuvImpl(JNIEnv* env, Ctx& c, bool doAlign, jobject listener) {
    Progress pg(env, listener);
    const int n = (int)c.yuv.size();
    if (n < 1) return -1;
    pg.report(0.f);
    int ref = 0;
    std::vector<Flow> flows(n);
    {
        std::vector<Pyramid> pyr(n);
        for (int i = 0; i < n; i++) {
            buildPyramid(c.yuv[i].y.get(), c.w, c.h, pyr[i]);
            pg.report(0.08f * (float)(i + 1) / (float)n);
        }
        double best = -1.0;
        for (int i = 0; i < n; i++) {
            const Plane& P = pyr[i].lv[pyr[i].lv.size() > 1 ? 1 : 0];
            double s = lapVar(P);
            if (s > best) { best = s; ref = i; }
        }
        pg.report(0.1f);
        const int others = n - 1;
        int done = 0;
        for (int i = 0; i < n; i++) {
            if (i == ref) continue;
            alignFrame(pyr[ref], pyr[i], doAlign, flows[i]);
            computeWeights(flows[i]);
            done++;
            pg.report(0.1f + 0.5f * (float)done / (float)others);
        }
    }
    stackYuv(c, ref, flows, pg, 0.6f, 0.95f);
    c.yuv.clear();
    c.yuv.shrink_to_fit();
    computeTone(c);
    computeBase(c);
    computeScene(c);
    computeSemantic(c);
    pg.report(1.f);
    return ref;
}

// ---------------------------------------------------------------- RAW stacking

int stackRawImpl(JNIEnv* env, Ctx& c, bool doAlign, int black, int white, jobject listener) {
    Progress pg(env, listener);
    const int n = (int)c.rawFrames.size();
    if (n < 1) return -1;
    const int w = c.w, h = c.h, qw = w / 2, qh = h / 2;
    pg.report(0.f);

    // Quad-binned (2x2) grayscale per frame, sqrt-encoded to 8 bit, used only for alignment.
    std::vector<Plane> gray(n);
    const float scale = 1.f / (4.f * (float)std::max(1, white - black));
    for (int i = 0; i < n; i++) {
        Plane& g = gray[i];
        g.w = qw;
        g.h = qh;
        g.own = alloc<uint8_t>((size_t)qw * qh);
        g.p = g.own.get();
        const uint16_t* fr = c.rawFrames[i].get();
        uint8_t* go = g.own.get();
        parallelFor((qh + 15) / 16, [&](int ck) {
            const int ya = ck * 16, yb = std::min(qh, ya + 16);
            for (int qy = ya; qy < yb; qy++) {
                const uint16_t* r0 = fr + (size_t)(2 * qy) * w;
                const uint16_t* r1 = r0 + w;
                uint8_t* o = go + (size_t)qy * qw;
                for (int qx = 0; qx < qw; qx++) {
                    float s = (float)((int)r0[2 * qx] + (int)r0[2 * qx + 1] + (int)r1[2 * qx] + (int)r1[2 * qx + 1] - 4 * black);
                    float nr = clampf(s * scale, 0.f, 1.f);
                    o[qx] = (uint8_t)(std::sqrt(nr) * 255.f + 0.5f);
                }
            }
        });
        pg.report(0.08f * (float)(i + 1) / (float)n);
    }

    int ref = 0;
    std::vector<Flow> flows(n);
    {
        std::vector<Pyramid> pyr(n);
        for (int i = 0; i < n; i++) buildPyramid(gray[i].p, qw, qh, pyr[i]);
        double best = -1.0;
        for (int i = 0; i < n; i++) {
            double s = lapVar(pyr[i].lv[0]);
            if (s > best) { best = s; ref = i; }
        }
        pg.report(0.1f);
        const int others = n - 1;
        int done = 0;
        for (int i = 0; i < n; i++) {
            if (i == ref) continue;
            alignFrame(pyr[ref], pyr[i], doAlign, flows[i]);
            computeWeights(flows[i]);
            done++;
            pg.report(0.1f + 0.5f * (float)done / (float)others);
        }
    }

    Buf<uint16_t> out = alloc<uint16_t>((size_t)w * h);
    const uint16_t* refRaw = c.rawFrames[ref].get();
    int gw = 0;
    for (int f = 0; f < n; f++) if (f != ref) { gw = flows[f].gw; break; }

    if (gw == 0) {
        std::memcpy(out.get(), refRaw, (size_t)w * h * sizeof(uint16_t));
    } else {
        std::vector<int> xt0(qw), xt1(qw);
        std::vector<float> xfr(qw);
        for (int x = 0; x < qw; x++) {
            float g = clampf(((float)x - 7.5f) / (float)TS, 0.f, (float)(gw - 1));
            int t0 = (int)g;
            xt0[x] = t0;
            xt1[x] = std::min(t0 + 1, gw - 1);
            xfr[x] = g - (float)t0;
        }
        const uint8_t* refG = gray[ref].p;
        const int bands = 8;
        for (int band = 0; band < bands; band++) {
            const int ya = (int)((int64_t)qh * band / bands);
            const int yb = (int)((int64_t)qh * (band + 1) / bands);
            const int chunks = (yb - ya + 1) / 2;
            parallelFor(chunks, [&](int ck) {
                const int r0 = ya + ck * 2, r1 = std::min(yb, r0 + 2);
                std::vector<float> a0(w), a1(w), ws(qw), rvx(gw), rvy(gw), rw(gw);
                for (int qy = r0; qy < r1; qy++) {
                    const uint16_t* s0 = refRaw + (size_t)(2 * qy) * w;
                    const uint16_t* s1 = s0 + w;
                    for (int x = 0; x < w; x++) { a0[x] = (float)s0[x]; a1[x] = (float)s1[x]; }
                    for (int x = 0; x < qw; x++) ws[x] = 1.f;
                    for (int f = 0; f < n; f++) {
                        if (f == ref) continue;
                        const Flow& fl = flows[f];
                        const float gy = clampf(((float)qy - 7.5f) / (float)TS, 0.f, (float)(fl.gh - 1));
                        const int t0 = (int)gy;
                        const int t1 = std::min(t0 + 1, fl.gh - 1);
                        const float fy = gy - (float)t0;
                        for (int t = 0; t < gw; t++) {
                            rvx[t] = fl.vx[(size_t)t0 * gw + t] + (fl.vx[(size_t)t1 * gw + t] - fl.vx[(size_t)t0 * gw + t]) * fy;
                            rvy[t] = fl.vy[(size_t)t0 * gw + t] + (fl.vy[(size_t)t1 * gw + t] - fl.vy[(size_t)t0 * gw + t]) * fy;
                            rw[t] = fl.wt[(size_t)t0 * gw + t] + (fl.wt[(size_t)t1 * gw + t] - fl.wt[(size_t)t0 * gw + t]) * fy;
                        }
                        const uint16_t* fr = c.rawFrames[f].get();
                        const uint8_t* fg = gray[f].p;
                        const float sig = fl.sigma;
                        for (int qx = 0; qx < qw; qx++) {
                            const int a = xt0[qx], b = xt1[qx];
                            const float fx = xfr[qx];
                            const float wt = rw[a] + (rw[b] - rw[a]) * fx;
                            if (wt <= 0.002f) continue;
                            const float u = rvx[a] + (rvx[b] - rvx[a]) * fx;
                            const float v = rvy[a] + (rvy[b] - rvy[a]) * fx;
                            // Integer quad shifts only: keeps the Bayer pattern intact.
                            const int sx = qx + (int)lroundf(u);
                            const int sy = qy + (int)lroundf(v);
                            if (sx < 0 || sx >= qw || sy < 0 || sy >= qh) continue;
                            const float d = std::fabs((float)fg[(size_t)sy * qw + sx] - (float)refG[(size_t)qy * qw + qx]);
                            const float pf = clampf((4.f * sig - d) / (2.f * sig), 0.f, 1.f);
                            const float wg = wt * pf;
                            if (wg <= 0.f) continue;
                            const uint16_t* p0 = fr + (size_t)(2 * sy) * w + 2 * sx;
                            const uint16_t* p1 = p0 + w;
                            a0[2 * qx] += wg * (float)p0[0];
                            a0[2 * qx + 1] += wg * (float)p0[1];
                            a1[2 * qx] += wg * (float)p1[0];
                            a1[2 * qx + 1] += wg * (float)p1[1];
                            ws[qx] += wg;
                        }
                    }
                    uint16_t* o0 = out.get() + (size_t)(2 * qy) * w;
                    uint16_t* o1 = o0 + w;
                    for (int qx = 0; qx < qw; qx++) {
                        const float inv = 1.f / ws[qx];
                        o0[2 * qx] = (uint16_t)clampf(a0[2 * qx] * inv + 0.5f, 0.f, 65535.f);
                        o0[2 * qx + 1] = (uint16_t)clampf(a0[2 * qx + 1] * inv + 0.5f, 0.f, 65535.f);
                        o1[2 * qx] = (uint16_t)clampf(a1[2 * qx] * inv + 0.5f, 0.f, 65535.f);
                        o1[2 * qx + 1] = (uint16_t)clampf(a1[2 * qx + 1] * inv + 0.5f, 0.f, 65535.f);
                    }
                }
            });
            pg.report(0.6f + 0.39f * (float)(band + 1) / (float)bands);
        }
    }
    c.rawFrames.clear();
    c.rawFrames.shrink_to_fit();
    c.rawOut = std::move(out);
    pg.report(1.f);
    return ref;
}

// ---------------------------------------------------------------- Rendering

inline void mapCoord(int rot, int w, int h, int ox, int oy, int& sx, int& sy) {
    switch (rot) {
        case 90: sx = oy; sy = h - 1 - ox; break;
        case 180: sx = w - 1 - ox; sy = h - 1 - oy; break;
        case 270: sx = w - 1 - oy; sy = ox; break;
        default: sx = ox; sy = oy; break;
    }
}

// One look, always on: "iPhone-style" (Smart HDR / Standard). Natural color, honest white balance, bright but
// believable shadows, protected highlights, clean skies, gentle clarity. Nothing is exaggerated: no halo, bloom,
// vignette, fade, grain or heavy saturation. How strong each step is depends on what the scene needs.
struct Look {
    float sh = 0, hl = 0, cl = 0, con = 0, vib = 0, sat = 1, warm = 0, gamma = 1.f;
    float sharp = 0.5f;
    float castK = 0.7f, castMax = 6.f;   // white-balance correction strength / cap (from neutral surfaces)
    float bpK = 0.7f;                    // black-point stretch (1 = full)
    float chromaCap0 = 40.f, chromaCap = 64.f;  // soft gamut limit (8-bit chroma units)
};

Look makeLook(const Ctx& c, int /*unused: single look*/) {
    auto lack = [](float v, float ideal) { return clampf((ideal - v) / ideal, 0.f, 1.f); };
    const float dCon = lack(c.sStd, 0.26f);     // flat scene -> a bit more contrast
    const float dCol = lack(c.sChroma, 0.14f);  // dull scene -> a bit more color
    Look k;
    k.sh = .32f + .08f * dCon;       // shadow lift (Smart HDR: faces/ground stay readable)
    k.hl = .34f;                     // highlight protection (sky/clouds keep shape)
    k.cl = .07f + .05f * dCon;       // very light local contrast (iPhone is smooth, not crunchy)
    k.con = .24f + .12f * dCon;      // soft S-curve
    k.sat = 1.07f + .06f * dCol;
    k.vib = .32f + .25f * dCol;
    k.warm = .8f;                    // faint warmth, like Apple's default tone
    k.sharp = .45f;
    k.castK = .7f; k.castMax = 6.f;
    k.bpK = .55f;
    float med = c.semMed > 0.f ? c.semMed : c.med;   // meter on the subject, not the bright sky
    const float target = 0.50f;
    const float range = std::max(c.wp - c.bp, 1.f);
    const float tm = clampf((med - c.bp) / range, 0.f, 1.f);
    float g = 1.f;
    if (tm > 0.01f && tm < target) g = clampf(std::log(target) / std::log(tm), 0.70f, 1.f);                 // brighten dark scenes
    else if (tm > target + 0.14f && tm < 0.99f) g = clampf(std::log(target + 0.08f) / std::log(tm), 1.f, 1.15f);  // tame over-exposure
    k.gamma = g;
    return k;
}

inline float smooth01(float e0, float e1, float x) {
    const float t = clampf((x - e0) / (e1 - e0), 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}

// Stacked float YUV -> display-referred RGB in [0,1]: gentle unsharp mask, auto-exposure, local tone mapping
// (shadow lift + highlight roll-off), soft S-curve, natural color with a soft gamut limit and per-region touches.
inline void renderPixel(const Ctx& c, const Look& k, int sx, int sy, bool tone, bool sharp, float& r, float& g, float& b) {
    const int w = c.w, h = c.h;
    const float* Y = c.sy.get();
    const float yc = Y[(size_t)sy * w + sx];
    const float cxf = (float)sx * 0.5f - 0.25f, cyf = (float)sy * 0.5f - 0.25f;
    float cu = sampleF(c.su.get(), c.cw, c.ch, cxf, cyf) - 128.f;
    float cv = sampleF(c.sv.get(), c.cw, c.ch, cxf, cyf) - 128.f;

    const bool sem = tone && c.semSky;
    const Sem m = sem ? sampleSem(c, sx, sy) : Sem();
    const float mag0 = std::sqrt(cu * cu + cv * cv);
    const float skyBlue = m.sky * smooth01(8.f, 20.f, mag0);

    float yv = yc;
    if (sharp || (sem && skyBlue > 0.f)) {
        const int xm = sx > 0 ? sx - 1 : 0, xp = sx + 1 < w ? sx + 1 : w - 1;
        const int ym = sy > 0 ? sy - 1 : 0, yp = sy + 1 < h ? sy + 1 : h - 1;
        const float blur = (4.f * yc +
                            2.f * (Y[(size_t)ym * w + sx] + Y[(size_t)yp * w + sx] + Y[(size_t)sy * w + xm] + Y[(size_t)sy * w + xp]) +
                            (Y[(size_t)ym * w + xm] + Y[(size_t)ym * w + xp] + Y[(size_t)yp * w + xm] + Y[(size_t)yp * w + xp])) / 16.f;
        if (sharp) {
            const float d = yc - blur;
            const float gain = (d * d) / (d * d + 6.f);   // coring: noise is not sharpened
            float amt = k.sharp;
            if (sem) amt *= clampf(1.f + 0.2f * m.fol + 0.2f * m.earth - 0.6f * m.skin - skyBlue, 0.f, 1.3f);
            yv = yc + amt * d * gain;
        }
        if (sem && skyBlue > 0.f) yv += (blur - yv) * 0.5f * skyBlue;   // clean sky
    }

    float t = yv / 255.f;
    const float t0 = t;
    if (tone) {
        float shK = k.sh, hlK = k.hl, clK = k.cl;
        if (sem) {
            shK *= 1.f + 0.25f * m.fol + 0.15f * m.earth - 0.4f * m.sky;
            hlK *= 1.f + 0.5f * m.sky;
            clK *= clampf(1.f + 0.3f * m.fol + 0.2f * m.earth - 0.8f * m.skin - 0.9f * skyBlue, 0.f, 1.5f);
        }
        const float bp = k.bpK * c.bp / 255.f, wp = c.wp / 255.f, inv = 1.f / (wp - bp);
        float y = clampf((t - bp) * inv, 0.f, 1.f);
        float bb = y;
        if (c.base) {
            const float bx = ((float)sx + 0.5f) / (float)kBaseScale - 0.5f;
            const float by = ((float)sy + 0.5f) / (float)kBaseScale - 0.5f;
            bb = clampf((sampleF(c.base.get(), c.bw, c.bh, bx, by) / 255.f - bp) * inv, 0.f, 1.f);
        }
        if (std::fabs(k.gamma - 1.f) > 0.001f) {
            y = std::pow(y, k.gamma);
            bb = std::pow(bb, k.gamma);
        }
        const float omb = 1.f - bb;
        const float b1 = bb + shK * 2.f * bb * omb * omb;           // shadow lift
        const float b2 = b1 - hlK * b1 * b1 * b1 * (1.f - b1);      // highlight roll-off
        const float ratio = clampf((b2 + 0.02f) / (bb + 0.02f), 0.85f, 1.45f);
        const float mid = 1.f - std::fabs(2.f * bb - 1.f);
        const float d0 = (y - bb) * ratio;
        const float dq = d0 / 0.10f;
        const float det = d0 * (1.f + clK * (0.5f + 0.5f * mid) / (1.f + dq * dq));
        y = clampf(b2 + det, 0.f, 1.f);
        const float sc = y * y * (3.f - 2.f * y);
        y = y + k.con * (sc - y) * smooth01(0.03f, 0.35f, y);        // soft S-curve, blacks stay deep
        y = clampf(y, 0.f, 1.f);
        t = y;

        // ---- color
        const float mag = mag0 * (1.f / 128.f);
        const float lowC = 1.f - clampf(mag * 2.5f, 0.f, 1.f);
        float gain = k.sat * (1.f + k.vib * lowC);                   // muted colors get a little more, vivid ones none
        const float du = (cu + 16.f) * (1.f / 14.f), dv = (cv - 24.f) * (1.f / 14.f);
        const float skin = std::exp(-(du * du + dv * dv));
        const float skinP = sem ? std::max(skin, m.skin) : skin;
        gain = 1.f + (gain - 1.f) * (1.f - 0.75f * skinP);           // natural skin
        if (sem) {
            gain *= clampf(1.f + 0.5f * ((y + 0.02f) / (t0 + 0.02f) - 1.f), 0.92f, 1.35f);  // lifted shadows keep their color
            gain *= 1.f + 0.07f * skyBlue;                           // slightly richer blue sky
            gain *= 1.f - 0.30f * m.neu * (1.f - smooth01(4.f, 12.f, mag0));   // grays stay gray
            t -= 0.03f * skyBlue * t;
            if (skyBlue > 0.02f || m.fol > 0.02f) {                  // tiny hue corrections only
                const float th = std::atan2(cv, cu) * 57.29578f;
                float dth = clampf(0.25f * angDiff(-35.f, th), -6.f, 6.f) * skyBlue;
                dth += clampf(0.20f * angDiff(-135.f, th), -7.f, 7.f) * m.fol * smooth01(8.f, 16.f, mag0);  // yellow leaves -> true green
                if (dth != 0.f) {
                    const float ang = dth * 0.0174533f, ca = std::cos(ang), sa = std::sin(ang);
                    const float nu = cu * ca - cv * sa, nv = cu * sa + cv * ca;
                    cu = nu; cv = nv;
                }
            }
        }
        gain *= 0.78f + 0.22f * smooth01(0.02f, 0.15f, y);           // calm chroma noise in deep shadows
        gain *= 1.f - 0.55f * smooth01(0.78f, 1.0f, y);              // highlights lose color (no pink/yellow glare)
        const float wm = k.warm * (0.4f + 0.6f * y);
        cu = cu * gain - 0.7f * wm;
        cv = cv * gain + 0.9f * wm;
        if (sem) {                                                    // gray surfaces anchor the white balance
            cu -= clampf(c.castU * k.castK, -k.castMax, k.castMax);
            cv -= clampf(c.castV * k.castK, -k.castMax, k.castMax);
        }
        // Soft gamut limit: very saturated pixels (neon green/yellow) are compressed instead of clipped.
        const float mg = std::sqrt(cu * cu + cv * cv);
        if (mg > k.chromaCap0) {
            const float span = k.chromaCap - k.chromaCap0;
            const float mg2 = k.chromaCap0 + span * std::tanh((mg - k.chromaCap0) / span);
            const float s = mg2 / mg;
            cu *= s; cv *= s;
        }
    }
    const float Yo = clampf(t, 0.f, 1.f) * 255.f;
    r = clampf((Yo + 1.402f * cv) * (1.f / 255.f), 0.f, 1.f);
    g = clampf((Yo - 0.344136f * cu - 0.714136f * cv) * (1.f / 255.f), 0.f, 1.f);
    b = clampf((Yo + 1.772f * cu) * (1.f / 255.f), 0.f, 1.f);
}

// Fills luma (full-res) and chroma (2x2 averaged) planes from ARGB rows [y0, y0+rows); y0 and rows must be even.
void loadArgbRows(Ctx& c, const uint32_t* px, int y0, int rows) {
    if (!c.sy) {
        c.sy = alloc<float>((size_t)c.w * c.h);
        c.su = alloc<float>((size_t)c.cw * c.ch);
        c.sv = alloc<float>((size_t)c.cw * c.ch);
    }
    const int w = c.w;
    parallelFor(rows / 2, [&](int pr) {
        const int ry = pr * 2;
        const int gy = y0 + ry;
        for (int x = 0; x < w; x += 2) {
            float su = 0.f, sv = 0.f;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    const uint32_t p = px[(size_t)(ry + dy) * w + x + dx];
                    const float R = (float)((p >> 16) & 255u), G = (float)((p >> 8) & 255u), B = (float)(p & 255u);
                    const float Y = 0.299f * R + 0.587f * G + 0.114f * B;
                    c.sy[(size_t)(gy + dy) * w + x + dx] = Y;
                    su += (B - Y) * 0.564f;
                    sv += (R - Y) * 0.713f;
                }
            }
            c.su[(size_t)(gy / 2) * c.cw + x / 2] = 128.f + su * 0.25f;
            c.sv[(size_t)(gy / 2) * c.cw + x / 2] = 128.f + sv * 0.25f;
        }
    });
}

void analyzeAll(Ctx& c) {
    computeTone(c);
    computeBase(c);
    computeScene(c);
    computeSemantic(c);
}

Ctx* ctxOf(jlong h) { return reinterpret_cast<Ctx*>(h); }

}  // namespace

// ---------------------------------------------------------------- JNI

extern "C" {

JNIEXPORT jlong JNICALL Java_com_burststack_cam_NativeLib_nativeCreate(JNIEnv*, jobject, jint w, jint h, jboolean raw, jint capacity) {
    Ctx* c = new (std::nothrow) Ctx();
    if (!c) return 0;
    c->w = w & ~1;
    c->h = h & ~1;
    c->cw = c->w / 2;
    c->ch = c->h / 2;
    c->raw = raw != JNI_FALSE;
    c->capacity = capacity;
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL Java_com_burststack_cam_NativeLib_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete ctxOf(handle);
}

JNIEXPORT jint JNICALL Java_com_burststack_cam_NativeLib_nativeFrameCount(JNIEnv*, jobject, jlong handle) {
    Ctx* c = ctxOf(handle);
    if (!c) return 0;
    return (jint)(c->raw ? c->rawFrames.size() : c->yuv.size());
}

JNIEXPORT jboolean JNICALL Java_com_burststack_cam_NativeLib_nativeAddYuv(JNIEnv* env, jobject, jlong handle,
        jobject yBuf, jint yRow, jobject uBuf, jint uRow, jobject vBuf, jint vRow, jint uvPix) {
    Ctx* c = ctxOf(handle);
    if (!c || c->raw || (int)c->yuv.size() >= c->capacity) return JNI_FALSE;
    const uint8_t* ys = (const uint8_t*)env->GetDirectBufferAddress(yBuf);
    const uint8_t* us = (const uint8_t*)env->GetDirectBufferAddress(uBuf);
    const uint8_t* vs = (const uint8_t*)env->GetDirectBufferAddress(vBuf);
    const jlong yc = env->GetDirectBufferCapacity(yBuf);
    const jlong uc = env->GetDirectBufferCapacity(uBuf);
    const jlong vc = env->GetDirectBufferCapacity(vBuf);
    if (!ys || !us || !vs) return JNI_FALSE;
    const int w = c->w, h = c->h, cw = c->cw, ch = c->ch;
    if ((jlong)(h - 1) * yRow + w > yc) return JNI_FALSE;
    if ((jlong)(ch - 1) * uRow + (jlong)(cw - 1) * uvPix + 1 > uc) return JNI_FALSE;
    if ((jlong)(ch - 1) * vRow + (jlong)(cw - 1) * uvPix + 1 > vc) return JNI_FALSE;
    try {
        YuvFrame fr;
        fr.y = alloc<uint8_t>((size_t)w * h);
        fr.u = alloc<uint8_t>((size_t)cw * ch);
        fr.v = alloc<uint8_t>((size_t)cw * ch);
        for (int y = 0; y < h; y++) std::memcpy(fr.y.get() + (size_t)y * w, ys + (size_t)y * yRow, (size_t)w);
        for (int y = 0; y < ch; y++) {
            uint8_t* du = fr.u.get() + (size_t)y * cw;
            uint8_t* dv = fr.v.get() + (size_t)y * cw;
            const uint8_t* su = us + (size_t)y * uRow;
            const uint8_t* sv = vs + (size_t)y * vRow;
            if (uvPix == 1) {
                std::memcpy(du, su, (size_t)cw);
                std::memcpy(dv, sv, (size_t)cw);
            } else {
                for (int x = 0; x < cw; x++) { du[x] = su[(size_t)x * uvPix]; dv[x] = sv[(size_t)x * uvPix]; }
            }
        }
        c->yuv.push_back(std::move(fr));
    } catch (...) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_burststack_cam_NativeLib_nativeAddRaw(JNIEnv* env, jobject, jlong handle,
        jobject buf, jint rowStrideBytes) {
    Ctx* c = ctxOf(handle);
    if (!c || !c->raw || (int)c->rawFrames.size() >= c->capacity) return JNI_FALSE;
    const uint8_t* src = (const uint8_t*)env->GetDirectBufferAddress(buf);
    const jlong cap = env->GetDirectBufferCapacity(buf);
    if (!src) return JNI_FALSE;
    const int w = c->w, h = c->h;
    if ((jlong)(h - 1) * rowStrideBytes + (jlong)w * 2 > cap) return JNI_FALSE;
    try {
        Buf<uint16_t> fr = alloc<uint16_t>((size_t)w * h);
        for (int y = 0; y < h; y++)
            std::memcpy(fr.get() + (size_t)y * w, src + (size_t)y * rowStrideBytes, (size_t)w * 2);
        c->rawFrames.push_back(std::move(fr));
    } catch (...) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_com_burststack_cam_NativeLib_nativeStackYuv(JNIEnv* env, jobject, jlong handle,
        jboolean doAlign, jobject listener) {
    Ctx* c = ctxOf(handle);
    if (!c || c->raw) return -1;
    try {
        return stackYuvImpl(env, *c, doAlign != JNI_FALSE, listener);
    } catch (...) {
        return -2;
    }
}

JNIEXPORT jint JNICALL Java_com_burststack_cam_NativeLib_nativeStackRaw(JNIEnv* env, jobject, jlong handle,
        jboolean doAlign, jint black, jint white, jobject listener) {
    Ctx* c = ctxOf(handle);
    if (!c || !c->raw) return -1;
    try {
        return stackRawImpl(env, *c, doAlign != JNI_FALSE, black, white, listener);
    } catch (...) {
        return -2;
    }
}

JNIEXPORT jobject JNICALL Java_com_burststack_cam_NativeLib_nativeRawOutput(JNIEnv* env, jobject, jlong handle) {
    Ctx* c = ctxOf(handle);
    if (!c || !c->rawOut) return nullptr;
    return env->NewDirectByteBuffer(c->rawOut.get(), (jlong)c->w * c->h * 2);
}

JNIEXPORT jint JNICALL Java_com_burststack_cam_NativeLib_nativeLoadArgb(JNIEnv* env, jobject, jlong handle,
        jintArray px, jint y0, jint rows) {
    Ctx* c = ctxOf(handle);
    if (!c || c->raw) return -1;
    if (y0 < 0 || (y0 & 1) || (rows & 1) || rows <= 0 || y0 + rows > c->h) return -3;
    try {
        std::vector<jint> buf((size_t)c->w * (size_t)rows);
        if ((size_t)env->GetArrayLength(px) < buf.size()) return -3;
        env->GetIntArrayRegion(px, 0, (jsize)buf.size(), buf.data());
        loadArgbRows(*c, reinterpret_cast<const uint32_t*>(buf.data()), y0, rows);
        return rows;
    } catch (...) {
        return -2;
    }
}

JNIEXPORT jint JNICALL Java_com_burststack_cam_NativeLib_nativeAnalyze(JNIEnv*, jobject, jlong handle) {
    Ctx* c = ctxOf(handle);
    if (!c || !c->sy) return -1;
    try {
        analyzeAll(*c);
        return 0;
    } catch (...) {
        return -2;
    }
}

JNIEXPORT jint JNICALL Java_com_burststack_cam_NativeLib_nativeRenderPng16(JNIEnv* env, jobject, jlong handle,
        jint rot, jboolean tone, jboolean sharp, jint look, jint oy0, jint rows, jbyteArray out) {
    Ctx* c = ctxOf(handle);
    if (!c || !c->sy) return -1;
    const bool sw = (rot == 90 || rot == 270);
    const int ow = sw ? c->h : c->w, oh = sw ? c->w : c->h;
    rows = std::min((int)rows, oh - (int)oy0);
    if (rows <= 0) return 0;
    const size_t rowBytes = 1 + (size_t)ow * 6;
    if ((size_t)env->GetArrayLength(out) < rowBytes * (size_t)rows) return -3;
    try {
        std::vector<uint8_t> buf(rowBytes * (size_t)rows);
        const bool t = tone != JNI_FALSE, s = sharp != JNI_FALSE;
        const Look lk = makeLook(*c, (int)look);
        parallelFor(rows, [&](int r) {
            const int oy = oy0 + r;
            std::vector<uint8_t> raw((size_t)ow * 6);
            for (int ox = 0; ox < ow; ox++) {
                int sx, sy;
                mapCoord(rot, c->w, c->h, ox, oy, sx, sy);
                float R, G, B;
                renderPixel(*c, lk, sx, sy, t, s, R, G, B);
                const uint16_t vr = (uint16_t)(R * 65535.f + 0.5f);
                const uint16_t vg = (uint16_t)(G * 65535.f + 0.5f);
                const uint16_t vb = (uint16_t)(B * 65535.f + 0.5f);
                uint8_t* p = raw.data() + (size_t)ox * 6;
                p[0] = (uint8_t)(vr >> 8); p[1] = (uint8_t)(vr & 255);
                p[2] = (uint8_t)(vg >> 8); p[3] = (uint8_t)(vg & 255);
                p[4] = (uint8_t)(vb >> 8); p[5] = (uint8_t)(vb & 255);
            }
            uint8_t* dst = buf.data() + rowBytes * (size_t)r;
            dst[0] = 1;  // PNG filter type "Sub"
            const size_t nb = (size_t)ow * 6;
            for (size_t i = 0; i < nb; i++) dst[1 + i] = (uint8_t)(raw[i] - (i >= 6 ? raw[i - 6] : 0));
        });
        env->SetByteArrayRegion(out, 0, (jsize)buf.size(), reinterpret_cast<const jbyte*>(buf.data()));
        return (jint)buf.size();
    } catch (...) {
        return -2;
    }
}

JNIEXPORT jint JNICALL Java_com_burststack_cam_NativeLib_nativeRenderArgb(JNIEnv* env, jobject, jlong handle,
        jint rot, jboolean tone, jboolean sharp, jint look, jint oy0, jint rows, jintArray out) {
    Ctx* c = ctxOf(handle);
    if (!c || !c->sy) return -1;
    const bool sw = (rot == 90 || rot == 270);
    const int ow = sw ? c->h : c->w, oh = sw ? c->w : c->h;
    rows = std::min((int)rows, oh - (int)oy0);
    if (rows <= 0) return 0;
    if ((size_t)env->GetArrayLength(out) < (size_t)ow * (size_t)rows) return -3;
    try {
        std::vector<jint> buf((size_t)ow * (size_t)rows);
        const bool t = tone != JNI_FALSE, s = sharp != JNI_FALSE;
        const Look lk = makeLook(*c, (int)look);
        parallelFor(rows, [&](int r) {
            const int oy = oy0 + r;
            jint* dst = buf.data() + (size_t)ow * (size_t)r;
            for (int ox = 0; ox < ow; ox++) {
                int sx, sy;
                mapCoord(rot, c->w, c->h, ox, oy, sx, sy);
                float R, G, B;
                renderPixel(*c, lk, sx, sy, t, s, R, G, B);
                const uint32_t pr = (uint32_t)(R * 255.f + 0.5f);
                const uint32_t pg = (uint32_t)(G * 255.f + 0.5f);
                const uint32_t pb = (uint32_t)(B * 255.f + 0.5f);
                dst[ox] = (jint)(0xFF000000u | (pr << 16) | (pg << 8) | pb);
            }
        });
        env->SetIntArrayRegion(out, 0, (jsize)buf.size(), buf.data());
        return (jint)buf.size();
    } catch (...) {
        return -2;
    }
}

}  // extern "C"
