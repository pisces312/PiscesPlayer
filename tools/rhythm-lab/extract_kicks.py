#!/usr/bin/env python
"""Clean kick extraction from a separated drums stem (auto-play diagnostics support).

Low-passes the stem, picks energy peaks with a musical min-distance, and writes one
kick time per line (ms). The kick series is the physical beat of the song: the auto-play
tool (RhythmAutoPlayToolTest, env RHYTHM_AUTOPLAY_KICK_TXT) measures the chart grid's
phase against it.

Usage:
    python extract_kicks.py <drums.wav> <out.txt> [--min-distance 300] [--top-hz 180]
"""
import argparse
import sys

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfiltfilt


def extract_kicks(path, min_distance_ms=300.0, top_hz=180.0, quantile=75.0):
    sr, data = wavfile.read(path)
    mono = data.astype(np.float64)
    if mono.ndim > 1:
        mono = mono.mean(axis=1)
    mono /= max(1e-9, np.max(np.abs(mono)))

    # Kick band: everything below the snare fundamental region.
    sos = butter(4, top_hz / (sr / 2), btype="low", output="sos")
    low = sosfiltfilt(sos, mono)

    # Onset envelope of the low band: positive energy difference over ~10 ms hops.
    hop = int(sr * 0.010)
    frames = len(low) // hop
    energy = np.array([np.sqrt(np.mean(low[i * hop:(i + 1) * hop] ** 2)) for i in range(frames)])
    flux = np.maximum(0.0, np.diff(energy, prepend=energy[0]))

    threshold = np.percentile(flux, quantile) * 0.5
    min_gap = max(1, int(min_distance_ms / 10.0))  # frames of 10 ms

    peaks = []
    last = -min_gap
    for i in range(1, frames - 1):
        if flux[i] < threshold:
            continue
        if flux[i] < flux[i - 1] or flux[i] < flux[i + 1]:
            continue
        if i - last < min_gap:
            # Keep the stronger of two close candidates.
            if peaks and flux[i] > flux[peaks[-1]]:
                peaks[-1] = i
                last = i
            continue
        peaks.append(i)
        last = i

    times = np.array(peaks, dtype=np.float64) * 10.0  # frame centre, ms
    return sr, energy, flux, times


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("wav")
    parser.add_argument("out")
    parser.add_argument("--min-distance", type=float, default=300.0)
    parser.add_argument("--top-hz", type=float, default=180.0)
    parser.add_argument("--quantile", type=float, default=75.0)
    args = parser.parse_args()

    sr, energy, flux, times = extract_kicks(args.wav, args.min_distance, args.top_hz, args.quantile)
    with open(args.out, "w", encoding="utf-8") as handle:
        for time in times:
            handle.write(f"{time:.1f}\n")

    gaps = np.diff(times)
    print(f"kicks: {len(times)}  first={times[0]:.0f}ms last={times[-1]:.0f}ms")
    if gaps.size:
        hist, edges = np.histogram(gaps, bins=np.arange(0, 1400, 100))
        for count, lo, hi in zip(hist, edges[:-1], edges[1:]):
            print(f"  gap {lo:.0f}-{hi:.0f}ms: {count}")


if __name__ == "__main__":
    sys.exit(main())
