"""Scratch: whole-song and windowed tempo scan of a plain novelty."""

import numpy as np

from _shared import load_pcm

sr, x = load_pcm()
x = x.astype(np.float32)
print("sr", sr, "samples", len(x), "dur", len(x) / sr)

n_fft, hop = 1024, 512
win = np.hanning(n_fft).astype(np.float32)
frames = 1 + (len(x) - n_fft) // hop
S = np.empty((frames, n_fft // 2 + 1), dtype=np.float32)
for i in range(frames):
    seg = x[i * hop:i * hop + n_fft] * win
    S[i] = np.abs(np.fft.rfft(seg))
S = np.log1p(S)
diff = np.diff(S, axis=0)
nov = np.maximum(diff, 0).sum(axis=1)
nov = np.concatenate([[0], nov])
nov = nov / (np.percentile(nov, 95) + 1e-9)
t = np.arange(frames) * hop / sr


def scan(lo, hi, tt, vv, step=0.005):
    bpms = np.arange(lo, hi, step)
    mags = np.empty_like(bpms)
    for k, bpm in enumerate(bpms):
        f = bpm / 60.0
        z = np.sum(vv * np.exp(-2j * np.pi * f * tt))
        mags[k] = abs(z)
    i = int(np.argmax(mags))
    return bpms[i], mags[i]


bpm_all, mag_all = scan(160, 182, t, nov)
print(f"whole song best bpm={bpm_all:.3f} mag={mag_all:.1f}")

win_s, hop_s = 20.0, 10.0
print("win_start_s, best_bpm, beat_ms, phase_deg, concentration")
for start in np.arange(0, t[-1] - win_s, hop_s):
    sel = (t >= start) & (t < start + win_s)
    tt = t[sel]
    w = np.hanning(len(tt)).astype(np.float64)
    vv = nov[sel] * w
    bpm, mag = scan(160, 182, tt - tt[0], vv, step=0.01)
    f = bpm / 60.0
    z = np.sum(vv * np.exp(-2j * np.pi * f * (tt - tt[0])))
    phase = np.degrees(np.angle(z))
    conc = mag / (np.sum(vv) + 1e-9)
    print(f"{start:7.1f}, {bpm:8.3f}, {60000/bpm:8.2f}, {phase:9.2f}, {conc:6.3f}")
