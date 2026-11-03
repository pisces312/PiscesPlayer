"""Intro lock-in fix: does extrapolating from the lock-in point fix the intro, and is the
whole-song tempo even better?

Variants per song (same tracked beats for all three):
  none    current piecewise timing; the intro fits its own (corrupted) beats
  fitL    anchors before the lock-in point extrapolated with the lock-in fit's own slope
  global  same, but with the whole-song median period as the extrapolated tempo
          (the "use the whole song's tempo" idea; this is what shipped)

The lock-in point is the first beat whose next 8 intervals all match the whole-song
median period within 3%; 0 means nothing to fix.

Usage (repository root):
    python tools/rhythm-lab/experiments/intro_fix.py
"""

import numpy as np

from selfscore import all_songs, load_novelty, used_bpm
from rhythm_lab import audio, reference, tracker
from rhythm_lab.config import Baseline


def fit_at_lock_variant(beat_times, duration_ms, lock):
    """Anchors before `lock` extrapolated with the lock-in fit's slope."""
    if lock < 1:
        return tracker.robust_piecewise_timing(beat_times, duration_ms)
    fit = tracker.weighted_line_fit(beat_times, lock)
    origin = fit[0] * lock + fit[1]
    prefix = []
    for index in range(lock):
        anchor_ms = origin + (index - lock) * fit[0]
        if anchor_ms <= 0.0:
            continue
        prefix.append((anchor_ms, fit[0] / 2.0))
    tail = [entry for entry in tracker.robust_piecewise_timing(beat_times, duration_ms)
            if entry[0] >= origin - 1.0]
    return prefix + tail if tail else tracker.robust_piecewise_timing(beat_times, duration_ms)


def main() -> None:
    from run_lab import evaluate_timing

    baseline = Baseline.load()
    for spec in all_songs():
        nov = load_novelty(spec, baseline.tracker.band_count, baseline.tracker.whiten_window)
        pcm, sample_rate = audio.read_wav(spec.decoded_wav)
        duration_ms = float(pcm.size * 1000.0 / sample_rate)
        reference_beats = reference.load_beats(spec.reference_beats)
        estimate = tracker.estimate_timing(
            nov, pcm, sample_rate, used_bpm(spec), duration_ms, baseline.tracker)

        beats = estimate.beat_times_ms
        lock = tracker.lock_in_index(beats)
        period = float(np.median(np.diff(beats)))
        variants = {
            "none": estimate.timing,
            "fitL": fit_at_lock_variant(beats, duration_ms, lock),
            "global": tracker.robust_piecewise_timing(beats, duration_ms, intro_fix=True),
        }
        print(f"\n== {spec.song_id}: lock={lock} period={period:.1f} ms")
        for name, timing in variants.items():
            scores, _ = evaluate_timing(timing, reference_beats, baseline.target_max_error_ms)
            cells = []
            for div in (1, 2):
                s = scores[div]
                cells.append(f"d{div} med {s['median_abs_ms']:5.1f} max {s['max_abs_ms']:6.1f} "
                             f"<=20ms {s['within_target_pct']:5.1f}%")
            print(f"   {name:<7} {cells[0]}   {cells[1]}")


if __name__ == "__main__":
    main()
