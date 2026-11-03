"""Scratch: fine tempogram over time + kick-band onset analysis."""

import sys
from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfiltfilt

sys.path.insert(0, str(Path("tools/rhythm-lab").resolve()))

from rhythm_lab import audio  # noqa: E402
from rhythm_lab.config import CHART_OUT_DIR, Baseline  # noqa: E402
from _shared import load_novelty  # noqa: E402


def fine_tempogram(values, times_s, window_s=20.0, hop_s=2.0, lo=60.0, hi=220.0, step=0.02):
    bpms = np.arange(lo, hi + step, step)
    omegas = 2.0 * np.pi * bpms / 60.0
    rows = []
    for start in np.arange(0.0, times_s[-1] - window_s, hop_s):
        selected = (times_s >= start) & (times_s < start + window_s)
        local = times_s[selected] - start
        taper = np.hanning(local.size)
        weighted = values[selected].astype(np.float64) * taper
        best_mag = -1.0
        best_bpm = lo
        for chunk_start in range(0, bpms.size, 128):
            chunk = omegas[chunk_start:chunk_start + 128]
            matrix = np.exp(-1j * np.outer(chunk, local))
            mags = np.abs(matrix @ weighted)
            index = int(np.argmax(mags))
            if mags[index] > best_mag:
                best_mag = float(mags[index])
                best_bpm = float(bpms[chunk_start + index])
        rows.append((start, best_bpm, best_mag / (weighted.sum() + 1e-9)))
    return rows


def kick_envelope(sample_rate, pcm, low=40.0, high=150.0):
    sos = butter(4, [low / (sample_rate / 2), high / (sample_rate / 2)], btype="band", output="sos")
    filtered = sosfiltfilt(sos, pcm)
    envelope = np.abs(filtered)
    sos_env = butter(4, 25.0 / (sample_rate / 2), btype="low", output="sos")
    return sosfiltfilt(sos_env, envelope)


def peak_pick(x, sample_rate, min_gap_s=0.12, threshold_ratio=0.0):
    threshold = threshold_ratio * float(np.max(x))
    peaks = []
    for index in range(1, x.size - 1):
        if x[index] <= x[index - 1] or x[index] < x[index + 1] or x[index] < threshold:
            continue
        if peaks and index - peaks[-1] < min_gap_s * sample_rate:
            if x[index] > x[peaks[-1]]:
                peaks[-1] = index
            continue
        peaks.append(index)
    return np.asarray(peaks, dtype=np.float64)


def main():
    baseline = Baseline.load()
    nov = load_novelty()
    novn = nov.values
    times_ms = nov.times_ms
    times_s = times_ms / 1000.0

    rows = fine_tempogram(novn, times_s)
    print("full-band fine tempogram (20 s window):")
    for start, bpm, concentration in rows[::2]:
        print(f"  {start:6.1f}s  bpm={bpm:8.2f}  beat_ms={60000/bpm:7.2f}  conc={concentration:.3f}")

    low = nov.flux_bands[:, 0:2].sum(axis=1).astype(np.float64)
    low = low / (np.percentile(low, 95) + 1e-9)
    rows = fine_tempogram(low, times_s)
    print("\nkick-band fine tempogram (20 s window, 21-129 Hz):")
    for start, bpm, concentration in rows[::2]:
        print(f"  {start:6.1f}s  bpm={bpm:8.2f}  beat_ms={60000/bpm:7.2f}  conc={concentration:.3f}")

    pcm, sample_rate = audio.read_wav(baseline.decoded_wav)
    envelope = kick_envelope(sample_rate, pcm)
    peaks = peak_pick(envelope, sample_rate, min_gap_s=0.15, threshold_ratio=0.1)
    peak_times = peaks / sample_rate
    intervals = np.diff(peak_times)
    intervals = intervals[(intervals > 0.15) & (intervals < 1.2)]
    print(f"\nkick peaks: {peaks.size} over {pcm.size/sample_rate:.1f}s")
    hist, edges = np.histogram(intervals, bins=np.arange(0.15, 1.25, 0.01))
    top = np.argsort(hist)[-12:][::-1]
    print("  top inter-onset intervals (s):")
    for index in top:
        print(f"    {edges[index]:.2f}-{edges[index+1]:.2f}  {hist[index]}")


if __name__ == "__main__":
    main()
