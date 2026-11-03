"""Why the RMS tail gate exists: show the song end before and after the gate.

Prints the tracked beats, the Beat This! reference and the local RMS around the fade-out.
The tracker's last raw beat sits in the decay, so the gate drops trailing beats whose RMS
is below a fraction of the song median; otherwise the final timing segment is pulled late.

Usage (repository root):
    python tools/rhythm-lab/experiments/end_check.py
"""

import numpy as np

from _shared import load_novelty, load_pcm, load_reference_beats
from rhythm_lab import tracker
from rhythm_lab.config import Baseline

baseline = Baseline.load()
pcm, sample_rate = load_pcm()
reference_beats = load_reference_beats()
nov = load_novelty()
bpm = 2.0 * 60000.0 / float(np.median(np.diff(reference_beats)))
estimate = tracker.estimate_timing(nov, pcm, sample_rate, bpm, float(nov.times_ms[-1]))
raw = tracker.beats.beat_track(nov.values, nov.times_ms, nov.fps, start_bpm=bpm / 2.0,
                               tightness=baseline.tracker.dp_tightness,
                               refine_radius=baseline.tracker.refine_radius)

print("raw tracker beats 167 s+ :", np.round(raw[raw > 167000.0], 1).tolist())
print("kept tracker beats 167 s+:", np.round(estimate.beat_times_ms[estimate.beat_times_ms > 167000.0], 1).tolist())
print("reference beats 167 s+   :", np.round(reference_beats[reference_beats > 167000.0], 1).tolist())
print(f"dropped tail beats: {estimate.diagnostics['dropped_tail_beats']}")

median_rms = float(np.median([tracker.local_rms(pcm, sample_rate, ms, 0.1)
                              for ms in np.arange(5000.0, 170000.0, 500.0)]))
print(f"median 100 ms RMS over the song: {median_rms:.4f}")
print("\n  time_s     novelty   local_rms   rms/median")
for time_s in np.arange(170.2, 172.0, 0.1):
    frame = int(np.argmin(np.abs(nov.times_ms - time_s * 1000.0)))
    rms = tracker.local_rms(pcm, sample_rate, time_s * 1000.0)
    print(f"  {time_s:6.2f}   {nov.values[frame]:7.3f}   {rms:8.4f}   {rms / median_rms:8.2f}")
