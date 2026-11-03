"""Can the tracker pick its own best parameters without an external reference?

A phone-side "optimize parameters after chart generation" pass has no Beat This! to score
against, so its objective must be self-consistency: a candidate grid is good when it
explains the song's own strong onsets. This experiment validates that objective against
the truth on the lab's six songs (baseline + validation.json), where the Beat This!
reference is available as judge.

E1: sweep tightness x fit window, rank candidates by onset agreement on the div-2 grid,
    and compare that ranking with the reference-based ranking. Parity is not part of E1:
    a div-2 grid shifted by half a chart beat is the same line set, so that score is
    structurally blind to parity.

E2: section-wise parity vote on the default output, measured on the div-1 beat circle:
    for each 8-beat segment compute C = sum(w * cos(2*pi*phase)) / sum(w) of the song's
    own onsets, where phase is the position inside the current chart beat. C > 0 means the
    onsets sit on the grid's beat, C < 0 means they sit on the off-beat; flip the segment's
    grid by half a chart beat when C is clearly negative, then re-check against the
    reference.

Usage (repository root):
    python tools/rhythm-lab/experiments/selfscore.py
"""

import json
import sys
from dataclasses import replace

import numpy as np
from scipy.stats import spearmanr

from _shared import CHART_OUT_DIR, EXPERIMENT_OUT
from rhythm_lab import audio, chart, metrics, novelty, reference, tracker
from rhythm_lab.config import Baseline, SongSpec, load_validation_songs

TIGHTNESS = (200.0, 400.0, 600.0, 800.0, 1200.0)
FIT_HALF_WINDOW = (6, 10, 16)
KERNEL_SIGMA_MS = 30.0
SEGMENT_BEATS = 8
FLIP_THRESHOLD = -0.1
TARGET_MS = 20.0


def all_songs() -> list[SongSpec]:
    baseline = Baseline.load()
    songs = [
        SongSpec(
            song_id="baseline",
            song_path=baseline.song_path,
            decoded_wav=baseline.decoded_wav,
            reference_beats=baseline.reference_beats,
            chart_out_dir=CHART_OUT_DIR,
        )
    ]
    songs += [spec for spec in load_validation_songs() if spec.decoded_wav.is_file()]
    return songs


def load_novelty(spec: SongSpec, band_count: int, whiten_window: int) -> novelty.Novelty:
    cache = spec.chart_out_dir / f"novelty_{band_count}.npz"
    if cache.is_file():
        data = np.load(cache)
        return novelty.Novelty(
            values=data["values"],
            times_ms=data["times_ms"],
            fps=float(data["fps"]),
            hop_ms=float(data["hop_ms"]),
            frame_offset_ms=float(data["frame_offset_ms"]),
            flux_bands=data["flux_bands"] if "flux_bands" in data else None,
        )
    pcm, sample_rate = audio.read_wav(spec.decoded_wav)
    result = novelty.compute(pcm, sample_rate, band_count=band_count, whiten_window=whiten_window)
    cache.parent.mkdir(parents=True, exist_ok=True)
    np.savez(cache, values=result.values, times_ms=result.times_ms, fps=result.fps,
             hop_ms=result.hop_ms, frame_offset_ms=result.frame_offset_ms, flux_bands=result.flux_bands)
    return result


def master_onsets(spec: SongSpec) -> tuple[np.ndarray, np.ndarray]:
    """(times, weights): exactly what a phone-side optimizer would see after generation."""
    master = chart.load_chart(spec.chart_out_dir / "master.json")
    times = np.asarray([float(n["timeMs"]) for n in master["notes"]], dtype=np.float64)
    weights = np.asarray([float(n.get("strength", 1.0)) for n in master["notes"]], dtype=np.float64)
    return times, weights


def used_bpm(spec: SongSpec) -> float:
    analysis = json.loads((spec.chart_out_dir / "analysis.json").read_text(encoding="utf-8"))
    return float(analysis["usedBpm"])


def fake_chart(timing: list[tuple[float, float]], duration_ms: float) -> dict:
    return {
        "timing": [{"timeMs": t, "beatMs": b} for t, b in timing],
        "durationMs": duration_ms,
    }


def nearest_distances(query: np.ndarray, lines: np.ndarray) -> np.ndarray:
    if lines.size == 0:
        return np.full_like(query, np.inf)
    index = np.clip(np.searchsorted(lines, query), 1, lines.size - 1)
    left, right = lines[index - 1], lines[index]
    return np.minimum(np.abs(query - left), np.abs(right - query))


