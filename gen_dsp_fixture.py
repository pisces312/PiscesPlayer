#!/usr/bin/env python
"""生成 DSP 谱面生成的黄金夹具（app/src/test/resources/rhythm/）。

用法:
    python gen_dsp_fixture.py                 # 重新生成 wav + expected.json
    python gen_dsp_fixture.py --check         # 只比对：夹具是否与当前参考实现一致

两套夹具（合成音频，不含任何版权素材）:
  dsp-beats-120bpm  20 s / 120 BPM，kick(每拍) + snare(2/4 拍) + hihat(半拍) + bass/pad
                    最密只有半拍 ⇒ 母谱（全部 onset）与 1/4 网格数量相同，验证不了分层
  dsp-dense-120bpm  同上，但 hihat 加密到 32 分音符（62.5 ms，仍高于 50 ms 峰值局部半径）
                    ⇒ 母谱约为困难档的两倍，用来验证 raw > hard > normal > easy

expected.json 同时记录:
  raw         = 参考实现 pick_peaks 的全部 onset 直接成谱（母谱的期望值）
  EASY/NORMAL/HARD = div 1/2/4 的网格输出

Kotlin 侧 RhythmChartGeneratorGoldenTest 用 ±15 ms 容差匹配率 >=95% 判定，
不逐条严格相等（Python round 是 half-to-even，Kotlin 是 half-up）。
"""
import argparse
import json
import os
import subprocess
import sys

import numpy as np
from scipy.io import wavfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "app", "src", "test", "resources", "rhythm")

DEFAULT_CHARTGEN = r"D:/3rd-party-projects/RhythmChartLab/rhythm-lab/fourier/fourier_chartgen.py"

SR = 44100
DURATION_S = 20.0
BPM = 120.0
SEED = 7

DIFFICULTIES = [("EASY", 1), ("NORMAL", 2), ("HARD", 4)]

FIXTURES = [
    ("dsp-beats-120bpm", {"hat_subdivisions": 2, "hat_gain": 0.18}),
    ("dsp-dense-120bpm", {"hat_subdivisions": 8, "hat_gain": 0.30}),
]


def synth(duration=DURATION_S, sample_rate=SR, bpm=BPM, seed=SEED,
          hat_subdivisions=2, hat_gain=0.18):
    """Deterministic drum/bass loop. Same recipe as RhythmChartLab synth_and_bench.py."""
    total = int(duration * sample_rate)
    out = np.zeros(total)
    rng = np.random.default_rng(seed)
    beat = 60.0 / bpm

    # kick on every beat
    index = 0
    while index * beat < duration:
        start = int(index * beat * sample_rate)
        length = int(0.18 * sample_rate)
        if start + length > total:
            break
        sweep = 55 * np.exp(-np.arange(length) / (0.03 * sample_rate)) + 42
        envelope = np.exp(-np.arange(length) / (0.045 * sample_rate))
        out[start:start + length] += 0.9 * np.sin(2 * np.pi * np.cumsum(sweep) / sample_rate) * envelope
        index += 1

    # snare on beats 2 and 4
    index = 0
    while index * beat < duration:
        if index % 4 in (1, 3):
            start = int(index * beat * sample_rate)
            length = int(0.12 * sample_rate)
            if start + length <= total:
                noise = rng.standard_normal(length)
                noise = np.diff(np.concatenate([[0], noise]))
                noise = np.convolve(noise, np.ones(6) / 6, mode="same")
                out[start:start + length] += 0.45 * noise * np.exp(-np.arange(length) / (0.03 * sample_rate))
        index += 1

    # hihat on every 1/hat_subdivisions beat (2 = eighths, 8 = thirty-seconds)
    index = 0
    while index * beat / hat_subdivisions < duration:
        start = int(index * beat / hat_subdivisions * sample_rate)
        length = int(0.05 * sample_rate)
        if start + length <= total:
            noise = rng.standard_normal(length)
            noise = np.diff(np.concatenate([[0], noise, [0]]))[:length]
            noise = np.diff(np.concatenate([[0], noise, [0]]))[:length]
            decay = 0.008 if hat_subdivisions > 2 else 0.012
            out[start:start + length] += hat_gain * noise * np.exp(-np.arange(length) / (decay * sample_rate))
        index += 1

    # bass + pad, one chord every two beats
    scale = [220, 246.94, 293.66, 329.63, 392.0]
    for index in range(int(duration / (beat * 2))):
        start = int(index * beat * 2 * sample_rate)
        length = int(beat * 2 * sample_rate)
        if start + length > total:
            break
        time = np.arange(length) / sample_rate
        fundamental = scale[index % 5] / 2
        envelope = np.minimum(1, time / 0.01) * np.exp(-time / (beat * 1.5))
        out[start:start + length] += 0.25 * np.sin(2 * np.pi * fundamental * time) * envelope
        for harmonic, amplitude in ((1, 0.10), (2, 0.05), (3, 0.03)):
            out[start:start + length] += (
                amplitude * np.sin(2 * np.pi * scale[index % 5] * harmonic * time) * envelope * 0.6
            )

    out = out / (np.max(np.abs(out)) + 1e-9) * 0.9
    return (out * 32767).astype(np.int16)


