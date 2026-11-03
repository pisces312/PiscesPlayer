#!/usr/bin/env python
"""PC lab for chart-vs-song alignment.

Usage (run from the repository root):
    python tools/rhythm-lab/run_lab.py decode      # mp3 -> 44.1 kHz mono WAV
    python tools/rhythm-lab/run_lab.py reference   # cache Beat This! beats (lab venv)
    python tools/rhythm-lab/run_lab.py export      # real Kotlin pipeline -> chart JSON
    python tools/rhythm-lab/run_lab.py analyze     # exported charts vs reference
    python tools/rhythm-lab/run_lab.py evaluate    # tracker proposal vs reference
    python tools/rhythm-lab/run_lab.py propose     # legacy phase-fix sweep
    python tools/rhythm-lab/run_lab.py all         # decode, reference, export, analyze, evaluate

Nothing here is part of the app build.
"""

import argparse
import json
import os
import subprocess
from pathlib import Path

import numpy as np

from rhythm_lab import audio, beats, chart, metrics, novelty, propose, reference, tracker
from rhythm_lab.config import CHART_OUT_DIR, LAB_DIR, REPO_ROOT, Baseline

OUT_DIR = CHART_OUT_DIR
DIFFICULTIES = {"EASY": 1, "NORMAL": 2, "HARD": 4}
CHART_DIVS = (1, 2, 4)
EVAL_START_MS = 2000.0


def _novelty_cache_path(band_count: int) -> Path:
    return OUT_DIR / f"novelty_{band_count}.npz"


def load_novelty(baseline: Baseline, band_count: int | None = None) -> novelty.Novelty:
    band_count = baseline.tracker.band_count if band_count is None else band_count
    cache = _novelty_cache_path(band_count)
    if cache.is_file() and cache.stat().st_mtime >= baseline.decoded_wav.stat().st_mtime:
        data = np.load(cache)
        return novelty.Novelty(
            values=data["values"],
            times_ms=data["times_ms"],
            fps=float(data["fps"]),
            hop_ms=float(data["hop_ms"]),
            frame_offset_ms=float(data["frame_offset_ms"]),
            flux_bands=data["flux_bands"] if "flux_bands" in data else None,
        )
    pcm, sample_rate = audio.read_wav(baseline.decoded_wav)
    result = novelty.compute(
        pcm,
        sample_rate,
        band_count=band_count,
        whiten_window=baseline.tracker.whiten_window,
    )
    cache.parent.mkdir(parents=True, exist_ok=True)
    np.savez(
        cache,
        values=result.values,
        times_ms=result.times_ms,
        fps=result.fps,
        hop_ms=result.hop_ms,
        frame_offset_ms=result.frame_offset_ms,
        flux_bands=result.flux_bands,
    )
    return result


def load_global_timing() -> tuple[int, float]:
    master = chart.load_chart(OUT_DIR / "master.json")
    timing = chart.timing_points(master)
    if not timing:
        raise SystemExit("master.json has no timing; run export first")
    return int(round(timing[0][0])), timing[0][1]


def _duration_ms(nov: novelty.Novelty) -> float:
    return float(nov.times_ms[-1] + (nov.times_ms[1] - nov.times_ms[0]))


def _reference_beats(baseline: Baseline) -> np.ndarray:
    if not baseline.decoded_wav.is_file():
        raise SystemExit(f"missing {baseline.decoded_wav}; run `run_lab.py decode` first")
    return reference.load_beats(baseline.reference_beats)


def _chart_reference_ratio(timing: list[tuple[float, float]], reference_beats: np.ndarray) -> int:
    """How many reference beats make one chart beat (2 when the chart beat is an eighth note)."""
    chart_beat = float(np.median([beat_ms for _, beat_ms in timing]))
    music_beat = float(np.median(np.diff(np.sort(reference_beats))))
    if chart_beat <= 0.0:
        return 2
    return max(1, int(round(music_beat / chart_beat)))


def evaluate_timing(
    timing: list[tuple[float, float]],
    reference_beats: np.ndarray,
    target_ms: float,
    start_ms: float = EVAL_START_MS,
    stop_ms: float | None = None,
) -> tuple[dict[int, dict], dict]:
    """Grid-vs-grid error for each chart division.

    A chart division d is compared against the reference subdivided by d * ratio, so both
    grids have the same absolute spacing. The reference span is the evaluation span: the
    song's fade-out is not a musical grid and must not be extrapolated into the metric.
    """
    ref = np.sort(np.asarray(reference_beats, dtype=np.float64))
    stop = float(ref[-1]) if stop_ms is None else min(float(stop_ms), float(ref[-1]))
    start = max(float(start_ms), float(ref[0]))
    ratio = _chart_reference_ratio(timing, ref)
    fake_chart = {
        "timing": [{"timeMs": float(time), "beatMs": float(beat_ms)} for time, beat_ms in timing],
        "durationMs": stop,
    }
    scores: dict[int, dict] = {}
    for chart_div in CHART_DIVS:
        grid = chart.chart_grid_times(fake_chart, chart_div, start_ms=start, end_ms=stop)
        reference_grid = chart.reference_grid(ref, chart_div * ratio, start, stop)
        errors = chart.nearest_signed_errors(grid, reference_grid)
        scores[chart_div] = metrics.summarize(errors, target_ms)
    return scores, {"start_ms": start, "stop_ms": stop, "reference_div_ratio": ratio}


