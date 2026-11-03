"""Regression sweep for the DP tracker + weighted piecewise timing.

Runs the production candidate (`rhythm_lab.tracker`) over a small parameter grid and
reports grid-vs-reference errors for every chart division. The defaults in
`tracker.TrackerConfig` are the selected point; this script proves the plateau around it
and exits non-zero if the selected point ever stops passing the baseline target.

Usage (repository root):
    python tools/rhythm-lab/experiments/tune_tracker.py
"""

import json
import sys

import numpy as np

from _shared import CHART_OUT_DIR, load_novelty, load_pcm
from rhythm_lab import chart, reference, tracker
from rhythm_lab.config import Baseline

CHART_DIVS = (1, 2, 4)
START_MS = 2000.0


def start_bpm(reference_beats: np.ndarray) -> float:
    """The app starts from its detected chart beat; fall back to twice the music beat."""
    master = chart.load_chart(CHART_OUT_DIR / "master.json")
    timing = chart.timing_points(master)
    if timing and timing[0][1] > 0.0:
        return 60000.0 / timing[0][1]
    return 2.0 * 60000.0 / float(np.median(np.diff(np.sort(reference_beats))))


def score(timing, reference_beats: np.ndarray, target_ms: float) -> dict[int, tuple]:
    ref = np.sort(reference_beats)
    stop = float(ref[-1])
    fake = {
        "timing": [{"timeMs": int(round(t)), "beatMs": float(b)} for t, b in timing],
        "durationMs": stop,
    }
    out = {}
    for div in CHART_DIVS:
        grid = chart.chart_grid_times(fake, div, start_ms=START_MS, end_ms=stop)
        ref_grid = chart.reference_grid(ref, div * 2, START_MS, stop)
        errors = np.abs(chart.nearest_signed_errors(grid, ref_grid))
        out[div] = (float(np.median(errors)), float(np.percentile(errors, 95)),
                    float(errors.max()), float(100.0 * np.mean(errors <= target_ms)))
    return out


def main() -> int:
    baseline = Baseline.load()
    pcm, sample_rate = load_pcm()
    reference_beats = reference.load_beats(baseline.reference_beats)
    nov = load_novelty()
    bpm = start_bpm(reference_beats)
    target = baseline.target_max_error_ms
    print(f"start_bpm={bpm:.3f}  target={target:.1f} ms  "
          f"reference_span={reference_beats[0]:.1f}..{reference_beats[-1]:.1f} ms")
    print(f"{'tight':>6} {'anch':>4} {'half':>4}  " +
          "  ".join(f"d{d} med/p95/max/%" for d in CHART_DIVS))

    def run(**overrides):
        cfg = tracker.TrackerConfig(**{**tracker.TrackerConfig().__dict__, **overrides})
        estimate = tracker.estimate_timing(nov, pcm, sample_rate, bpm,
                                           float(nov.times_ms[-1]), cfg)
        return estimate, score(estimate.timing, reference_beats, target)

    for tight in (400.0, 600.0, 800.0):
        for anchor in (1, 4):
            for half in (8, 10, 12):
                _, cells = run(dp_tightness=tight, anchor_every=anchor, fit_half_window=half)
                print(f"{tight:>6.0f} {anchor:>4} {half:>4}  " + "  ".join(
                    f"{c[0]:5.1f}/{c[1]:5.1f}/{c[2]:5.1f}/{c[3]:5.1f}"
                    for c in (cells[d] for d in CHART_DIVS)))

    estimate, cells = run()
    print("\nselected defaults: " + json.dumps(estimate.diagnostics, ensure_ascii=False))
    for div in CHART_DIVS:
        median, p95, maximum, within = cells[div]
        print(f"  div{div}: median={median:.2f} p95={p95:.2f} max={maximum:.2f} "
              f"within {target:.0f} ms={within:.1f}%")
    passed = all(cells[div][2] <= target for div in CHART_DIVS)
    print("PASS" if passed else "FAIL")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
