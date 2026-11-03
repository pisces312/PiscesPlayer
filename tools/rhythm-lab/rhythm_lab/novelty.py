"""Kotlin-equivalent novelty curve.

This mirrors `RhythmChartGenerator.analyze` stages 1-4 exactly enough for the lab: same FFT
size and hop, same log-spaced band edges, same running-max whitening, same half-wave
rectified flux and the same p95 normalisation. Keeping the conventions identical matters
because every beat time and phase below is expressed in the Kotlin pipeline's time base.

`band_count` defaults to the app's 8 bands. The tracker uses 64: the extra bands turn the
novelty curve into a much sharper beat marker without changing any other convention.
"""

from dataclasses import dataclass
from math import cos, exp, floor, log

import numpy as np

N_FFT = 2048
HOP = 512
N_BANDS = 8
WHITEN_WINDOW = 32


@dataclass(frozen=True)
class Novelty:
    values: np.ndarray
    times_ms: np.ndarray
    fps: float
    hop_ms: float
    frame_offset_ms: float
    """Per-band half-wave rectified flux, shape (frames, bands); the sum is `values` before normalisation."""
    flux_bands: np.ndarray | None = None

    def frame_to_ms(self, frame: float) -> np.ndarray:
        return self.frame_offset_ms + np.asarray(frame, dtype=np.float64) * self.hop_ms

    def band_values(self, low: int, high: int) -> np.ndarray:
        if self.flux_bands is None:
            raise ValueError("novelty cache has no per-band flux; delete novelty.npz and rerun")
        selected = self.flux_bands[:, low:high].sum(axis=1)
        return selected / (float(np.percentile(selected, 95)) + 1e-9)


def _round_half_up(value: float) -> int:
    return int(floor(value + 0.5))


def band_edges(band_count: int = N_BANDS) -> list[int]:
    highest = N_FFT // 2
    log_low = log(1.0)
    log_high = log(float(highest))
    edges = [_round_half_up(exp(log_low + (log_high - log_low) * i / band_count))
             for i in range(band_count + 1)]
    distinct: list[int] = []
    for edge in edges:
        if not distinct or edge != distinct[-1]:
            distinct.append(edge)
    return distinct


def _running_max(values: np.ndarray, window: int) -> np.ndarray:
    """Per-column maximum over [i - window, i], same inclusive window as the Kotlin deque."""
    out = np.empty_like(values)
    for column in range(values.shape[1]):
        data = values[:, column]
        queue: list[int] = []
        head = 0
        for index in range(data.size):
            while len(queue) > head and data[queue[-1]] <= data[index]:
                queue.pop()
            queue.append(index)
            lowest = index - window
            while queue[head] < lowest:
                head += 1
            out[index, column] = data[queue[head]]
    return out


def compute(
    pcm: np.ndarray,
    sample_rate: int,
    band_count: int = N_BANDS,
    whiten_window: int = WHITEN_WINDOW,
) -> Novelty:
    if pcm.size < N_FFT:
        raise SystemExit("audio is too short for the novelty front-end")
    frame_count = 1 + (pcm.size - N_FFT) // HOP
    window = (0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(N_FFT) / (N_FFT - 1))).astype(np.float32)
    edges = band_edges(band_count)
    band_count = len(edges) - 1

    band_raw = np.empty((frame_count, band_count), dtype=np.float32)
    chunk = 512
    for start in range(0, frame_count, chunk):
        stop = min(frame_count, start + chunk)
        frames = np.arange(start, stop)[:, None] * HOP + np.arange(N_FFT)[None, :]
        spectrum = np.abs(np.fft.rfft(pcm[frames] * window, axis=1))
        for band in range(band_count):
            peak = spectrum[:, edges[band]:edges[band + 1]].max(axis=1)
            band_raw[start:stop, band] = np.log1p(peak)

    running = _running_max(band_raw, whiten_window)
    band = band_raw / (running + 1e-4)

    delta = band[1:] - band[:-1]
    flux_bands = np.maximum(delta, 0.0)
    flux_bands = np.concatenate([np.zeros((1, band_count), dtype=np.float32), flux_bands])
    flux = flux_bands.sum(axis=1)
    novelty = flux.astype(np.float32)

    p95 = float(np.percentile(novelty, 95))
    novn = novelty / (p95 + 1e-9)

    fps = sample_rate / HOP
    hop_ms = HOP * 1000.0 / sample_rate
    frame_offset_ms = (N_FFT - HOP / 2.0) * 1000.0 / sample_rate
    times_ms = frame_offset_ms + np.arange(frame_count, dtype=np.float64) * hop_ms
    return Novelty(values=novn, times_ms=times_ms, fps=fps, hop_ms=hop_ms,
                   frame_offset_ms=frame_offset_ms, flux_bands=flux_bands.astype(np.float32))
