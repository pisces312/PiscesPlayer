"""Scratch: fine phase scan of a plain 1024-point novelty against the chart grid.

This is the check that exposed the half-beat offset: the Kotlin comb phase subtracted a
fractional period it had rounded into its residue table, so the grid sat half a chart beat
early (139.29 ms instead of roughly 324 ms).
"""

import numpy as np

from _shared import load_pcm

P = 352.3217929698567
OFFSET = 139.2895289670705

sr, x = load_pcm()

n_fft, hop = 1024, 512
win = np.hanning(n_fft).astype(np.float32)
frames = 1 + (len(x) - n_fft) // hop
S = np.empty((frames, n_fft // 2 + 1), dtype=np.float32)
for i in range(frames):
    S[i] = np.abs(np.fft.rfft(x[i * hop:i * hop + n_fft] * win))
S = np.log1p(S)
nov = np.concatenate([[0], np.maximum(np.diff(S, axis=0), 0).sum(axis=1)])
nov = nov / (np.percentile(nov, 95) + 1e-9)
t_audio = np.arange(frames) * hop / sr


def phase_error(query_times, times, weights, sigma_s):
    out_e, out_r = [], []
    for q in query_times:
        g = np.exp(-0.5 * ((times - q) / sigma_s) ** 2)
        z = np.sum(weights * g * np.exp(-2j * np.pi * (times - OFFSET / 1000.0) / (P / 1000.0)))
        norm = np.sum(weights * g)
        e = -np.angle(z) / (2 * np.pi) * P
        e = (e + P / 2) % P - P / 2
        out_e.append(e)
        out_r.append(abs(z) / norm if norm > 0 else 0.0)
    return np.array(out_e), np.array(out_r)


qs = np.arange(10.0, 175.0, 5.0)
e, r = phase_error(qs, t_audio[::4], nov[::4].astype(np.float64), 2.8)
print("audio-nov")
print("  t(s): " + " ".join(f"{q:5.0f}" for q in qs))
print("  eps : " + " ".join(f"{v:5.0f}" for v in e))
print("  R   : " + " ".join(f"{v:5.2f}" for v in r))
