"""Scratch: windowed tempo estimate from the novelty autocorrelation."""

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path("tools/rhythm-lab").resolve()))

from _shared import load_novelty  # noqa: E402


def autocorr_period(values, fps, min_bpm=50.0, max_bpm=240.0):
    x = values.astype(np.float64)
    x = x - x.mean()
    ac = np.correlate(x, x, mode="full")[x.size - 1:]
    ac /= ac[0] + 1e-12
    lag_min = int(np.floor(60.0 * fps / max_bpm))
    lag_max = int(np.ceil(60.0 * fps / min_bpm))
    lag_max = min(lag_max, ac.size - 2)
    lag = lag_min + int(np.argmax(ac[lag_min:lag_max + 1]))
    y0, y1, y2 = ac[lag - 1], ac[lag], ac[lag + 1]
    denom = y0 - 2.0 * y1 + y2
    delta = 0.0 if abs(denom) < 1e-12 else float(np.clip(0.5 * (y0 - y2) / denom, -0.5, 0.5))
    return (lag + delta) / fps, float(y1)


def main():
    nov = load_novelty()
    novn = nov.values
    times = nov.times_ms
    fps = nov.fps
    bands = nov.flux_bands

    print(f"{'window':>16} {'all bpm':>8} {'all ms':>8} {'low bpm':>8} {'low ms':>8}")
    window = 20.0
    step = 5.0
    start = 0.0
    stop = float(times[-1])
    while start + window <= stop:
        mask = (times >= start * 1000.0) & (times < (start + window) * 1000.0)
        all_period, _ = autocorr_period(novn[mask], fps)
        low = bands[mask][:, 0:3].sum(axis=1)
        low = low / (np.percentile(low, 95) + 1e-9)
        low_period, _ = autocorr_period(low, fps)
        print(f"{start:6.1f}-{start+window:6.1f}s {60000/all_period:8.2f} {all_period*1000:8.1f} "
              f"{60000/low_period:8.2f} {low_period*1000:8.1f}")
        start += step


if __name__ == "__main__":
    main()