def self_score(timing, onset_times, onset_weights, duration_ms) -> float:
    """Smooth onset agreement on the div-2 grid: sum(w * exp(-d^2 / 2*sigma^2)) / sum(w).

    Blind to parity by construction (a half-beat shift maps the div-2 line set onto
    itself), so it only ranks tempo/fit choices.
    """
    lines = chart.chart_grid_times(fake_chart(timing, duration_ms), 2, start_ms=0.0, end_ms=duration_ms)
    distances = nearest_distances(onset_times, lines)
    if onset_weights.sum() <= 0:
        return 0.0
    kernel = np.exp(-(distances ** 2) / (2.0 * KERNEL_SIGMA_MS ** 2))
    return float(np.sum(onset_weights * kernel) / np.sum(onset_weights))


def beat_circle_phases(timing, onset_times) -> np.ndarray:
    """Each onset's position inside its local chart beat, in [0, 1)."""
    anchors = np.asarray([t for t, _ in timing], dtype=np.float64)
    beats = np.asarray([b for _, b in timing], dtype=np.float64)
    index = np.clip(np.searchsorted(anchors, onset_times, side="right") - 1, 0, anchors.size - 1)
    local = (onset_times - anchors[index]) / beats[index]
    return local - np.floor(local)


def phase_bias(timing, onset_times, onset_weights) -> float:
    """C = sum(w * cos(2*pi*phase)) / sum(w) on the chart-beat circle.

    C near +1: onsets sit on the grid's beat lines; near -1: on the off-beats (the grid is
    half a beat off); near 0: no usable phase evidence.
    """
    if onset_weights.sum() <= 0:
        return 0.0
    phases = beat_circle_phases(timing, onset_times)
    return float(np.sum(onset_weights * np.cos(2.0 * np.pi * phases)) / np.sum(onset_weights))


def evaluate_against_reference(timing, reference_beats, chart_divs=(1, 2)) -> dict[int, dict]:
    from run_lab import evaluate_timing

    scores, _ = evaluate_timing(timing, reference_beats, TARGET_MS)
    return {div: scores[div] for div in chart_divs}


def run_e1(spec, nov, pcm, sample_rate, onset_times, onset_weights, reference_beats) -> dict:
    duration_ms = float(pcm.size * 1000.0 / sample_rate)
    bpm = used_bpm(spec)
    base = Baseline.load().tracker
    rows = []
    for tightness in TIGHTNESS:
        for half_window in FIT_HALF_WINDOW:
            cfg = replace(base, dp_tightness=tightness, fit_half_window=half_window)
            try:
                estimate = tracker.estimate_timing(nov, pcm, sample_rate, bpm, duration_ms, cfg)
            except ValueError:
                continue
            timing = estimate.timing
            score = self_score(timing, onset_times, onset_weights, duration_ms)
            errors = evaluate_against_reference(timing, reference_beats)
            rows.append({
                "tightness": tightness,
                "fit_half_window": half_window,
                "self_score": score,
                "ref_div1_max": errors[1]["max_abs_ms"],
                "ref_div1_med": errors[1]["median_abs_ms"],
                "ref_div2_max": errors[2]["max_abs_ms"],
                "ref_div2_med": errors[2]["median_abs_ms"],
            })
    return {"rows": rows}


def run_e2(spec, nov, pcm, sample_rate, onset_times, onset_weights, reference_beats) -> dict:
    """Section-wise half-beat parity vote on the chart-beat circle."""
    duration_ms = float(pcm.size * 1000.0 / sample_rate)
    bpm = used_bpm(spec)
    estimate = tracker.estimate_timing(nov, pcm, sample_rate, bpm, duration_ms, Baseline.load().tracker)
    timing = estimate.timing
    beats = estimate.beat_times_ms
    lines = chart.chart_grid_times(fake_chart(timing, duration_ms), 1, start_ms=0.0, end_ms=duration_ms)
    flipped = lines.copy()
    segments = []
    for start in range(0, beats.size, SEGMENT_BEATS):
        seg = beats[start:start + SEGMENT_BEATS]
        if seg.size < 4:
            continue
        window = (float(seg[0]), float(seg[-1]))
        onsets_mask = (onset_times >= window[0]) & (onset_times <= window[1])
        if onsets_mask.sum() < 4:
            continue
        seg_lines_mask = (lines >= window[0]) & (lines <= window[1])
        if not seg_lines_mask.any():
            continue
        bias = phase_bias(timing, onset_times[onsets_mask], onset_weights[onsets_mask])
        flip = bias < FLIP_THRESHOLD
        local_half = float(np.median([b for t, b in timing
                                      if window[0] <= t <= window[1]] or [0.0])) / 2.0
        if flip and local_half > 0.0:
            flipped[seg_lines_mask] = flipped[seg_lines_mask] + local_half
        segments.append({
            "start_ms": window[0],
            "end_ms": window[1],
            "phase_bias": bias,
            "flip": flip,
        })

    ref = np.sort(np.asarray(reference_beats, dtype=np.float64))
    chart_beat = float(np.median([b for _, b in timing]))
    ratio = max(1, int(round(float(np.median(np.diff(ref))) / chart_beat))) if chart_beat > 0 else 1
    stop = float(ref[-1])
    start = max(2000.0, float(ref[0]))
    ref_grid = chart.reference_grid(ref, ratio, start, stop)
    before = chart.nearest_signed_errors(lines[(lines >= start) & (lines <= stop)], ref_grid)
    after = chart.nearest_signed_errors(flipped[(flipped >= start) & (flipped <= stop)], ref_grid)
    return {
        "segments": segments,
        "before": metrics.summarize(before, TARGET_MS),
        "after": metrics.summarize(after, TARGET_MS),
    }


