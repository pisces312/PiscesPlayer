"""Shared paths and loaders for the one-off experiments.

Every script here is historical evidence, not production code. Run scripts from the
repository root: `python tools/rhythm-lab/experiments/<name>.py`.
"""

import sys
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO_ROOT / "tools" / "rhythm-lab"))

from rhythm_lab import audio, novelty, reference  # noqa: E402
from rhythm_lab.config import CHART_OUT_DIR, Baseline  # noqa: E402

EXPERIMENT_OUT = CHART_OUT_DIR / "experiments"


def load_pcm() -> tuple[np.ndarray, int]:
    return audio.read_wav(Baseline.load().decoded_wav)


def load_reference_beats() -> np.ndarray:
    return reference.load_beats()


def load_novelty() -> novelty.Novelty:
    baseline = Baseline.load()
    cache = CHART_OUT_DIR / f"novelty_{baseline.tracker.band_count}.npz"
    if cache.is_file():
        data = np.load(cache)
        return novelty.Novelty(
            values=data["values"],
            times_ms=data["times_ms"],
            fps=float(data["fps"]),
            hop_ms=float(data["hop_ms"]),
            frame_offset_ms=float(data["frame_offset_ms"]),
            flux_bands=data["flux_bands"] if "flux_bands" in data else None,
        )
    pcm, sample_rate = load_pcm()
    return novelty.compute(pcm, sample_rate, band_count=baseline.tracker.band_count)


def ensure_out() -> Path:
    EXPERIMENT_OUT.mkdir(parents=True, exist_ok=True)
    return EXPERIMENT_OUT
