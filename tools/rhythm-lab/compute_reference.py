#!/usr/bin/env python
"""Regenerate the cached Beat This! reference beats for the baseline song.

Run with the lab virtual environment (it needs torch + beat-this):

    tools/rhythm-lab/.venv/Scripts/python.exe tools/rhythm-lab/compute_reference.py

The output is committed as a small text fixture. The model runs at 50 fps; the beat times
here are parabolic sub-frame peaks of the beat logits, which is why they are not multiples
of 20 ms.
"""

import argparse
import sys
from pathlib import Path

import numpy as np

LAB_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB_DIR))

from rhythm_lab import audio  # noqa: E402
from rhythm_lab.config import Baseline  # noqa: E402
from rhythm_lab.reference import DEFAULT_BEATS_FILE, DEFAULT_DOWNBEATS_FILE  # noqa: E402


def peaks_with_interpolation(logits: np.ndarray, fps: float = 50.0, threshold: float = 0.0) -> np.ndarray:
    import torch
    from torch.nn.functional import max_pool1d

    tensor = torch.tensor(logits, dtype=torch.float32)[None, None, :]
    pooled = max_pool1d(tensor, 7, 1, 3)[0, 0].numpy()
    candidates = np.where((logits >= pooled) & (logits > threshold))[0]
    if candidates.size == 0:
        return np.zeros(0)
    keep = [int(candidates[0])]
    for index in candidates[1:]:
        if index - keep[-1] > 1:
            keep.append(int(index))
    keep = np.asarray(keep)
    delta = np.zeros(keep.size)
    for position, frame in enumerate(keep):
        if 0 < frame < logits.size - 1:
            y0, y1, y2 = (float(v) for v in logits[frame - 1:frame + 2])
            denominator = y0 - 2.0 * y1 + y2
            if abs(denominator) > 1e-12:
                delta[position] = float(np.clip(0.5 * (y0 - y2) / denominator, -0.5, 0.5))
    return (keep + delta) * 1000.0 / fps


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="recompute even if the cache exists")
    args = parser.parse_args()

    baseline = Baseline.load()
    if baseline.reference_beats.is_file() and not args.force:
        print(f"reference already cached: {baseline.reference_beats}")
        return
    if not baseline.decoded_wav.is_file():
        print(f"decoding {baseline.song_path}")
        audio.decode(baseline.song_path, baseline.decoded_wav, baseline.sample_rate)

    from beat_this.inference import Audio2Frames, load_audio

    signal, sample_rate = load_audio(str(baseline.decoded_wav), dtype="float32")
    model = Audio2Frames("final0", device="cpu")
    beat_logits, downbeat_logits = model(signal, sample_rate)
    beats = peaks_with_interpolation(beat_logits.cpu().numpy())
    downbeats = peaks_with_interpolation(downbeat_logits.cpu().numpy())

    baseline.reference_beats.parent.mkdir(parents=True, exist_ok=True)
    np.savetxt(baseline.reference_beats, beats, fmt="%.3f")
    np.savetxt(DEFAULT_DOWNBEATS_FILE, downbeats, fmt="%.3f")
    intervals = np.diff(beats)
    print(f"beats    : {beats.size}  first={beats[0]:.1f}  last={beats[-1]:.1f} ms")
    print(f"interval : median={np.median(intervals):.2f} ms  bpm={60000.0 / np.median(intervals):.3f}")
    print(f"saved    : {baseline.reference_beats}")


if __name__ == "__main__":
    main()
