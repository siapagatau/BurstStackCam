#pragma once
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cmath>
#include <vector>
#include <thread>
#include <atomic>
#include <algorithm>
#include <memory>

namespace bs {

template <class T> using Buf = std::unique_ptr<T[]>;

template <class T> inline Buf<T> alloc(size_t n) { return Buf<T>(new T[n]); }

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

inline int hwThreads() {
    unsigned n = std::thread::hardware_concurrency();
    if (n == 0) n = 2;
    if (n > 6) n = 6;
    return (int)n;
}

// Runs fn(i) for i in [0,count) on a small pool of threads (dynamic scheduling).
template <class F>
void parallelFor(int count, F fn) {
    if (count <= 0) return;
    int nt = std::min(hwThreads(), count);
    if (nt <= 1) {
        for (int i = 0; i < count; i++) fn(i);
        return;
    }
    std::atomic<int> next(0);
    auto worker = [&]() {
        for (;;) {
            int i = next.fetch_add(1);
            if (i >= count) break;
            fn(i);
        }
    };
    std::vector<std::thread> ts;
    ts.reserve(nt - 1);
    for (int t = 0; t < nt - 1; t++) ts.emplace_back(worker);
    worker();
    for (auto& t : ts) t.join();
}

}  // namespace bs