def _print_scores(label: str, scores: dict[int, dict], target_ms: float) -> None:
    for chart_div, stats in scores.items():
        print(metrics.format_summary(f"{label}/div{chart_div}", stats, target_ms))


def _evaluate_chart_file(
    path: Path,
    fallback_timing: list[tuple[float, float]],
    reference_beats: np.ndarray,
    target_ms: float,
) -> dict[int, dict]:
    loaded = chart.load_chart(path)
    timing = chart.timing_points(loaded) or fallback_timing
    return evaluate_timing(timing, reference_beats, target_ms)[0]


def cmd_decode(args) -> None:
    baseline = Baseline.load()
    audio.decode(baseline.song_path, baseline.decoded_wav, baseline.sample_rate)
    for cache in OUT_DIR.glob("novelty_*.npz"):
        cache.unlink()


def cmd_reference(args) -> None:
    baseline = Baseline.load()
    if baseline.reference_beats.is_file() and not args.force:
        beats_ms = reference.load_beats(baseline.reference_beats)
        print(f"reference cached: {baseline.reference_beats}")
        print(json.dumps(beats.period_stats(beats_ms), ensure_ascii=False))
        return
    python = LAB_DIR / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not python.is_file():
        raise SystemExit(
            f"missing {python}; create the lab venv (see tools/rhythm-lab/README.md) "
            "or point `reference.beatsFile` at an existing file"
        )
    subprocess.run([str(python), str(LAB_DIR / "compute_reference.py"), "--force"], check=True)


def cmd_export(args) -> None:
    command = [
        "cmd",
        "/c",
        "gradlew.bat",
        ":app:testDebugUnitTest",
        "--tests",
        "com.lostf1sh.pixelplayeross.data.rhythm.RhythmLabExportTest",
        "--console=plain",
    ]
    print("running:", " ".join(command))
    subprocess.run(command, cwd=REPO_ROOT, check=True)


def cmd_analyze(args) -> None:
    baseline = Baseline.load()
    reference_beats = _reference_beats(baseline)
    if not (OUT_DIR / "master.json").is_file():
        raise SystemExit(f"missing {OUT_DIR / 'master.json'}; run `run_lab.py export` first")

    print(f"reference: {json.dumps(beats.period_stats(reference_beats), ensure_ascii=False)}")
    master = chart.load_chart(OUT_DIR / "master.json")
    master_timing = chart.timing_points(master)
    print(f"master   : {len(master_timing)} timing point(s), first={master_timing[0]}")
    print("\nchart grids vs Beat This! reference")
    report = {
        "reference": beats.period_stats(reference_beats),
        "charts": {},
    }
    _print_scores(
        "master (current)",
        evaluate_timing(master_timing, reference_beats, baseline.target_max_error_ms)[0],
        baseline.target_max_error_ms,
    )
    for prefix in ("grid_global", "grid_refined", "onset"):
        for difficulty, grid_div in DIFFICULTIES.items():
            path = OUT_DIR / f"{prefix}_{difficulty}.json"
            if not path.is_file():
                continue
            label = f"{prefix}_{difficulty}"
            if prefix == "onset":
                print(f"{label:<28} onset timestamps, no grid to evaluate")
                continue
            scores = _evaluate_chart_file(
                path, master_timing, reference_beats, baseline.target_max_error_ms
            )
            _print_scores(label, scores, baseline.target_max_error_ms)
            report["charts"][label] = {str(key): value for key, value in scores.items()}

    report_path = OUT_DIR / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\nreport: {report_path}")


