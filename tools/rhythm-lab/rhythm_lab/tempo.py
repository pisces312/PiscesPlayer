"""Tempo and beat-phase estimators.

Two families live here on purpose:
- `refine_tempo` is a faithful port of the Kotlin refinement, used to report what the app
  currently uses;
- `windowed_tempo_scan` and `demodulate_phase` are independent measurements built on the
  same novelty curve, used as the reference and as the proposed timing source.
"""

from math import cos, exp, pi

import numpy as np


def _demodulate_magnitude(weighted: np.ndarray, fps: float, bpm: float) -> float:
    omega = 2.0 * pi * (bpm / 60.0) / fps
    index = np.arange(weighted.size, dtype=np.float64)
    z = np.sum(weighted * np.exp(-1j * omega * index))
    return float(abs(z) ** 2)


def refine_tempo(novn: np.ndarray, fps: float, coarse_bpm: float) -> float:
    """Whole-song complex demodulation: +/-1 % scan, then a parabolic peak fit."""
    frame_count = novn.size
    if not np.isfinite(coarse_bpm) or coarse_bpm <= 0.0 or frame_count < 16:
        return coarse_bpm
    hann = 0.5 - 0.5 * np.cos(2.0 * pi * np.arange(frame_count) / (frame_count - 1))
    weighted = hann * novn.astype(np.float64)

    search_fraction = 0.01
    half_steps = 40
    step_bpm = coarse_bpm * search_fraction / half_steps
    best_bpm = coarse_bpm
    best_magnitude = -np.inf
    for step in range(-half_steps, half_steps + 1):
        bpm = coarse_bpm * (1.0 + search_fraction * step / half_steps)
        magnitude = _demodulate_magnitude(weighted, fps, bpm)
        if magnitude > best_magnitude:
            best_magnitude = magnitude
            best_bpm = bpm

    y0 = _demodulate_magnitude(weighted, fps, best_bpm - step_bpm)
    y1 = _demodulate_magnitude(weighted, fps, best_bpm)
    y2 = _demodulate_magnitude(weighted, fps, best_bpm + step_bpm)
    denominator = y0 - 2.0 * y1 + y2
    if abs(denominator) < 1e-12:
        return best_bpm
    delta = float(np.clip(0.5 * (y0 - y2) / denominator, -1.0, 1.0))
    return best_bpm + delta * step_bpm


def scan_windowed(
    novn: np.ndarray,
    times_ms: np.ndarray,
    lo_bpm: float,
    hi_bpm: float,
    window_s: float = 20.0,
    hop_s: float = 10.0,
    step_bpm: float = 0.01,
) -> list[dict]:
    """Independent tempo verification: one best BPM per tapered window."""
    times_s = times_ms / 1000.0
    bpms = np.arange(lo_bpm, hi_bpm, step_bpm)
    rows: list[dict] = []
    for start in np.arange(0.0, times_s[-1] - window_s, hop_s):
        selected = (times_s >= start) & (times_s < start + window_s)
        local_times = times_s[selected] - start
        taper = np.hanning(local_times.size)
        weighted = novn[selected].astype(np.float64) * taper
        if weighted.sum() <= 0.0:
            continue
        magnitudes = np.empty(bpms.size)
        for index, bpm in enumerate(bpms):
            omega = 2.0 * pi * (bpm / 60.0)
            magnitudes[index] = abs(np.sum(weighted * np.exp(-1j * omega * local_times)))
        best = int(np.argmax(magnitudes))
        omega = 2.0 * pi * (bpms[best] / 60.0)
        z = np.sum(weighted * np.exp(-1j * omega * local_times))
        rows.append(
            {
                "start_s": float(start),
                "bpm": float(bpms[best]),
                "beat_ms": float(60000.0 / bpms[best]),
                "phase_deg": float(np.degrees(np.angle(z))),
                "concentration": float(magnitudes[best] / (weighted.sum() + 1e-9)),
            }
        )
    return rows


def demodulate_phase(
    novn: np.ndarray,
    times_ms: np.ndarray,
    bpm: float,
    offset_ms: float,
    query_ms: np.ndarray,
    sigma_s: float,
) -> tuple[np.ndarray, np.ndarray]:
    """Local beat-phase error and confidence at each query time.

    Positive error means the query grid line is late relative to the music. The estimate is
    the argument of the windowed complex demodulation at the beat frequency; confidence is
    |z| / sum(weight), i.e. how concentrated the windowed novelty is on one phase.
    """
    period_ms = 60000.0 / bpm
    sigma_ms = sigma_s * 1000.0
    hop_ms = float(times_ms[1] - times_ms[0])
    sigma_frames = sigma_ms / hop_ms
    errors = np.zeros(query_ms.size)
    confidence = np.zeros(query_ms.size)
    for index, query in enumerate(np.asarray(query_ms, dtype=np.float64)):
        center = (query - times_ms[0]) / hop_ms
        low = max(0, int(center - 3.0 * sigma_frames))
        high = min(novn.size, int(center + 3.0 * sigma_frames) + 1)
        if high <= low:
            continue
        frames = np.arange(low, high, dtype=np.float64)
        weights = novn[low:high].astype(np.float64) * np.exp(-0.5 * ((frames - center) / sigma_frames) ** 2)
        total = weights.sum()
        if total <= 1e-12:
            continue
        phase = -2.0 * pi * (times_ms[low:high] - offset_ms) / period_ms
        z = np.sum(weights * np.exp(1j * phase))
        error = -np.angle(z) / (2.0 * pi) * period_ms
        errors[index] = ((error + period_ms / 2.0) % period_ms) - period_ms / 2.0
        confidence[index] = abs(z) / total
    return errors, confidence
