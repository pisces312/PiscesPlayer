"""Scratch: per-instrument onsets separated by frequency band."""

import sys
from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfiltfilt

sys.path.insert(0, str(Path("tools/rhythm-lab").resolve()))

from rhythm_lab import audio  # noqa: E402
from rhythm_lab.config import Baseline  # noqa: E402


def band_envelope(pcm, sample_rate, low, high, smooth=25.0):
    sos = butter(4, [low / (sample_rate / 2), high / (sample_rate / 2)], btype="band", output="sos")
    filtered = sosfiltfilt(sos, pcm)
    envelope = np.abs(filtered)
    sos_env = butter(4, smooth / (sample_rate / 2), btype="low", output="sos")
    return sosfiltfilt(sos_env, envelope)


def onset_strength(envelope, sample_rate, hop=256):
    # positive difference of a smoothed envelope, decimated to ~172 Hz
    diff = np.maximum(np.diff(envelope, prepend=envelope[0]), 0.0)
    kernel = int(0.02 * sample_rate)
    if kernel > 1:
        diff = np.convolve(diff, np.ones(kernel) / kernel, mode="same")
    return diff[::hop], sample_rate / hop


def pick_peaks(values, fps, min_gap_s=0.12, rel_threshold=0.12):
    threshold = rel_threshold * float(np.max(values))
    peaks = []
    for index in range(1, values.size - 1):
        if values[index] <= values[index - 1] or values[index] < values[index + 1]:
            continue
        if values[index] < threshold:
            continue
        gap = min_gap_s * fps
        if peaks and index - peaks[-1] < gap:
            if values[index] > values[peaks[-1]]:
                peaks[-1] = index
            continue
        peaks.append(index)
    return np.asarray(peaks, dtype=np.float64) / fps


def phase_stats(times_ms, period_ms):
    phase = np.mod(times_ms, period_ms)
    z = np.sum(np.exp(1j * 2.0 * np.pi * phase / period_ms)) / phase.size
    return np.angle(z) / (2.0 * np.pi) * period_ms % period_ms, abs(z)


def main():
    baseline = Baseline.load()
    pcm, sample_rate = audio.read_wav(baseline.decoded_wav)

    bands = {
        "kick 35-90": (35.0, 90.0),
        "snare 150-400": (150.0, 400.0),
        "hat 5k-10k": (5000.0, 10000.0),
    }
    results = {}
    for name, (low, high) in bands.items():
        envelope = band_envelope(pcm, sample_rate, low, high)
        strength, fps = onset_strength(envelope, sample_rate)
        peaks = pick_peaks(strength, fps, min_gap_s=0.12, rel_threshold=0.10)
        results[name] = peaks[1:] * 1000.0  # skip the boundary artefact
        print(f"{name:15s} peaks={peaks.size}")

    for name, times in results.items():
        line = f"{name:15s}"
        for period in (708.21, 352.32, 176.16):
            phase, r = phase_stats(times, period)
            line += f" | P={period:6.2f} phase={phase:7.2f} R={r:.3f}"
        print(line)

    print("\nfirst 24 s pattern (phase relative to 352.32 ms grid at 139.29):")
    period = 352.3218
    origin = 139.2895
    for name, times in results.items():
        window = times[(times >= 2000.0) & (times < 24000.0)]
        rel = ((window - origin) % period)
        rel = np.where(rel > period / 2, rel - period, rel)
        print(f"\n{name}:")
        print("  " + " ".join(f"{t/1000:6.2f}s({r:+6.0f})" for t, r in zip(window[:28], rel[:28])))


if __name__ == "__main__":
    main()
