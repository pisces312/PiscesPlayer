"""Scratch: which frequency band's flux phase tracks the beat_this reference?"""

import sys
from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfiltfilt

sys.path.insert(0, str(Path("tools/rhythm-lab").resolve()))

from rhythm_lab import audio  # noqa: E402
from rhythm_lab.config import Baseline  # noqa: E402
from _shared import load_reference_beats  # noqa: E402

PERIOD = 352.3218
BEAT_THIS = load_reference_beats()


def band_flux(pcm, sample_rate, low, high, hop=512, smooth_s=0.015):
    sos = butter(4, [low / (sample_rate / 2), high / (sample_rate / 2)], btype="band", output="sos")
    envelope = np.abs(sosfiltfilt(sos, pcm))
    sos_env = butter(4, 25.0 / (sample_rate / 2), btype="low", output="sos")
    envelope = sosfiltfilt(sos_env, envelope)
    diff = np.maximum(np.diff(envelope, prepend=envelope[0]), 0.0)
    kernel = max(1, int(smooth_s * sample_rate))
    diff = np.convolve(diff, np.ones(kernel) / kernel, mode="same")
    return diff[::hop], sample_rate / hop


def demod_phase(values, fps, start_s, window_s=16.0, period_ms=PERIOD):
    count = values.size
    center = int((start_s + window_s / 2) * fps)
    half = int(window_s / 2 * fps)
    low = max(0, center - half)
    high = min(count, center + half)
    frames = np.arange(low, high) - center
    weight = values[low:high].astype(np.float64) * np.hanning(high - low)
    total = weight.sum()
    if total <= 1e-9:
        return None
    phase = -2.0 * np.pi * frames / fps / (period_ms / 1000.0)
    z = np.sum(weight * np.exp(1j * phase))
    phi = (-np.angle(z) / (2.0 * np.pi) * period_ms) % period_ms
    return phi, float(abs(z) / total)


def beat_this_phase(start_s, window_s=16.0):
    selected = BEAT_THIS[(BEAT_THIS >= start_s * 1000) & (BEAT_THIS < (start_s + window_s) * 1000)]
    if selected.size < 2:
        return None
    z = np.sum(np.exp(1j * 2.0 * np.pi * selected / PERIOD)) / selected.size
    return (np.angle(z) / (2.0 * np.pi) * PERIOD) % PERIOD, float(abs(z))


def main():
    baseline = Baseline.load()
    pcm, sample_rate = audio.read_wav(baseline.decoded_wav)
    bands = [
        ("40-90", 40.0, 90.0),
        ("90-200", 90.0, 200.0),
        ("200-400", 200.0, 400.0),
        ("400-1000", 400.0, 1000.0),
        ("1k-3k", 1000.0, 3000.0),
        ("3k-8k", 3000.0, 8000.0),
    ]
    signals = {}
    for name, low, high in bands:
        values, fps = band_flux(pcm, sample_rate, low, high)
        signals[name] = (values, fps)

    starts = np.arange(2.0, 160.0, 8.0)
    print(f"{'band':>10} " + " ".join(f"{s:5.0f}" for s in starts) + "   | mean_diff  std  max|d|")
    for name, (values, fps) in signals.items():
        diffs = []
        row = []
        for start in starts:
            bt = beat_this_phase(start)
            fit = demod_phase(values, fps, start)
            if bt is None or fit is None:
                row.append("    .")
                continue
            diff = ((fit[0] - bt[0] + PERIOD / 2) % PERIOD) - PERIOD / 2
            diffs.append(diff)
            row.append(f"{diff:+5.0f}")
        diffs = np.asarray(diffs)
        print(f"{name:>10} " + " ".join(row) +
              f"   | {diffs.mean():+8.1f} {diffs.std():5.1f} {np.abs(diffs).max():6.1f}")


if __name__ == "__main__":
    main()
