# rhythm-lab

PC-side lab for aligning generated rhythm charts with the real song. Nothing here is part
of the app build; the goal is to prove a chart-generation recipe on the desktop before
porting it to Kotlin.

## Baseline

`baseline.json` is the single source of truth:

- song: `E:/迎着风向前冲-姜月初.mp3` (172.875 s, ~85.3 BPM music, 170.3 BPM chart);
- decoded input: `build/rhythm-lab/baseline.wav` (44.1 kHz mono, gitignored);
- target: max absolute grid error **20 ms**;
- tracker parameters (see below).

Reference beats come from **Beat This!** (`final0`, MIT) with sub-frame parabolic peaks:
244 beats, 259.5–171361.2 ms. The result is committed in
`reference/beat_this_final0_fine.txt`, so normal lab runs are offline and need no model.

## Layout

| Path | Role |
|---|---|
| `run_lab.py` | entry point for every command |
| `baseline.json` | song, target, reference pointer, tracker parameters |
| `rhythm_lab/` | shared pipeline: novelty, DP tracker, piecewise timing, metrics |
| `compute_reference.py` | regenerates the Beat This! reference (venv only) |
| `reference/` | committed reference beat lists (fine beats + downbeats) |
| `experiments/` | historical one-off questions; see `experiments/README.md` |
| `docs/` | investigation notes written while fixing the drift |
| `gen_dsp_fixture.py` | generates the DSP fixture used by Kotlin unit tests |

## Setup

Everything except `reference` needs only `numpy` + `scipy` (`requirements.txt`).
`reference` additionally needs the lab venv (CPU is fine):

```bash
python -m venv tools/rhythm-lab/.venv
tools/rhythm-lab/.venv/Scripts/python.exe -m pip install torch soundfile beat-this
```

`export` runs the real Kotlin pipeline through
`:app:testDebugUnitTest --tests ...RhythmLabExportTest` and therefore needs a working
Gradle/JDK setup.

## Commands (run from the repository root)

```bash
python tools/rhythm-lab/run_lab.py decode      # mp3 -> 44.1 kHz mono WAV
python tools/rhythm-lab/run_lab.py reference   # cache Beat This! beats (lab venv)
python tools/rhythm-lab/run_lab.py export      # Kotlin pipeline -> app/build/rhythm-lab/out/*.json
python tools/rhythm-lab/run_lab.py analyze     # exported charts vs the reference
python tools/rhythm-lab/run_lab.py evaluate    # tracker proposal vs the reference
python tools/rhythm-lab/run_lab.py propose     # legacy 8-band phase sweep (not the recipe)
python tools/rhythm-lab/run_lab.py all         # decode + reference + export + analyze + evaluate
```

`analyze` evaluates the charts the app currently produces (`master`, `grid_global_*`,
`grid_refined_*`). `evaluate` runs the candidate tracker end to end and writes
`proposed_timing.json`; its numbers are the acceptance result.

## Metric

- A chart division `d` has grid spacing `beatMs / d`. It is compared against the reference
  beats subdivided by `d × ratio`, where `ratio = round(music beat / chart beat) = 2` for
  this song. Both grids then have the same absolute spacing.
- Error per chart grid line is the signed distance to the nearest reference line.
- The evaluation span is **2000 ms … the last reference beat** (171361 ms). The song
  duration is deliberately not used: after ~171.4 s there is only reverb, and extrapolating
  a grid into it would dominate the max error.
- Grid lines are clamped to their timing segment; a fitted line landing within 1 ms of the
  next anchor is not counted twice.
- PASS means `max |error| ≤ 20 ms` at every division.

## Recipe

1. **64-band log-spaced novelty** (N_FFT 2048, hop 512, 32-frame whitening, p95
   normalization). Only 52 distinct bands survive FFT-bin rounding at 44.1 kHz; that is
   the `band_count` the diagnostics report. 8 bands — what the app used — is the reason the
   tracker drifted (see `experiments/band_count.py`).
2. **Ellis DP beat tracker at half the detected BPM** (`start_bpm ≈ 85.15`, tightness 600,
   parabolic peak refinement radius 2). Tracking the music beat, not the chart beat, keeps
   the DP transition prior meaningful.
3. **RMS tail gate** (fraction 0.25): drop trailing tracked beats that only sit in the
   fade-out.
4. **Weighted local-linear fit**: one timing point per tracked beat, ±10 beats, tricube
   weights, iterative residual trimming at `max(45 ms, 3×median)`. The chart beat is half a
   music beat, so `beatMs = slope / 2`.
5. Feed those timing points to the grid deriver.

## Result (2026-11-03, baseline song)

Current charts vs the Beat This! reference:

| chart | div | n | median | p95 | max | within 20 ms |
|---|---|---|---|---|---|---|
| `master` | 1 | 480 | 157.8 | 174.6 | 180.1 | 0.8 % |
| `master` | 2 | 961 | 18.2 | 70.1 | 88.5 | 55.5 % |
| `master` | 4 | 1922 | 16.7 | 40.1 | 44.5 | 61.8 % |
| `grid_refined_*` | 1 | 501 | 112.4 | 165.5 | 178.1 | 8.6 % |
| `grid_refined_*` | 2 | 1002 | 39.6 | 82.7 | 89.3 | 22.3 % |
| `grid_refined_*` | 4 | 2004 | 24.4 | 42.0 | 44.6 | 39.6 % |

Candidate tracker (`run_lab.py evaluate`, spans 2.0–171.361 s):

| div | n | median | p95 | max | within 20 ms |
|---|---|---|---|---|---|
| 1 | 511 | 3.5 | 11.8 | 19.2 | 100 % |
| 2 | 992 | 3.3 | 11.7 | 19.2 | 100 % |
| 4 | 1954 | 3.3 | 11.6 | 19.6 | 100 % |

`experiments/tune_tracker.py` reproduces the sweep and exits non-zero if the selected
defaults stop passing. It also rounds timing points to integer milliseconds (as the chart
format does): max 18.95 / 18.95 / 19.56 ms.

## Risks before porting to Kotlin

- The passing region is sharp in DP tightness: 400 gives max ~42 ms, 600–1000 gives
  ≤19.6 ms. This was tuned on one song; validate on several before shipping.
- Beat This! runs at 50 fps, so even sub-frame interpolation leaves roughly 10–20 ms of
  reference noise. A p95 of ~11.7 ms and a max of ~19.6 ms are close to that floor; chasing
  smaller numbers on this reference is not meaningful.
- The recipe is Python-only today. The Kotlin port must reproduce the 64-band novelty, the
  DP pass, the RMS gate and the weighted fits; that port is the next step and is not
  implemented yet.
