"""Scratch: find a novelty transform whose windowed phase tracks beat_this."""

import sys
from pathlib import Path

import numpy as np
from scipy.ndimage import gaussian_filter1d

sys.path.insert(0, str(Path("tools/rhythm-lab").resolve()))

from _shared import load_novelty, load_reference_beats  # noqa: E402

PERIOD = 352.3218
BEAT_THIS = load_reference_beats()


def beat_this_phase(start_s, window_s):
    selected = BEAT_THIS[(BEAT_THIS >= start_s * 1000) & (BEAT_THIS < (start_s + window_s) * 1000)]
    if selected.size < 2:
        return None
    z = np.sum(np.exp(1j * 2.0 * np.pi * selected / PERIOD)) / selected.size
    return (np.angle(z) / (2.0 * np.pi) * PERIOD) % PERIOD, float(abs(z))


def demod_phase(values, times_ms, center_ms, window_s, period_ms=PERIOD):
    half = window_s * 500.0
    selected = (times_ms >= center_ms - half) & (times_ms < center_ms + half)
    local = times_ms[selected]
    weight = values[selected].astype(np.float64) * np.hanning(local.size)
    total = weight.sum()
    if total <= 1e-9:
        return None
    z = np.sum(weight * np.exp(-1j * 2.0 * np.pi * local / period_ms))
    phi = (-np.angle(z) / (2.0 * np.pi) * period_ms) % period_ms
    return phi, float(abs(z) / total)


def main():
    nov = load_novelty()
    novn = nov.values.astype(np.float64)
    times = nov.times_ms
    fps = nov.fps
    bands = nov.flux_bands.astype(np.float64)

    transforms = {"raw": novn}
    for sigma_ms in (30, 60, 120, 240):
        transforms[f"smooth{sigma_ms}"] = gaussian_filter1d(novn, sigma_ms / (1000.0 / fps))
    for sigma_ms in (60, 120, 240):
        transforms[f"sq_smooth{sigma_ms}"] = gaussian_filter1d(novn ** 2, sigma_ms / (1000.0 / fps))
    transforms["sqrt"] = np.sqrt(novn)
    low = bands[:, 0:2].sum(axis=1)
    transforms["low01"] = low / (np.percentile(low, 95) + 1e-9)
    mid = bands[:, 3:5].sum(axis=1)
    transforms["mid34"] = mid / (np.percentile(mid, 95) + 1e-9)

    window_s = 20.0
    centers = np.arange(10000.0, 170000.0, 5000.0)
    print(f"{'transform':>12}  mean_diff  std  max|d|  |  rms_after_constant_fit")
    for name, values in transforms.items():
        diffs = []
        for center in centers:
            bt = beat_this_phase((center - window_s * 500.0) / 1000.0, window_s)
            fit = demod_phase(values, times, center, window_s)
            if bt is None or fit is None:
                continue
            diff = ((fit[0] - bt[0] + PERIOD / 2) % PERIOD) - PERIOD / 2
            diffs.append(diff)
        diffs = np.asarray(diffs)
        print(f"{name:>12}  {diffs.mean():+9.1f} {diffs.std():4.1f} {np.abs(diffs).max():6.1f}  |  "
              f"{np.sqrt(np.mean((diffs - diffs.mean()) ** 2)):6.1f}   "
              + " ".join(f"{d:+4.0f}" for d in diffs[::4]))


if __name__ == "__main__":
    main()
