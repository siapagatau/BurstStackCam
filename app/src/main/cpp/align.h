#pragma once
// Tile-based, coarse-to-fine block matching with sub-pixel refinement.
#include "common.h"

namespace bs {

constexpr int TS = 16;  // tile size at every pyramid level

struct Plane {
    int w = 0, h = 0;
    const uint8_t* p = nullptr;
    Buf<uint8_t> own;
};

struct Pyramid {
    std::vector<Plane> lv;
};

// Per-tile motion field on the finest-level grid (units: finest-level pixels).
struct Flow {
    int gw = 0, gh = 0;
    std::vector<float> vx, vy, mad, wt;
    float sigma = 2.0f;  // expected |difference| scale for noise (pixel-level rejection)
};

struct LevelVec {
    int gw = 0, gh = 0;
    std::vector<float> vx, vy;
};

inline void downsample2(const Plane& src, Plane& dst) {
    dst.w = src.w / 2;
    dst.h = src.h / 2;
    dst.own = alloc<uint8_t>((size_t)dst.w * dst.h);
    dst.p = dst.own.get();
    uint8_t* d = dst.own.get();
    const int sw = src.w;
    const uint8_t* s = src.p;
    const int dw = dst.w, dh = dst.h;
    const int chunks = (dh + 15) / 16;
    parallelFor(chunks, [&](int c) {
        int ya = c * 16, yb = std::min(dh, ya + 16);
        for (int y = ya; y < yb; y++) {
            const uint8_t* r0 = s + (size_t)(2 * y) * sw;
            const uint8_t* r1 = r0 + sw;
            uint8_t* o = d + (size_t)y * dw;
            for (int x = 0; x < dw; x++)
                o[x] = (uint8_t)((r0[2 * x] + r0[2 * x + 1] + r1[2 * x] + r1[2 * x + 1] + 2) >> 2);
        }
    });
}

inline void buildPyramid(const uint8_t* base, int w, int h, Pyramid& pr, int maxLevels = 4) {
    pr.lv.clear();
    pr.lv.resize(1);
    pr.lv[0].w = w;
    pr.lv[0].h = h;
    pr.lv[0].p = base;
    while ((int)pr.lv.size() < maxLevels) {
        if (std::min(pr.lv.back().w, pr.lv.back().h) < 128) break;
        Plane nxt;
        downsample2(pr.lv.back(), nxt);
        pr.lv.push_back(std::move(nxt));
    }
}

inline float sampleU8(const uint8_t* p, int w, int h, float x, float y) {
    x = clampf(x, 0.f, (float)(w - 1));
    y = clampf(y, 0.f, (float)(h - 1));
    int x0 = (int)x, y0 = (int)y;
    int x1 = x0 + 1 < w ? x0 + 1 : x0;
    int y1 = y0 + 1 < h ? y0 + 1 : y0;
    float fx = x - (float)x0, fy = y - (float)y0;
    const uint8_t* r0 = p + (size_t)y0 * w;
    const uint8_t* r1 = p + (size_t)y1 * w;
    float a = (float)r0[x0] + (float)((int)r0[x1] - (int)r0[x0]) * fx;
    float b = (float)r1[x0] + (float)((int)r1[x1] - (int)r1[x0]) * fx;
    return a + (b - a) * fy;
}

inline float sampleF(const float* p, int w, int h, float x, float y) {
    x = clampf(x, 0.f, (float)(w - 1));
    y = clampf(y, 0.f, (float)(h - 1));
    int x0 = (int)x, y0 = (int)y;
    int x1 = x0 + 1 < w ? x0 + 1 : x0;
    int y1 = y0 + 1 < h ? y0 + 1 : y0;
    float fx = x - (float)x0, fy = y - (float)y0;
    const float* r0 = p + (size_t)y0 * w;
    const float* r1 = p + (size_t)y1 * w;
    float a = r0[x0] + (r0[x1] - r0[x0]) * fx;
    float b = r1[x0] + (r1[x1] - r1[x0]) * fx;
    return a + (b - a) * fy;
}

// Sum of absolute differences between ref block at (x0,y0) and frame block displaced by (dx,dy).
inline uint32_t sadBlock(const Plane& ref, const Plane& frm, int x0, int y0, int bw, int bh, int dx, int dy) {
    const int fx0 = x0 + dx, fy0 = y0 + dy;
    uint32_t sum = 0;
    if (fx0 >= 0 && fy0 >= 0 && fx0 + bw <= frm.w && fy0 + bh <= frm.h) {
        for (int j = 0; j < bh; j++) {
            const uint8_t* a = ref.p + (size_t)(y0 + j) * ref.w + x0;
            const uint8_t* b = frm.p + (size_t)(fy0 + j) * frm.w + fx0;
            for (int i = 0; i < bw; i++) {
                int d = (int)a[i] - (int)b[i];
                sum += (uint32_t)(d < 0 ? -d : d);
            }
        }
    } else {
        for (int j = 0; j < bh; j++) {
            const uint8_t* a = ref.p + (size_t)(y0 + j) * ref.w + x0;
            const uint8_t* b = frm.p + (size_t)clampi(fy0 + j, 0, frm.h - 1) * frm.w;
            for (int i = 0; i < bw; i++) {
                int d = (int)a[i] - (int)b[clampi(fx0 + i, 0, frm.w - 1)];
                sum += (uint32_t)(d < 0 ? -d : d);
            }
        }
    }
    return sum;
}

// Variance of the Laplacian: higher = sharper.
inline double lapVar(const Plane& P) {
    if (P.w < 3 || P.h < 3) return 0.0;
    std::vector<double> ps(P.h, 0.0), pq(P.h, 0.0);
    parallelFor(P.h - 2, [&](int r) {
        int y = r + 1;
        const uint8_t* up = P.p + (size_t)(y - 1) * P.w;
        const uint8_t* md = P.p + (size_t)y * P.w;
        const uint8_t* dn = P.p + (size_t)(y + 1) * P.w;
        double s = 0, q = 0;
        for (int x = 1; x < P.w - 1; x++) {
            int l = 4 * (int)md[x] - (int)up[x] - (int)dn[x] - (int)md[x - 1] - (int)md[x + 1];
            s += l;
            q += (double)l * (double)l;
        }
        ps[y] = s;
        pq[y] = q;
    });
    double S = 0, Q = 0;
    for (int y = 1; y < P.h - 1; y++) { S += ps[y]; Q += pq[y]; }
    double n = (double)(P.w - 2) * (double)(P.h - 2);
    double mean = S / n;
    return Q / n - mean * mean;
}

// Estimates the motion of `fp` relative to reference `rp`. If search == false the field stays zero
// (tripod assumption) but the mismatch measure is still computed for ghost rejection.
inline void alignFrame(const Pyramid& rp, const Pyramid& fp, bool search, Flow& out) {
    const int L = (int)rp.lv.size();
    std::vector<LevelVec> vecs(L);
    for (int l = L - 1; l >= 0; l--) {
        const Plane& R = rp.lv[l];
        const Plane& F = fp.lv[l];
        LevelVec& cur = vecs[l];
        cur.gw = (R.w + TS - 1) / TS;
        cur.gh = (R.h + TS - 1) / TS;
        const size_t n = (size_t)cur.gw * cur.gh;
        cur.vx.assign(n, 0.f);
        cur.vy.assign(n, 0.f);
        if (!search) continue;
        const LevelVec* coarse = (l + 1 < L) ? &vecs[l + 1] : nullptr;
        const int radius = (l == L - 1) ? 4 : ((l == 0) ? 1 : 2);
        const bool finest = (l == 0);
        parallelFor(cur.gh, [&](int ty) {
            for (int tx = 0; tx < cur.gw; tx++) {
                const int x0 = tx * TS, y0 = ty * TS;
                const int bw = std::min(TS, R.w - x0), bh = std::min(TS, R.h - y0);
                int ix = 0, iy = 0;
                if (coarse) {
                    int cx = std::min(tx / 2, coarse->gw - 1);
                    int cy = std::min(ty / 2, coarse->gh - 1);
                    ix = (int)lroundf(coarse->vx[(size_t)cy * coarse->gw + cx] * 2.f);
                    iy = (int)lroundf(coarse->vy[(size_t)cy * coarse->gw + cx] * 2.f);
                }
                const uint32_t lambda = (uint32_t)(bw * bh) / 32u;
                uint32_t bestCost = 0xFFFFFFFFu;
                int bx = ix, by = iy;
                auto eval = [&](int cx, int cy) {
                    uint32_t s = sadBlock(R, F, x0, y0, bw, bh, cx, cy);
                    uint32_t cost = s + lambda * (uint32_t)(std::abs(cx - ix) + std::abs(cy - iy));
                    if (cost < bestCost) { bestCost = cost; bx = cx; by = cy; }
                };
                for (int dy = -radius; dy <= radius; dy++)
                    for (int dx = -radius; dx <= radius; dx++) eval(ix + dx, iy + dy);
                if (ix != 0 || iy != 0) eval(0, 0);
                float fx = (float)bx, fy = (float)by;
                if (finest) {
                    float c0 = (float)sadBlock(R, F, x0, y0, bw, bh, bx, by);
                    float cl = (float)sadBlock(R, F, x0, y0, bw, bh, bx - 1, by);
                    float cr = (float)sadBlock(R, F, x0, y0, bw, bh, bx + 1, by);
                    float cu = (float)sadBlock(R, F, x0, y0, bw, bh, bx, by - 1);
                    float cd = (float)sadBlock(R, F, x0, y0, bw, bh, bx, by + 1);
                    float denx = cl + cr - 2.f * c0;
                    float deny = cu + cd - 2.f * c0;
                    if (denx > 1e-3f) fx += clampf(0.5f * (cl - cr) / denx, -0.5f, 0.5f);
                    if (deny > 1e-3f) fy += clampf(0.5f * (cu - cd) / deny, -0.5f, 0.5f);
                }
                cur.vx[(size_t)ty * cur.gw + tx] = fx;
                cur.vy[(size_t)ty * cur.gw + tx] = fy;
            }
        });
    }

    LevelVec& f0 = vecs[0];
    out.gw = f0.gw;
    out.gh = f0.gh;
    out.vx = std::move(f0.vx);
    out.vy = std::move(f0.vy);
    const size_t n = (size_t)out.gw * out.gh;
    out.mad.assign(n, 0.f);
    const Plane& R = rp.lv[0];
    const Plane& F = fp.lv[0];
    parallelFor(out.gh, [&](int ty) {
        for (int tx = 0; tx < out.gw; tx++) {
            const int x0 = tx * TS, y0 = ty * TS;
            const int bw = std::min(TS, R.w - x0), bh = std::min(TS, R.h - y0);
            const float vx = out.vx[(size_t)ty * out.gw + tx];
            const float vy = out.vy[(size_t)ty * out.gw + tx];
            double acc = 0;
            for (int j = 0; j < bh; j++) {
                const uint8_t* a = R.p + (size_t)(y0 + j) * R.w + x0;
                for (int i = 0; i < bw; i++) {
                    float b = sampleU8(F.p, F.w, F.h, (float)(x0 + i) + vx, (float)(y0 + j) + vy);
                    acc += std::fabs((float)a[i] - b);
                }
            }
            out.mad[(size_t)ty * out.gw + tx] = (float)(acc / (double)(bw * bh));
        }
    });
}

// Converts per-tile mismatch into stacking weights in [0,1] (noise-adaptive thresholds).
inline void computeWeights(Flow& f) {
    const int n = f.gw * f.gh;
    std::vector<float> tmp(f.mad.begin(), f.mad.end());
    std::nth_element(tmp.begin(), tmp.begin() + n / 2, tmp.end());
    const float med = std::min(tmp[n / 2], 8.f);
    const float t0 = 1.3f * med + 2.f;
    const float t1 = 2.5f * med + 6.f;
    f.sigma = clampf(1.25f * med, 1.5f, 10.f);
    std::vector<float> w0(n);
    for (int i = 0; i < n; i++) w0[i] = clampf((t1 - f.mad[i]) / (t1 - t0), 0.f, 1.f);
    f.wt.assign(n, 0.f);
    for (int ty = 0; ty < f.gh; ty++) {
        for (int tx = 0; tx < f.gw; tx++) {
            const int i = ty * f.gw + tx;
            float mn = w0[i];
            if (tx > 0) mn = std::min(mn, w0[i - 1]);
            if (tx + 1 < f.gw) mn = std::min(mn, w0[i + 1]);
            if (ty > 0) mn = std::min(mn, w0[i - f.gw]);
            if (ty + 1 < f.gh) mn = std::min(mn, w0[i + f.gw]);
            f.wt[i] = 0.5f * w0[i] + 0.5f * mn;  // slightly dilate rejected regions
        }
    }
}

}  // namespace bs
