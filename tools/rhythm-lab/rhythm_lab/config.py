"""Paths and the regression baseline definition."""

import json
from dataclasses import dataclass, field
from pathlib import Path

from .tracker import TrackerConfig

PACKAGE_DIR = Path(__file__).resolve().parent
LAB_DIR = PACKAGE_DIR.parent
REPO_ROOT = LAB_DIR.parent.parent
BASELINE_PATH = LAB_DIR / "baseline.json"
CHART_OUT_DIR = REPO_ROOT / "app" / "build" / "rhythm-lab" / "out"
DEFAULT_REFERENCE_BEATS = LAB_DIR / "reference" / "beat_this_final0_fine.txt"


@dataclass(frozen=True)
class Baseline:
    song_path: Path
    decoded_wav: Path
    sample_rate: int
    expected_bpm: float
    target_max_error_ms: float
    bpm_range: tuple[float, float]
    expected_duration_ms: int
    reference_beats: Path = DEFAULT_REFERENCE_BEATS
    tracker: TrackerConfig = field(default_factory=TrackerConfig)

    @classmethod
    def load(cls, path: Path = BASELINE_PATH) -> "Baseline":
        data = json.loads(path.read_text(encoding="utf-8"))
        low, high = data.get("referenceBeatTempoRange", [160.0, 182.0])
        tracker = data.get("tracker", {})
        return cls(
            song_path=Path(data["songPath"]),
            decoded_wav=REPO_ROOT / data["decodedWav"],
            sample_rate=int(data.get("sampleRate", 44100)),
            expected_bpm=float(data["expectedBpm"]),
            target_max_error_ms=float(data.get("targetMaxErrorMs", 20.0)),
            bpm_range=(float(low), float(high)),
            expected_duration_ms=int(data.get("expectedDurationMs", 0)),
            reference_beats=LAB_DIR / data.get("reference", {}).get("beatsFile", "reference/beat_this_final0_fine.txt"),
            tracker=TrackerConfig(
                band_count=int(tracker.get("bandCount", TrackerConfig.band_count)),
                whiten_window=int(tracker.get("whitenWindow", TrackerConfig.whiten_window)),
                dp_tightness=float(tracker.get("dpTightness", TrackerConfig.dp_tightness)),
                refine_radius=int(tracker.get("refineRadius", TrackerConfig.refine_radius)),
                tail_rms_fraction=float(tracker.get("tailRmsFraction", TrackerConfig.tail_rms_fraction)),
                anchor_every=int(tracker.get("anchorEveryBeats", TrackerConfig.anchor_every)),
                fit_half_window=int(tracker.get("fitHalfWindowBeats", TrackerConfig.fit_half_window)),
                fit_residual_ms=float(tracker.get("fitResidualMs", TrackerConfig.fit_residual_ms)),
            ),
        )
