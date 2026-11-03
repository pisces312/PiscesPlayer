# One-off experiments

Historical evidence for the 2026-11-03 chart-alignment investigation. None of this is part
of the app build, and the scripts are not a supported interface — they document which
questions were asked and what the answer was. The maintained pipeline lives one directory up
(`run_lab.py` + `rhythm_lab/`).

Run from the repository root, e.g.:

```bash
python tools/rhythm-lab/experiments/tune_tracker.py
```

`_shared.py` resolves the baseline song (`baseline.json`), the committed reference beats and
the 64-band novelty cache. If the cache is missing it is recomputed (slow, ~1 min).

## Scripts

| Script | Question it answered | Conclusion |
|---|---|---|
| `phase.py` | Does the chart's fixed grid phase match the audio? | No. A plain novelty puts the phase near 328 ms, the chart at 139 ms, and the per-10 s phase wanders +128…+173 ms. A single global offset cannot fit this song; the grid needs piecewise timing. |
| `phase_check.py` | Is the Kotlin comb-phase estimate itself correct? | No. The rounded period (`phasePeriod=30` frames instead of 30.35) plus `phaseFrame·hop` yields a prediction of 3.4 ms against the exported 139 ms offset. The comb estimate was the root cause of the half-beat shift. |
| `band_count.py` | Is the novelty's spectral resolution the limiting factor for DP tracking? | Yes. DP at the music tempo, errors vs Beat This!: 8 bands med 20.3 / p95 327 ms; 16 → 7.4 / 156; 32 → 5.4 / 63; **64 → 5.1 / 26**; 128 → 4.7 / 34. 64 log-spaced bands is the sweet spot. |
| `band_phase.py` | Does any single frequency band give a phase that tracks the reference? | No band is reliable across the whole song; the wideband 64-band novelty is better than any hand-picked band. |
| `drums.py` | Do kick / snare / hat onsets support a 352.32 ms grid? | Drums hit several grid phases (8th/16th patterns), so no stable grid can be derived from them alone. |
| `drum_phase.py` | If drums define a phase, does it drift? | It drifts; no 10 s window agrees on one phase. This is why a per-window tracker replaced a global phase. |
| `joint_fit.py` | Can a sliding joint period+phase fit reproduce the reference? | Local period/phase wobble dominates; the resulting grid was worse than DP beats + a local linear fit. Superseded. |
| `tempo.py` | Is there an authored tempo map or a tempo drift? | 85.3 BPM throughout; only microtiming, no map. |
| `tempo_windows.py` | Same question via windowed autocorrelation. | Same answer: 85.3 BPM stable. |
| `tempogram.py` | Fine tempogram + kick-band onset analysis. | Confirms 85.3 BPM; no hidden tempo change. |
| `transform_phase.py` | Can smoothing / squaring / low-band weighting make a novelty whose phase tracks the reference? | None of the transforms reached ±20 ms; abandoned in favour of the DP tracker. |
| `end_check.py` | Where does the song actually end, and what is the tracker doing in the fade-out? | Musical content ends ~171.4 s; the raw DP adds beats at 172.1 and 172.8 s in the reverb. The RMS tail gate drops them. |
| `tune_tracker.py` | Which tracker/fit parameters keep every grid line within 20 ms? | Tightness 600, one anchor per beat, ±10-beat weighted fit: max 18.95 / 18.95 / 19.56 ms at div 1 / 2 / 4, 100 % within 20 ms. Also acts as the regression check (exits non-zero on failure). |

`beat_this_fine.py` (sub-frame reference extraction) was folded into
`../compute_reference.py`; the resulting beat list is committed in `../reference/`.
