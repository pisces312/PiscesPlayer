"""Scratch: sliding joint period+phase fit on the novelty, checked against drum phase."""

import sys
from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfiltfilt

sys.path.insert(0, str(Path("tools/rhythm-lab").resolve()))

from rhythm_lab import audio  # noqa: E402
from rhythm_lab.config import CHART_OUT_DIR, Baseline  # noqa: E402
from _shared import load_novelty  # noqa: E402

GLOBAL_BEAT = 352.3218
CHART_OFFSET = 139.2895


def band_flux(pcm, sample_rate, low, high, hop=256):
    sos = butter(4, [low / (sample_rate / 2), high / (sample_rate / 2)], btype="band", output="sos")
    envelope = np.abs(sosfiltfilt(sos, pcm))
    sos_env = butter(4, 25.0 / (sample_rate / 2), btype="low", output="sos")
    envelope = sosfiltfilt(sos_env, envelope)
    diff = np.maximum(np.diff(envelope, prepend=envelope[0]), 0.0)
    kernel = max(1, int(0.015 * sample_rate))
    diff = np.convolve(diff, np.ones(kernel) / kernel, mode="same")
    return diff[::hop], sample_rate / hop


def joint_fit(values, times_s, start_s, window_s, period_ms_grid, step_ms=1.0):
    selected = (times_s >= start_s) & (times_s < start_s + window_s)
    local = times_s[selected]
    weight = values[selected].astype(np.float64) * np.hanning(local.size)
    total = weight.sum()
    if total <= 1e-12 or local.size < 8:
        return None
    best = (-1.0, 0.0, 0.0)
    for period_s in period_ms_grid / 1000.0:
        z = np.sum(weight * np.exp(1j * 2.0 * np.pi * local / period_s))
        magnitude = abs(z) / total
        if magnitude > best[0]:
            phase_s = (np.angle(z) / (2.0 * np.pi) * period_s) % period_s
            best = (float(magnitude), float(period_s * 1000.0), float(phase_s * 1000.0))
    return best


def circular_phase(times_ms, weights, period_ms):
    phase = np.mod(times_ms, period_ms) / period_ms * 2.0 * np.pi
    z = np.sum(weights * np.exp(1j * phase)) / (weights.sum() + 1e-12)
    return (np.angle(z) / (2.0 * np.pi) * period_ms) % period_ms, abs(z)


def main():
    baseline = Baseline.load()
    pcm, sample_rate = audio.read_wav(baseline.decoded_wav)
    nov = load_novelty()
    novn = nov.values.astype(np.float64)
    times_ms = nov.times_ms
    times_s = times_ms / 1000.0

    kick, fps = band_flux(pcm, sample_rate, 35.0, 90.0)
    snare, _ = band_flux(pcm, sample_rate, 150.0, 400.0)
    drum = kick / (np.percentile(kick, 95) + 1e-9) + snare / (np.percentile(snare, 95) + 1e-9)
    drum_times = np.arange(drum.size) / fps

    periods = np.arange(338.0, 368.0, 0.05)
    print(f"{'window':>13} {'P_nov':>7} {'phi_nov':>8} {'conc':>6} | {'P_drum':>7} {'phi_drum':>8} {'conc':>6} | phi_diff")
    start = 0.0
    while start + 16.0 <= times_s[-1]:
        fit_nov = joint_fit(novn, times_s, start, 16.0, periods)
        fit_drum = joint_fit(drum, drum_times, start, 16.0, periods)

        mask = (drum_times >= start) & (drum_times < start + 16.0)
        phase_drum, r_drum = circular_phase(
            drum_times[mask] * 1000.0, drum[mask], fit_drum[1] if fit_drum else GLOBAL_BEAT
        )
        if fit_nov and fit_drum:
            diff = ((fit_nov[2] - phase_drum + fit_nov[1] / 2) % fit_nov[1]) - fit_nov[1] / 2
            print(f"{start:5.0f}-{start+16:5.0f}s {fit_nov[1]:7.2f} {fit_nov[2]:8.2f} {fit_nov[0]:6.3f} | "
                  f"{fit_drum[1]:7.2f} {phase_drum:8.2f} {r_drum:6.3f} | {diff:+6.1f}")
        start += 4.0

    print("\nglobal fits:")
    for label, values, t in (("novelty", novn, times_s), ("drums", drum, drum_times)):
        fit = joint_fit(values, t, 0.0, times_s[-1], periods)
        print(f"  {label:8s} P={fit[1]:.3f} ms phase={fit[2]:.2f} ms conc={fit[0]:.4f}")


if __name__ == "__main__":
    main()