def load_reference(chartgen):
    """Import the reference implementation so the RAW expectation uses its own constants."""
    directory = os.path.dirname(os.path.abspath(chartgen))
    if directory not in sys.path:
        sys.path.insert(0, directory)
    import fourier_chartgen as reference
    return reference


def build_raw(reference, wav_path, lanes=4):
    """Every detected onset, unquantized — the expectation for the master chart."""
    samples, sr = reference.load_audio(wav_path)
    spectrum = reference.stft_mag(samples)
    bands = reference.band_energy(spectrum)
    novelty = reference.novelty(bands)
    indices, _ = reference.pick_peaks(novelty, sr)

    def frame_to_ms(frame):
        # Same centre-of-window convention as the Kotlin pipeline.
        return (frame * reference.HOP + reference.N_FFT - reference.HOP / 2) / sr * 1000.0

    lane_list = reference.assign_lanes(indices, bands, lanes)
    return [
        {"t": round(frame_to_ms(int(index)), 1), "lane": int(lane)}
        for index, lane in zip(indices, lane_list)
    ]


def run_chartgen(chartgen, wav_path, div, work_dir, name):
    osu_path = os.path.join(work_dir, f"{name}.osu")
    subprocess.run(
        [sys.executable, chartgen, wav_path, "--lanes", "4", "--div", str(div),
         "--out", osu_path, "--json"],
        check=True,
        capture_output=True,
    )
    json_path = os.path.splitext(osu_path)[0] + ".json"
    with open(json_path, encoding="utf-8") as handle:
        data = json.load(handle)
    return data


def build_expected(chartgen, reference, wav_path):
    expected = {
        "schemaVersion": 1,
        "generator": "RhythmChartLab/rhythm-lab/fourier/fourier_chartgen.py",
        "sampleRate": SR,
        "durationMs": int(DURATION_S * 1000),
        "bpm": None,
        "offsetMs": None,
        "raw": {},
        "difficulties": {},
    }
    raw_notes = build_raw(reference, wav_path)
    expected["raw"] = {"noteCount": len(raw_notes), "notes": raw_notes}

    for name, div in DIFFICULTIES:
        data = run_chartgen(chartgen, wav_path, div, OUT_DIR, name.lower())
        expected["difficulties"][name] = {
            "gridDiv": div,
            "noteCount": len(data["notes"]),
            "notes": [
                {"t": round(float(note["t"]), 1), "lane": int(note["lane"])}
                for note in data["notes"]
            ],
        }
        # Every difficulty is generated with the same BPM, so the first one defines it.
        if expected["bpm"] is None:
            expected["bpm"] = round(float(data["bpm"]), 3)
            expected["offsetMs"] = round(float(data["offset_ms"]), 1)
    return expected


def generate_one(chartgen, reference, stem, synth_kwargs, check_only):
    wav_path = os.path.join(OUT_DIR, f"{stem}.wav")
    expected_path = os.path.join(OUT_DIR, f"{stem}.expected.json")

    if not check_only or not os.path.isfile(wav_path):
        wavfile.write(wav_path, SR, synth(**synth_kwargs))
        print(f"wav      : {wav_path}")

    expected = build_expected(chartgen, reference, wav_path)

    if check_only:
        if not os.path.isfile(expected_path):
            sys.exit(f"{expected_path} missing; run without --check first")
        with open(expected_path, encoding="utf-8") as handle:
            current = json.load(handle)
        if current != expected:
            sys.exit(f"{stem}: fixture is stale — expected.json no longer matches the generator")
        print(f"{stem}: up to date (raw {expected['raw']['noteCount']} notes)")
        return

    with open(expected_path, "w", encoding="utf-8") as handle:
        json.dump(expected, handle, ensure_ascii=False, indent=1)
    print(f"expected : {expected_path}")
    print(f"bpm {expected['bpm']}  offset {expected['offsetMs']} ms")
    print(f"  {'RAW':6s} onsets   {expected['raw']['noteCount']} notes")
    for name, block in expected["difficulties"].items():
        print(f"  {name:6s} div={block['gridDiv']}  {block['noteCount']} notes")

    for name, _ in DIFFICULTIES:
        for candidate in (f"{name.lower()}.osu", f"{name.lower()}.json"):
            leftover = os.path.join(OUT_DIR, candidate)
            if os.path.isfile(leftover):
                os.remove(leftover)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chartgen", default=os.environ.get("RHYTHM_LAB_CHARTGEN", DEFAULT_CHARTGEN))
    parser.add_argument("--check", action="store_true", help="only verify the fixtures are current")
    args = parser.parse_args()

    if not os.path.isfile(args.chartgen):
        sys.exit(f"reference generator not found: {args.chartgen}")

    os.makedirs(OUT_DIR, exist_ok=True)
    reference = load_reference(args.chartgen)
    for stem, synth_kwargs in FIXTURES:
        generate_one(args.chartgen, reference, stem, synth_kwargs, args.check)


if __name__ == "__main__":
    main()