def cmd_evaluate(args) -> None:
    baseline = Baseline.load()
    reference_beats = _reference_beats(baseline)
    if not baseline.decoded_wav.is_file():
        raise SystemExit(f"missing {baseline.decoded_wav}; run `run_lab.py decode` first")
    pcm, sample_rate = audio.read_wav(baseline.decoded_wav)
    nov = load_novelty(baseline)
    duration_ms = _duration_ms(nov)

    estimate = tracker.estimate_timing(
        nov,
        pcm,
        sample_rate,
        baseline.expected_bpm,
        duration_ms,
        baseline.tracker,
    )
    print(json.dumps(estimate.diagnostics, ensure_ascii=False, indent=1))

    kept = estimate.beat_times_ms
    inside = kept <= reference_beats[-1]
    beat_errors = chart.nearest_signed_errors(kept[inside], reference_beats)
    print("\ntracked beats vs reference beats")
    print(
        metrics.format_summary(
            "beats",
            metrics.summarize(beat_errors, baseline.target_max_error_ms),
            baseline.target_max_error_ms,
        )
    )

    print("\ntracker timing vs reference grid")
    scores, span = evaluate_timing(estimate.timing, reference_beats, baseline.target_max_error_ms)
    _print_scores("tracker timing", scores, baseline.target_max_error_ms)
    print(
        f"evaluation span: {span['start_ms']:.0f}..{span['stop_ms']:.0f} ms, "
        f"chart beat = 1/{span['reference_div_ratio']} reference beat"
    )

    timing = [
        {"timeMs": int(round(time)), "beatMs": float(beat_ms)}
        for time, beat_ms in estimate.timing
    ]
    proposal = {
        "kind": "tracker",
        "diagnostics": estimate.diagnostics,
        "scores": {str(key): value for key, value in scores.items()},
        "timing": timing,
    }
    proposal_path = OUT_DIR / "proposed_timing.json"
    proposal_path.write_text(json.dumps(proposal, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\nproposal: {proposal_path}")


def cmd_propose(args) -> None:
    """Legacy sweep: phase correction around the app's current global timing."""
    baseline = Baseline.load()
    reference_beats = _reference_beats(baseline)
    nov = load_novelty(baseline, band_count=8)
    duration_ms = _duration_ms(nov)
    offset_ms, beat_ms = load_global_timing()
    bpm = 60000.0 / beat_ms

    candidates: list[dict] = []
    for segment in (4, 8, 16):
        for sigma in (1.5, 2.0, 2.8, 4.0):
            for confidence in (0.05, 0.15, 0.30):
                for smooth in (1, 3, 5, 7):
                    timing, diagnostics = propose.phase_piecewise_timing(
                        novn=nov.values,
                        times_ms=nov.times_ms,
                        bpm=bpm,
                        offset_ms=offset_ms,
                        duration_ms=duration_ms,
                        beats_per_segment=segment,
                        sigma_s=sigma,
                        confidence_min=confidence,
                        smooth_size=smooth,
                    )
                    scores, _ = evaluate_timing(
                        timing, reference_beats, baseline.target_max_error_ms
                    )
                    candidates.append(
                        {
                            "kind": "phase",
                            "config": {
                                "beats_per_segment": segment,
                                "sigma_s": sigma,
                                "confidence_min": confidence,
                                "smooth_size": smooth,
                            },
                            "scores": scores,
                            "timing": timing,
                            "diagnostics": diagnostics,
                        }
                    )

    for segment in (1, 2, 4, 8):
        timing, diagnostics = propose.beats_piecewise_timing(
            reference_beats,
            duration_ms,
            beats_per_segment=segment,
            bpm=bpm,
            chart_beat_ratio=0.5,
        )
        scores, _ = evaluate_timing(timing, reference_beats, baseline.target_max_error_ms)
        candidates.append(
            {
                "kind": "reference_beats",
                "config": {"beats_per_segment": segment},
                "scores": scores,
                "timing": timing,
                "diagnostics": diagnostics,
            }
        )

    def rank_key(candidate):
        worst = candidate["scores"][4]
        return (worst["max_abs_ms"], worst["p99_abs_ms"], worst["p95_abs_ms"])

    ranked = sorted(candidates, key=rank_key)
    print(f"{'rank':<4} {'kind':<16} {'segment':>7} {'sigma':>5} {'conf':>5} {'smooth':>6} "
          f"{'max':>7} {'p99':>6} {'p95':>6} {'pass@4':>6}")
    for index, candidate in enumerate(ranked[:15], start=1):
        config = candidate["config"]
        worst = candidate["scores"][4]
        print(
            f"{index:<4} {candidate['kind']:<16} "
            f"{config.get('beats_per_segment', '-'):>7} "
            f"{config.get('sigma_s', '-'):>5} "
            f"{config.get('confidence_min', '-'):>5} "
            f"{config.get('smooth_size', '-'):>6} "
            f"{worst['max_abs_ms']:>7.1f} {worst['p99_abs_ms']:>6.1f} "
            f"{worst['p95_abs_ms']:>6.1f} {worst['within_target_pct']:>5.1f}%"
        )

    best = ranked[0]
    print("\nbest candidate")
    print(json.dumps(best["diagnostics"], ensure_ascii=False, indent=1))
    _print_scores("best", best["scores"], baseline.target_max_error_ms)
    best_path = OUT_DIR / "proposed_phase_timing.json"
    best_path.write_text(
        json.dumps(
            {
                "kind": best["kind"],
                "config": best["config"],
                "diagnostics": best["diagnostics"],
                "scores": {str(key): value for key, value in best["scores"].items()},
                "timing": propose.timing_to_json(best["timing"]),
            },
            ensure_ascii=False,
            indent=1,
        ),
        encoding="utf-8",
    )
    print(f"\nproposal: {best_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=["decode", "reference", "export", "analyze", "evaluate", "propose", "all"],
    )
    parser.add_argument("--force", action="store_true", help="reference only: recompute the cache")
    args = parser.parse_args()

    if args.command in ("decode", "all"):
        cmd_decode(args)
    if args.command in ("reference", "all"):
        cmd_reference(args)
    if args.command in ("export", "all"):
        cmd_export(args)
    if args.command in ("analyze", "all"):
        cmd_analyze(args)
    if args.command in ("evaluate", "all"):
        cmd_evaluate(args)
    if args.command == "propose":
        cmd_propose(args)


if __name__ == "__main__":
    main()
