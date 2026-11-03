"""Independent beat reference: an Ellis-style dynamic-programming beat tracker.

The tracker is deliberately a different family from the complex-demodulation phase used by
the proposed timing generator. If both agree, the reference is not an artefact of one
estimator. Beats are frame-quantised by the 512-sample hop and then refined to the local
novelty peak with a parabolic fit.
"""

import numpy as np


def dp_beat_track(
    novn: np.ndarray,
    fps: float,
    start_bpm: float,
    tightness: float = 100.0,
) -> np.ndarray:
    local = novn.astype(np.float64)
    local = local / (float(np.std(local)) + 1e-9)
    frame_count = local.size
    period = 60.0 * fps / start_bpm
    tau_min = max(1, int(round(period / 2.0)))
    tau_max = max(tau_min + 1, int(round(period * 2.0)))

    cumulative = local.copy()
    backlink = np.full(frame_count, -1, dtype=np.int32)
    for frame in range(tau_min, frame_count):
        low = max(0, frame - tau_max)
        high = frame - tau_min
        if high < low:
            continue
        taus = (frame - np.arange(low, high + 1, dtype=np.float64))
        scores = cumulative[low:high + 1] - tightness * np.log(taus / period) ** 2
        best = int(np.argmax(scores))
        if scores[best] > 0.0:
            cumulative[frame] = local[frame] + scores[best]
            backlink[frame] = low + best

    search_from = max(0, frame_count - int(round(2.0 * period)))
    frame = search_from + int(np.argmax(cumulative[search_from:]))
    beats: list[int] = []
    while frame >= 0:
        beats.append(frame)
        frame = int(backlink[frame])
    beats.reverse()
    return np.asarray(beats, dtype=np.float64)


def refine_to_peaks(novn: np.ndarray, frames: np.ndarray, radius: int = 2) -> np.ndarray:
    """Move every frame-quantised beat to the local novelty maximum and interpolate it."""
    refined = np.empty(frames.size)
    for index, frame in enumerate(frames):
        base = int(round(frame))
        low = max(0, base - radius)
        high = min(novn.size, base + radius + 1)
        peak = low + int(np.argmax(novn[low:high]))
        delta = 0.0
        if 0 < peak < novn.size - 1:
            y0, y1, y2 = (float(v) for v in novn[peak - 1:peak + 2])
            denominator = y0 - 2.0 * y1 + y2
            if abs(denominator) > 1e-12:
                delta = float(np.clip(0.5 * (y0 - y2) / denominator, -0.5, 0.5))
        refined[index] = peak + delta
    return refined


def beat_track(
    novn: np.ndarray,
    times_ms: np.ndarray,
    fps: float,
    start_bpm: float,
    tightness: float = 100.0,
    refine_radius: int = 2,
) -> np.ndarray:
    frames = dp_beat_track(novn, fps, start_bpm, tightness=tightness)
    refined = refine_to_peaks(novn, frames, radius=refine_radius)
    return np.asarray(times_ms[0] + refined * (times_ms[1] - times_ms[0]))


def period_stats(beats_ms: np.ndarray) -> dict:
    if beats_ms.size < 2:
        return {"beats": int(beats_ms.size)}
    intervals = np.diff(beats_ms)
    return {
        "beats": int(beats_ms.size),
        "first_ms": float(beats_ms[0]),
        "last_ms": float(beats_ms[-1]),
        "median_beat_ms": float(np.median(intervals)),
        "bpm": float(60000.0 / np.median(intervals)),
        "min_beat_ms": float(intervals.min()),
        "max_beat_ms": float(intervals.max()),
    }
