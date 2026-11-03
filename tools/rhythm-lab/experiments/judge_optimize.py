"""Judge-based per-song optimization: what the phone would do with Beat This! on board.

For each lab song, sweep tracker candidates (tightness x fit window), pick the one with the
smallest grid-vs-reference error (tune_tracker.py's rank), then vote section parity against
the reference beats (the judge version of E2 in selfscore.py). Reports default vs optimized
at div 1/2/4.

Selection and voting use the same judge as the evaluation, so the numbers are an upper bound
of what judge-based optimization can extract per song. Reference artifacts are guarded: a
segment with fewer than 4 reference beats is not voted on.

Usage (repository root):
    python tools/rhythm-lab/experiments/judge_optimize.py
"""

import json
from dataclasses import replace

import numpy as np

from selfscore import (
    beat_circle_phases,
    all_songs,
    load_novelty,
    used_bpm,
)
from rhythm_lab import audio, reference, tracker
from rhythm_lab.config import Baseline, CHART_OUT_DIR

TIGHTNESS = (200.0, 400.0, 600.0, 800.0, 1200.0)
FIT_HALF_WINDOW = (6, 10, 16)
DEFAULT_TIGHTNESS = 600.0
DEFAULT_FIT_HALF_WINDOW = 10
SEGMENT_BEATS = 8
FLIP_THRESHOLD = -0.1
MIN_REF_BEATS_PER_SEGMENT = 4
TARGET_MS = 20.0


def rank_key(scores: dict[int, dict]) -> tuple:
    worst = scores[4]
    return (worst["max_abs_ms"], worst["p99_abs_ms"], worst["p95_abs_ms"])


def judge_parity_vote(timing, beat_times, reference_beats):
    """Flip each 8-beat segment onto the phase the reference beats prefer.

    Returns (flipped_timing, flip_report). Segments with too few reference beats (gaps,
    break sections) are left alone: the judge has no evidence there.
    """
    ref = np.sort(np.asarray(reference_beats, dtype=np.float64))
    flipped = list(timing)
    report = []
    for start in range(0, beat_times.size, SEGMENT_BEATS):
        seg = beat_times[start:start + SEGMENT_BEATS]
        if seg.size < 4:
            continue
        window = (float(seg[0]), float(seg[-1]))
        ref_mask = (ref >= window[0]) & (ref <= window[1])
        if ref_mask.sum() < MIN_REF_BEATS_PER_SEGMENT:
            report.append({"start_ms": window[0], "end_ms": window[1],
                           "bias": None, "flip": False, "skipped": True})
            continue
        phases = beat_circle_phases(timing, ref[ref_mask])
        bias = float(np.mean(np.cos(2.0 * np.pi * phases)))
        flip = bias < FLIP_THRESHOLD
        if flip:
            flipped = [
                (t + b / 2.0 if window[0] <= t <= window[1] else t, b)
                for t, b in flipped
            ]
        report.append({"start_ms": window[0], "end_ms": window[1],
                       "bias": bias, "flip": flip, "skipped": False})
    return flipped, report


def format_scores(scores: dict[int, dict]) -> str:
    cells = []
    for div in (1, 2, 4):
        s = scores[div]
        cells.append(f"d{div} {s['median_abs_ms']:5.1f}/{s['p95_abs_ms']:5.1f}/"
                     f"{s['max_abs_ms']:6.1f}/{s['within_target_pct']:5.1f}%")
    return "  ".join(cells)


def main() -> None:
    from run_lab import evaluate_timing

    baseline = Baseline.load()
    summary = {}
    for spec in all_songs():
        nov = load_novelty(spec, baseline.tracker.band_count, baseline.tracker.whiten_window)
        pcm, sample_rate = audio.read_wav(spec.decoded_wav)
        duration_ms = float(pcm.size * 1000.0 / sample_rate)
        bpm = used_bpm(spec)
        reference_beats = reference.load_beats(spec.reference_beats)

        candidates = []
        for tightness in TIGHTNESS:
            for half_window in FIT_HALF_WINDOW:
                cfg = replace(baseline.tracker, dp_tightness=tightness,
                              fit_half_window=half_window)
                try:
                    estimate = tracker.estimate_timing(
                        nov, pcm, sample_rate, bpm, duration_ms, cfg)
                except ValueError:
                    continue
                scores, _ = evaluate_timing(estimate.timing, reference_beats, TARGET_MS)
                candidates.append({
                    "tightness": tightness,
                    "fit_half_window": half_window,
                    "timing": estimate.timing,
                    "beats": estimate.beat_times_ms,
                    "scores": scores,
                })
        default = next(c for c in candidates
                       if c["tightness"] == DEFAULT_TIGHTNESS
                       and c["fit_half_window"] == DEFAULT_FIT_HALF_WINDOW)
        picked = min(candidates, key=lambda c: rank_key(c["scores"]))
        flipped_timing, flips = judge_parity_vote(picked["timing"], picked["beats"], reference_beats)
        vote_scores, _ = evaluate_timing(flipped_timing, reference_beats, TARGET_MS)

        n_flips = sum(1 for f in flips if f["flip"])
        print(f"\n== {spec.song_id}")
        print(f"   default (600/10) : {format_scores(default['scores'])}")
        picked_label = f"picked ({picked['tightness']:.0f}/{picked['fit_half_window']})"
        if picked is default:
            picked_label = "picked = default"
        print(f"   {picked_label:<18}: {format_scores(picked['scores'])}")
        print(f"   + parity vote    : {format_scores(vote_scores)}   "
              f"({n_flips} flip(s))")
        for f in flips:
            if f["flip"]:
                print(f"      flip {f['start_ms']/1000:.1f}-{f['end_ms']/1000:.1f}s "
                      f"bias={f['bias']:+.2f}")
        summary[spec.song_id] = {
            "default": default["scores"],
            "picked": {"tightness": picked["tightness"],
                       "fit_half_window": picked["fit_half_window"],
                       "scores": picked["scores"]},
            "voted": vote_scores,
            "flips": flips,
        }

    print("\n== totals (div 4): lines over 20 ms")
    total_over_default = 0
    total_over_voted = 0
    for song_id, item in summary.items():
        for div in (1, 2, 4):
            n = item["default"][div]["count"]
            over_default = round(n * (100.0 - item["default"][div]["within_target_pct"]) / 100.0)
            over_voted = round(n * (100.0 - item["voted"][div]["within_target_pct"]) / 100.0)
            if div == 4:
                total_over_default += over_default
                total_over_voted += over_voted
        d, v = item["default"][4], item["voted"][4]
        print(f"  {song_id:<18} div4 max {d['max_abs_ms']:7.1f} -> {v['max_abs_ms']:7.1f}   "
              f"<=20ms {d['within_target_pct']:5.1f}% -> {v['within_target_pct']:5.1f}%")
    print(f"  div4 over-target lines total: {total_over_default} -> {total_over_voted}")

    report_path = CHART_OUT_DIR / "experiments" / "judge_optimize.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(summary, ensure_ascii=False, indent=1, default=str),
                           encoding="utf-8")
    print(f"\nreport: {report_path}")


if __name__ == "__main__":
    main()
