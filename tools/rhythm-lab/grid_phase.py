#!/usr/bin/env python
"""Windowed cross-correlation: where does the chart grid sit relative to the beat?

Robust phase ruler for the auto-play diagnostics. Instead of picking individual kicks
(noisy on real arrangements), this correlates the drums stem's low-band onset envelope
with the chart's grid-line impulse train in 30 s windows. The best lag per window is the
grid's phase against the audible low-end pulse:

    lag ~ 0 ms          grid locked to the beat
    lag walking around  grid losing the beat (the "played fine, then all misses" feel)
    lag stuck at +-half grid spacing   grid permanently off-beat

Usage:
    python grid_phase.py <drums.wav> <grid_lines.txt> [--grid-ms 660] [--window 30]
"""
import argparse
import sys

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfiltfilt

HOP_S = 0.010


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("wav")
    parser.add_argument("grid")
    parser.add_argument("--grid-ms", type=float, default=660.0,
                        help="grid spacing, defines the +-lag range (half of it)")
    parser.add_argument("--window", type=float, default=30.0)
    parser.add_argument("--low-hz", type=float, default=25.0)
    parser.add_argument("--high-hz", type=float, default=180.0,
                        help="band of the pulse ruler: 25-180 kick/bass, 200-500 snare (on-beat)")
    args = parser.parse_args()

    sr, data = wavfile.read(args.wav)
    mono = data.astype(np.float64)
    if mono.ndim > 1:
        mono = mono.mean(axis=1)
    mono /= max(1e-9, np.max(np.abs(mono)))

    sos = butter(4, [args.low_hz / (sr / 2), args.high_hz / (sr / 2)],
                 btype="band", output="sos")
    low = sosfiltfilt(sos, mono)
    hop = int(sr * HOP_S)
    frames = len(low) // hop
    energy = np.array([np.sqrt(np.mean(low[i * hop:(i + 1) * hop] ** 2)) for i in range(frames)])
    flux = np.maximum(0.0, np.diff(energy, prepend=energy[0]))

    grid_lines = np.loadtxt(args.grid)
    grid_train = np.zeros(frames)
    for time in grid_lines:
        index = int(round(time / 1000.0 / HOP_S))
        if 0 <= index < frames:
            grid_train[index] = 1.0

    max_lag = int(round(args.grid_ms / 2.0 / 1000.0 / HOP_S))
    window_frames = int(round(args.window / HOP_S))

    print(f"frames={frames} grid_lines={len(grid_lines)} maxLag=+-{max_lag * 10:.0f}ms")
    print("window    bestLag   corr    (bestLag = grid lines sit this far from the low-end pulse)")
    for start in range(0, frames - window_frames, window_frames):
        end = start + window_frames
        seg_flux = flux[start:end]
        seg_grid = grid_train[start:end]
        if seg_grid.sum() < 2 or seg_flux.sum() <= 0:
            continue
        best_lag, best_corr = 0, -np.inf
        for lag in range(-max_lag, max_lag + 1):
            shifted = np.roll(seg_grid, lag)
            if lag > 0:
                shifted[:lag] = 0.0
            elif lag < 0:
                shifted[lag:] = 0.0
            corr = float(np.dot(seg_flux, shifted) / (np.linalg.norm(shifted) + 1e-9))
            if corr > best_corr:
                best_corr, best_lag = corr, lag
        t0 = start * HOP_S
        print(f"{t0:6.1f}s   {best_lag * 10:+5.0f}ms  {best_corr:7.3f}")


if __name__ == "__main__":
    sys.exit(main())