def main() -> None:
    report: dict = {"e1": {}, "e2": {}}
    print("E1: onset-agreement self-score vs the Beat This! judge, 15 candidates per song")
    for spec in all_songs():
        baseline = Baseline.load()
        nov = load_novelty(spec, baseline.tracker.band_count, baseline.tracker.whiten_window)
        pcm, sample_rate = audio.read_wav(spec.decoded_wav)
        onset_times, onset_weights = master_onsets(spec)
        reference_beats = reference.load_beats(spec.reference_beats)

        e1 = run_e1(spec, nov, pcm, sample_rate, onset_times, onset_weights, reference_beats)
        rows = e1["rows"]
        by_self = sorted(rows, key=lambda r: -r["self_score"])
        by_ref = sorted(rows, key=lambda r: (r["ref_div2_max"], r["ref_div2_med"]))
        default = next(
            r for r in rows
            if r["tightness"] == 600.0 and r["fit_half_window"] == 10
        )
        corr_med, _ = spearmanr(
            [r["self_score"] for r in rows], [-r["ref_div2_med"] for r in rows]
        )
        corr_max, _ = spearmanr(
            [r["self_score"] for r in rows], [-r["ref_div2_max"] for r in rows]
        )
        best = by_self[0]
        truth = by_ref[0]
        print(f"\n== {spec.song_id}: spearman(self, -ref_med)={corr_med:+.2f} (self, -ref_max)={corr_max:+.2f}")
        print(f"   self-pick : tight={best['tightness']:.0f} win={best['fit_half_window']}  "
              f"self={best['self_score']:.3f}  ref div2 med/max={best['ref_div2_med']:.1f}/{best['ref_div2_max']:.1f}")
        print(f"   ref-best  : tight={truth['tightness']:.0f} win={truth['fit_half_window']}  "
              f"self={truth['self_score']:.3f}  ref div2 med/max={truth['ref_div2_med']:.1f}/{truth['ref_div2_max']:.1f}")
        print(f"   default   : self={default['self_score']:.3f}  "
              f"ref div2 med/max={default['ref_div2_med']:.1f}/{default['ref_div2_max']:.1f}")
        report["e1"][spec.song_id] = {
            "spearman_med": float(corr_med),
            "spearman_max": float(corr_max),
            "self_pick": best,
            "ref_best": truth,
            "default": default,
            "rows": rows,
        }

        e2 = run_e2(spec, nov, pcm, sample_rate, onset_times, onset_weights, reference_beats)
        flips = [s for s in e2["segments"] if s["flip"]]
        print(f"   E2 parity vote: {len(flips)}/{len(e2['segments'])} segments flipped  "
              f"div1 max {e2['before']['max_abs_ms']:.1f} -> {e2['after']['max_abs_ms']:.1f}  "
              f"med {e2['before']['median_abs_ms']:.1f} -> {e2['after']['median_abs_ms']:.1f}")
        for seg in flips:
            print(f"      flip {seg['start_ms']/1000:.1f}-{seg['end_ms']/1000:.1f}s "
                  f"phase_bias={seg['phase_bias']:+.2f}")
        report["e2"][spec.song_id] = e2

    EXPERIMENT_OUT.mkdir(parents=True, exist_ok=True)
    out = EXPERIMENT_OUT / "selfscore.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\nreport: {out}")


if __name__ == "__main__":
    main()
