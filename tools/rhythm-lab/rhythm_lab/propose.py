"""Candidate piecewise timing generators.

The production grid snaps notes to `RhythmTimingPoint` anchors. That makes every anchor an
exact beat line, so a timing list whose anchors follow the audio gives an arbitrarily tight
chart. Two candidates are built here:

- `phase_piecewise_timing` uses the windowed complex-demodulation phase of the novelty
  curve (the same information the app already holds while generating the master);
- `beats_piecewise_timing` uses the independent DP beat sequence, to show the ceiling.
"""

import numpy as np

from .tempo import demodulate_phase


def _median_filter(values: np.ndarray, size: int) -> np.ndarray:
    if size <= 1 or values.size < size:
        return values
    pad = size // 2
    padded = np.pad(values, pad, mode="edge")
    return np.asarray([np.median(padded[i:i + size]) for i in range(values.size)])


def _smooth(values: np.ndarray, size: int) -> np.ndarray:
    if size <= 1 or values.size < size:
        return values
    kernel = np.ones(size, dtype=np.float64) / size
    return np.convolve(values, kernel, mode="same")


def _fill_missing(values: np.ndarray) -> np.ndarray:
    valid = np.isfinite(values)
    if valid.all():
        return values.copy()
    if not valid.any():
        return np.zeros_like(values)
    index = np.arange(values.size, dtype=np.float64)
    filled = values.copy()
    filled[~valid] = np.interp(index[~valid], index[valid], values[valid])
    return filled


def _continuity_clamp(values: np.ndarray, max_step_ms: float) -> np.ndarray:
    clamped = values.copy()
    for index in range(1, clamped.size):
        clamped[index] = np.clip(
            clamped[index],
            clamped[index - 1] - max_step_ms,
            clamped[index - 1] + max_step_ms,
        )
    return clamped


def phase_piecewise_timing(
    novn: np.ndarray,
    times_ms: np.ndarray,
    bpm: float,
    offset_ms: float,
    duration_ms: float,
    beats_per_segment: int = 8,
    sigma_s: float = 2.0,
    confidence_min: float = 0.15,
    smooth_size: int = 5,
    max_correction_ms: float | None = None,
    max_step_ms: float = 25.0,
    iterations: int = 2,
) -> tuple[list[tuple[int, float]], dict]:
    """Anchors every `beats_per_segment` beats, corrected by the audio beat phase."""
    period_ms = 60000.0 / bpm
    if max_correction_ms is None:
        max_correction_ms = period_ms / 4.0

    beat_count = max(1, int(np.floor((duration_ms - offset_ms) / period_ms)))
    anchor_beats = list(range(0, beat_count + 1, beats_per_segment))
    if anchor_beats[-1] != beat_count:
        anchor_beats.append(beat_count)
    anchor_beats_array = np.asarray(anchor_beats, dtype=np.float64)
    predicted = offset_ms + anchor_beats_array * period_ms

    errors = np.zeros_like(predicted)
    confidence = np.ones_like(predicted)
    for _ in range(max(1, iterations)):
        errors, confidence = demodulate_phase(
            novn, times_ms, bpm, offset_ms, predicted + errors, sigma_s
        )
        errors = np.clip(errors, -max_correction_ms, max_correction_ms)

    gated = np.where(confidence >= confidence_min, errors, np.nan)
    filled = _fill_missing(gated)
    smoothed = _smooth(_median_filter(filled, smooth_size), smooth_size)
    smoothed = _continuity_clamp(smoothed, max_step_ms)
    anchors = np.round(predicted + smoothed).astype(np.int64)

    timing: list[tuple[int, float]] = []
    for index in range(anchors.size - 1):
        beats = anchor_beats[index + 1] - anchor_beats[index]
        beat_ms = (anchors[index + 1] - anchors[index]) / beats
        if beat_ms <= 0.0:
            beat_ms = period_ms
        timing.append((int(anchors[index]), float(beat_ms)))

    diagnostics = {
        "anchor_count": len(timing),
        "beats_per_segment": beats_per_segment,
        "sigma_s": sigma_s,
        "confidence_min": confidence_min,
        "smooth_size": smooth_size,
        "max_correction_ms": max_correction_ms,
        "median_correction_ms": float(np.median(smoothed)),
        "max_abs_correction_ms": float(np.abs(smoothed).max()),
        "median_confidence": float(np.median(confidence)),
        "min_confidence": float(confidence.min()),
        "anchor_beats": anchor_beats,
    }
    return timing, diagnostics


def beats_piecewise_timing(
    beats_ms: np.ndarray,
    duration_ms: float,
    beats_per_segment: int = 1,
    bpm: float | None = None,
    chart_beat_ratio: float = 1.0,
) -> tuple[list[tuple[int, float]], dict]:
    """Anchors copied straight from the DP beat sequence (the accuracy ceiling)."""
    beats = np.sort(np.asarray(beats_ms, dtype=np.float64))
    beats = beats[(beats >= 0.0) & (beats <= duration_ms)]
    if beats.size < 2:
        raise ValueError("not enough reference beats")
    period = float(np.median(np.diff(beats)))
    if bpm is None:
        bpm = 60000.0 / period

    timing: list[tuple[int, float]] = []
    index = 0
    while index + beats_per_segment < beats.size:
        start = beats[index]
        stop = beats[index + beats_per_segment]
        beat_ms = (stop - start) / beats_per_segment * chart_beat_ratio
        timing.append((int(round(start)), float(beat_ms)))
        index += beats_per_segment
    last = beats[-1]
    if timing and last - timing[-1][0] > 0.5 * period:
        segment_beats = max(1, int(round((last - timing[-1][0]) / period)))
        beat_ms = (last - timing[-1][0]) / segment_beats * chart_beat_ratio
        timing.append((int(round(last)), float(beat_ms)))

    diagnostics = {
        "anchor_count": len(timing),
        "beats_per_segment": beats_per_segment,
        "chart_beat_ratio": chart_beat_ratio,
        "median_beat_ms": period,
        "bpm": float(60000.0 / period),
        "last_anchor_ms": float(timing[-1][0]) if timing else 0.0,
    }
    return timing, diagnostics


def timing_to_json(timing: list[tuple[int, float]]) -> list[dict]:
    return [{"timeMs": int(round(time)), "beatMs": float(beat_ms)} for time, beat_ms in timing]


def timing_beat_lengths(timing: list[tuple[int, float]]) -> np.ndarray:
    return np.asarray([beat for _, beat in timing], dtype=np.float64)
