"""Scratch: which grid phase do the drums actually support, and does it drift?"""

import sys
from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfiltfilt

sys.path.insert(0, str(Path("tools/rhythm-lab").resolve()))

from rhythm_lab import audio  # noqa: E402
from rhythm_lab.config import Baseline  # noqa: E402

PERIOD = 352.3218
CHART_OFFSET = 139.2895


def band_envelope(pcm, sample_rate, low, high, smooth=25.0):
    sos = butter(4, [low / (sample_rate / 2), high / (sample_rate / 2)], btype="band", output="sos")
    envelope = np.abs(sosfiltfilt(sos, pcm))
    sos_env = butter(4, smooth / (sample_rate / 2), btype="low", output="sos")
    return sosfiltfilt(sos_env, envelope)


def flux(envelope, sample_rate, hop=256, smooth_s=0.015):
    diff = np.maximum(np.diff(envelope, prepend=envelope[0]), 0.0)
    kernel = max(1, int(smooth_s * sample_rate))
    diff = np.convolve(diff, np.ones(kernel) / kernel, mode="same")
    return diff[::hop], sample_rate / hop


def circular_phase(times_ms, weights, period_ms):
    phase = np.mod(times_ms, period_ms) / period_ms * 2.0 * np.pi
    z = np.sum(weights * np.exp(1j * phase)) / (weights.sum() + 1e-12)
    phi_ms = (np.angle(z) / (2.0 * np.pi) * period_ms) % period_ms
    return phi_ms, abs(z)


def main():
    baseline = Baseline.load()
    pcm, sample_rate = audio.read_wav(baseline.decoded_wav)

    signals = {}
    for name, (low, high) in {
        "kick": (35.0, 90.0),
        "snare": (150.0, 400.0),
        "hat": (5000.0, 10000.0),
    }.items():
        envelope = band_envelope(pcm, sample_rate, low, high)
        strengths, fps = flux(envelope, sample_rate)
        mask = strengths > np.percentile(strengths, 90.0)
        times = np.arange(strengths.size) / fps * 1000.0
        signals[name] = (times[mask], strengths[mask], fps, mask, strengths, times)

    print("global drum phase at P=352.32:")
    for name, (times, weights, *_rest) in signals.items():
        phi, r = circular_phase(times, weights, PERIOD)
        print(f"  {name:6s} phase={phi:7.2f} ms  R={r:.3f}")

    # combined envelope
    combined = sum(signals[name][4] / (signals[name][4].max() + 1e-9) for name in signals)
    times = signals["kick"][5]
    mask = combined > np.percentile(combined, 88.0)
    phi_all, r_all = circular_phase(times[mask], combined[mask], PERIOD)
    print(f"  combined phase={phi_all:7.2f} ms  R={r_all:.3f}")

    print("\ncandidate grid phases (drum hits within +/-20 ms of any grid line):")
    for label, offset in [
        ("chart", CHART_OFFSET),
        ("half+", CHART_OFFSET + PERIOD / 2),
        ("global-nov", 332.5),
        ("top10-circ", 283.3),
        ("kick", 345.98),
        ("snare", 308.69),
        ("hat", 267.32),
        ("combined", phi_all),
    ]:
        hits = 0
        total = 0
        for name in ("kick", "snare", "hat"):
            t, w, *_ = signals[name]
            rel = np.abs(((t - offset + PERIOD / 2) % PERIOD) - PERIOD / 2)
            hits += int(np.sum(rel <= 20.0))
            total += t.size
        print(f"  {label:10s} offset={offset:7.2f}  hits={hits}/{total} ({hits/total*100:.1f}%)")

    print("\n10 s windows, combined drum phase (P=352.32):")
    start = 2000.0
    stop = 170000.0
    while start < stop:
        mask = (times >= start) & (times < start + 10000.0)
        phi, r = circular_phase(times[mask], combined[mask], PERIOD)
        rel = ((phi - CHART_OFFSET + PERIOD / 2) % PERIOD) - PERIOD / 2
        print(f"  {start/1000:6.1f}s  phase={phi:7.2f}  rel_chart={rel:+7.2f}  R={r:.3f}")
        start += 10000.0


if __name__ == "__main__":
    main()
