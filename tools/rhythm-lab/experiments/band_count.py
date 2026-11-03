"""Scratch: does a finer log-band novelty make the DP tracker match beat_this?"""

import sys
from math import cos, exp, floor, log
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path("tools/rhythm-lab").resolve()))

from rhythm_lab import audio, beats as lab_beats  # noqa: E402
from rhythm_lab.config import Baseline  # noqa: E402
from _shared import ensure_out, load_reference_beats  # noqa: E402

N_FFT = 2048
HOP = 512


def compute_bands(pcm, sample_rate, band_count, whiten_window=32):
    frame_count = 1 + (pcm.size - N_FFT) // HOP
    window = (0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(N_FFT) / (N_FFT - 1))).astype(np.float32)
    highest = N_FFT // 2
    log_low = log(1.0)
    log_high = log(float(highest))
    edges = []
    for i in range(band_count + 1):
        value = int(floor(exp(log_low + (log_high - log_low) * i / band_count) + 0.5))
        if not edges or value != edges[-1]:
            edges.append(value)
    edges[-1] = highest
    if len(edges) < 3:
        raise SystemExit("too few distinct bands")

    band_raw = np.empty((frame_count, len(edges) - 1), dtype=np.float32)
    for start in range(0, frame_count, 512):
        stop = min(frame_count, start + 512)
        frames = np.arange(start, stop)[:, None] * HOP + np.arange(N_FFT)[None, :]
        spectrum = np.abs(np.fft.rfft(pcm[frames] * window, axis=1))
        for band in range(len(edges) - 1):
            band_raw[start:stop, band] = np.log1p(spectrum[:, edges[band]:edges[band + 1]].max(axis=1))

    running = np.empty_like(band_raw)
    for column in range(band_raw.shape[1]):
        data = band_raw[:, column]
        queue = []
        head = 0
        for index in range(data.size):
            while len(queue) > head and data[queue[-1]] <= data[index]:
                queue.pop()
            queue.append(index)
            lowest = index - whiten_window
            while queue[head] < lowest:
                head += 1
            running[index, column] = data[queue[head]]

    band = band_raw / (running + 1e-4)
    flux = np.maximum(band[1:] - band[:-1], 0.0).sum(axis=1)
    flux = np.concatenate(([0.0], flux))
    flux = flux / (np.percentile(flux, 95) + 1e-9)
    times = ((N_FFT - HOP / 2.0) * 1000.0 / sample_rate
             + np.arange(frame_count) * HOP * 1000.0 / sample_rate)
    return flux.astype(np.float32), times, sample_rate / HOP


def errors(query, reference):
    reference = np.sort(reference)
    index = np.clip(np.searchsorted(reference, query), 1, reference.size - 1)
    left, right = reference[index - 1], reference[index]
    return query - np.where(np.abs(query - left) <= np.abs(right - query), left, right)


def main():
    baseline = Baseline.load()
    pcm, sample_rate = audio.read_wav(baseline.decoded_wav)
    beat_this = load_reference_beats()

    for band_count in (8, 16, 32, 64, 128):
        flux, times, fps = compute_bands(pcm, sample_rate, band_count)
        np.save(ensure_out() / f"novelty_{band_count}.npy", flux)
        for start_bpm in (85.0, 170.0):
            track = lab_beats.beat_track(flux, times, fps, start_bpm=start_bpm, tightness=100.0)
            # Keep the comparison inside the reference span: the tail and the intro are not
            # musical beats and would dominate the max error.
            track = track[(track >= beat_this[0]) & (track <= beat_this[-1])]
            if start_bpm < 120.0:
                e = errors(track, beat_this)
                label = f"bands={band_count:3d} bpm={start_bpm:5.0f}"
                print(f"{label:22s} n={track.size:4d} med={np.median(np.abs(e)):6.2f} "
                      f"p95={np.percentile(np.abs(e),95):6.2f} max={np.abs(e).max():7.2f}")


if __name__ == "__main__":
    main()
