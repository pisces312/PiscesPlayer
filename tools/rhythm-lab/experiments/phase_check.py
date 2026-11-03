"""Scratch: compare the Kotlin comb phase with a fine phase scan of the novelty."""

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path("tools/rhythm-lab").resolve()))

from rhythm_lab import chart  # noqa: E402
from rhythm_lab.config import CHART_OUT_DIR, Baseline  # noqa: E402
from _shared import load_novelty  # noqa: E402


def phase_scan(values, times, beat_ms, start_ms=None, end_ms=None, step=0.5):
    if start_ms is not None:
        mask = (times >= start_ms) & (times < end_ms)
        values = values[mask]
        times = times[mask]
    best_score = -1.0
    best_phi = 0.0
    for phi in np.arange(0.0, beat_ms, step):
        k0 = int(np.ceil((times[0] - phi) / beat_ms))
        k1 = int(np.floor((times[-1] - phi) / beat_ms))
        grid = phi + np.arange(k0, k1 + 1) * beat_ms
        score = float(np.interp(grid, times, values).mean())
        if score > best_score:
            best_score = score
            best_phi = float(phi)
    return best_phi, best_score


def main():
    baseline = Baseline.load()
    nov = load_novelty()
    novn = nov.values
    times = nov.times_ms
    fps = nov.fps
    hop = nov.hop_ms

    master = chart.load_chart(CHART_OUT_DIR / "master.json")
    offset, beat = chart.timing_points(master)[0]
    period_frames = fps * 60.0 / (60000.0 / beat)
    phase_period = max(1, round(period_frames))
    comb = np.zeros(phase_period)
    for frame in range(novn.size):
        comb[frame % phase_period] += novn[frame]
    phase_frame = int(np.argmax(comb))

    print(f"chart offset      : {offset:.2f} ms")
    print(f"beat              : {beat:.4f} ms")
    print(f"period frames     : {period_frames:.4f}  phasePeriod={phase_period}  "
          f"phaseFrame={phase_frame}  phaseFrame*hop={phase_frame * hop:.2f} ms")

    phi, score = phase_scan(novn, times, beat)
    print(f"global phase scan : phi={phi:.2f} ms  score={score:.4f}")
    diff = (phi - offset) % beat
    if diff > beat / 2:
        diff -= beat
    print(f"phase - offset    : {diff:+.2f} ms")
    print(f"prediction offset = phi - phaseFrame*hop = {(phi - phase_frame * hop) % beat:.2f} ms")

    print("\n10 s windows (fine phase relative to global chart offset):")
    start = 2000.0
    stop = float(times[-1]) - 1000.0
    while start < stop:
        end = min(start + 10000.0, stop)
        local_phi, local_score = phase_scan(novn, times, beat, start, end)
        rel = (local_phi - offset) % beat
        if rel > beat / 2:
            rel -= beat
        print(f"  {start/1000:6.1f}-{end/1000:6.1f}s  phi={local_phi:7.2f}  "
              f"rel={rel:+7.2f} ms  score={local_score:.4f}")
        start = end


if __name__ == "__main__":
    main()
