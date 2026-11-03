"""Production-portable timing estimate.

Pipeline: 64-band novelty -> Ellis DP beat track at half the detected tempo -> drop beats
that only sit in a silent tail -> robust local-linear fit, one timing point per anchor
beats. The chart's beat is half a music beat, so each anchor's `beatMs` is half the fitted
music-beat period.

This is the path that can be ported to Kotlin: it needs the same FFT front end the app
already has, one O(frames * 60) DP pass and one tricube-weighted least-squares fit
over a +/-10 beat window per anchor.
"""

from dataclasses import dataclass, field

import numpy as np

from . import beats, novelty

DEFAULT_BAND_COUNT = 64
DEFAULT_WHITEN_WINDOW = 32
DEFAULT_DP_TIGHTNESS = 600.0
DEFAULT_REFINE_RADIUS = 2
DEFAULT_TAIL_RMS_FRACTION = 0.25
DEFAULT_ANCHOR_EVERY = 1
DEFAULT_FIT_HALF_WINDOW = 10
DEFAULT_FIT_RESIDUAL_MS = 45.0


@dataclass(frozen=True)
class TrackerConfig:
    band_count: int = DEFAULT_BAND_COUNT
    whiten_window: int = DEFAULT_WHITEN_WINDOW
    dp_tightness: float = DEFAULT_DP_TIGHTNESS
    refine_radius: int = DEFAULT_REFINE_RADIUS
    tail_rms_fraction: float = DEFAULT_TAIL_RMS_FRACTION
    anchor_every: int = DEFAULT_ANCHOR_EVERY
    fit_half_window: int = DEFAULT_FIT_HALF_WINDOW
    fit_residual_ms: float = DEFAULT_FIT_RESIDUAL_MS


@dataclass(frozen=True)
class TimingEstimate:
    timing: list[tuple[float, float]]
    beat_times_ms: np.ndarray
    tracker_beats: int
    kept_beats: int
    diagnostics: dict = field(default_factory=dict)


def local_rms(pcm: np.ndarray, sample_rate: int, time_ms: float, window_s: float = 0.05) -> float:
    center = int(time_ms / 1000.0 * sample_rate)
    half = int(window_s * sample_rate)
    low = max(0, center - half)
    high = min(pcm.size, center + half)
    if high <= low:
        return 0.0
    return float(np.sqrt(np.mean(pcm[low:high].astype(np.float64) ** 2)))


def trim_silent_tail(
    beat_times_ms: np.ndarray,
    pcm: np.ndarray,
    sample_rate: int,
    rms_fraction: float = DEFAULT_TAIL_RMS_FRACTION,
) -> np.ndarray:
    """Drop trailing beats that only sit in reverb/fade-out, not in music.

    A trailing beat is kept while its local RMS reaches `rms_fraction` of the song's
    median 100 ms RMS. Only the tail is examined, so a quiet intro cannot be cut.
    """
    beat_times = np.asarray(beat_times_ms, dtype=np.float64)
    frame = int(0.1 * sample_rate)
    if frame <= 0 or beat_times.size == 0 or pcm.size < frame:
        return beat_times
    count = pcm.size // frame
    rms = np.sqrt(np.mean(pcm[: count * frame].astype(np.float64).reshape(count, frame) ** 2, axis=1))
    median = float(np.median(rms))
    if median <= 0.0:
        return beat_times
    keep = beat_times.size
    while keep > 1 and local_rms(pcm, sample_rate, beat_times[keep - 1]) < rms_fraction * median:
        keep -= 1
    return beat_times[:keep]


def robust_piecewise_timing(
    beat_times_ms: np.ndarray,
    duration_ms: float,
    anchor_every: int = DEFAULT_ANCHOR_EVERY,
    half_window: int = DEFAULT_FIT_HALF_WINDOW,
    residual_ms: float = DEFAULT_FIT_RESIDUAL_MS,
) -> list[tuple[float, float]]:
    """One timing point every `anchor_every` beats, from a trimmed local-linear fit.

    The fit is in beat-index space: `time = slope * index + intercept`. Tricube weights
    centred on the anchor keep a distant beat from dragging the local tempo, and trimming
    residual outliers keeps a single misplaced beat from bending the grid.
    """
    beat_times = np.asarray(beat_times_ms, dtype=np.float64)
    if beat_times.size < 2:
        raise ValueError("need at least two tracked beats")
    indices = np.arange(beat_times.size)
    timing: list[tuple[float, float]] = []
    for anchor in range(0, beat_times.size, anchor_every):
        low = max(0, anchor - half_window)
        high = min(beat_times.size, anchor + half_window + 1)
        x = indices[low:high]
        y = beat_times[low:high]
        weights = np.clip(1.0 - np.abs(x - anchor) / (half_window + 1.0), 1e-3, None) ** 3
        keep = np.ones(x.size, dtype=bool)
        for _ in range(4):
            if int(keep.sum()) < 2:
                break
            slope, intercept = np.polyfit(x[keep], y[keep], 1, w=weights[keep])
            residual = np.abs(y - (slope * x + intercept))
            cutoff = max(residual_ms, 3.0 * float(np.median(residual[keep])))
            keep = residual < cutoff
        if int(keep.sum()) < 2:
            keep[:] = True
        slope, intercept = np.polyfit(x[keep], y[keep], 1, w=weights[keep])
        if not np.isfinite(slope) or slope <= 0.0:
            continue
        anchor_ms = float(slope * anchor + intercept)
        if anchor_ms <= 0.0 or anchor_ms >= duration_ms:
            continue
        if timing and anchor_ms <= timing[-1][0] + 1.0:
            continue
        timing.append((anchor_ms, float(slope) / 2.0))
    if not timing:
        raise ValueError("no usable timing anchors")
    return timing


def compute_novelty(
    pcm: np.ndarray,
    sample_rate: int,
    config: TrackerConfig = TrackerConfig(),
) -> novelty.Novelty:
    return novelty.compute(
        pcm,
        sample_rate,
        band_count=config.band_count,
        whiten_window=config.whiten_window,
    )


def estimate_timing(
    nov: novelty.Novelty,
    pcm: np.ndarray,
    sample_rate: int,
    bpm: float,
    duration_ms: float,
    config: TrackerConfig = TrackerConfig(),
) -> TimingEstimate:
    """Track music beats at `bpm / 2` and turn them into chart timing points."""
    tracker_beats = beats.beat_track(
        nov.values,
        nov.times_ms,
        nov.fps,
        start_bpm=bpm / 2.0,
        tightness=config.dp_tightness,
        refine_radius=config.refine_radius,
    )
    kept = trim_silent_tail(tracker_beats, pcm, sample_rate, config.tail_rms_fraction)
    timing = robust_piecewise_timing(
        kept,
        duration_ms,
        anchor_every=config.anchor_every,
        half_window=config.fit_half_window,
        residual_ms=config.fit_residual_ms,
    )
    diagnostics = {
        "band_count": int(nov.flux_bands.shape[1]) if nov.flux_bands is not None else config.band_count,
        "dp_tightness": config.dp_tightness,
        "start_bpm": bpm / 2.0,
        "tracker_beats": int(tracker_beats.size),
        "kept_beats": int(kept.size),
        "dropped_tail_beats": int(tracker_beats.size - kept.size),
        "anchors": len(timing),
        "first_beat_ms": float(kept[0]),
        "last_beat_ms": float(kept[-1]),
        "median_beat_ms": float(np.median(np.diff(kept))) if kept.size > 1 else 0.0,
    }
    return TimingEstimate(
        timing=timing,
        beat_times_ms=kept,
        tracker_beats=int(tracker_beats.size),
        kept_beats=int(kept.size),
        diagnostics=diagnostics,
    )
