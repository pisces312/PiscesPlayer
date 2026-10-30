"""Gate B：合成音频客观指标（点击/鼓点夹具，含静音、噪声、强弱变化）。

- 稳定 4/4 夹具 BPM 误差 ≤0.5，不出现半速/倍速误锁；
- 节拍相位中位绝对误差 ≤20ms；
- 清晰 onset 夹具 precision / recall 均 ≥0.90；
- 纯静音不得生成音符。
"""
from __future__ import annotations

import numpy as np
import pytest

from rhythm_lab.features import analyze_samples, onset_envelope, pick_onsets, track_beats

SR = 22050
MATCH_TOLERANCE_S = 0.05


def synth_clicks(
    bpm: float,
    duration_s: float = 20.0,
    phase_s: float = 0.5,
    sr: int = SR,
    amplitude: float = 0.5,
    noise_level: float = 0.0,
    dynamic_range: bool = False,
    seed: int = 0,
) -> tuple[np.ndarray, np.ndarray]:
    """点击夹具：5ms 指数衰减 1kHz 正弦。返回 (pcm, 点击时刻秒)。"""
    rng = np.random.default_rng(seed)
    n = int(sr * duration_s)
    pcm = np.zeros(n, dtype=np.float64)
    click_times = np.arange(phase_s, duration_s - 0.2, 60.0 / bpm)
    click_len = int(0.005 * sr)
    tt = np.arange(click_len) / sr
    base = np.sin(2 * np.pi * 1000 * tt) * np.exp(-tt * 500)
    for i, ct in enumerate(click_times):
        amp = amplitude
        if dynamic_range:
            amp = amplitude * (0.4 + 0.6 * ((i % 4) == 0))  # 强弱交替（模拟强拍）
        s = int(ct * sr)
        pcm[s : s + click_len] += amp * base
    if noise_level > 0:
        pcm += rng.normal(0, noise_level, n)
    return pcm.astype(np.float32), click_times


def synth_drums(
    bpm: float,
    duration_s: float = 20.0,
    phase_s: float = 0.5,
    sr: int = SR,
) -> tuple[np.ndarray, np.ndarray]:
    """鼓点夹具：kick 每拍 + snare 落 2/4 拍（稳定 4/4 流行/舞曲模式）。"""
    rng = np.random.default_rng(42)
    n = int(sr * duration_s)
    pcm = np.zeros(n, dtype=np.float64)
    beat_times = np.arange(phase_s, duration_s - 0.2, 60.0 / bpm)
    kick_len = int(0.05 * sr)
    snare_len = int(0.04 * sr)
    tk = np.arange(kick_len) / sr
    kick = np.sin(2 * np.pi * 60 * tk) * np.exp(-tk * 60)
    # 击打器咔嗒：宽带起音，避免纯正弦 kick 的通量远弱于噪声 snare（实测 16:1 → 半拍自相关塌缩）
    click_n = int(0.005 * sr)
    kick[:click_n] += 0.6 * rng.normal(0, 1, click_n)
    ts = np.arange(snare_len) / sr
    snare = rng.normal(0, 1, snare_len) * np.exp(-ts * 80)
    for i, bt in enumerate(beat_times):
        s = int(bt * sr)
        pcm[s : s + kick_len] += 0.5 * kick
        if i % 2 == 1:
            pcm[s : s + snare_len] += 0.4 * snare
    return pcm.astype(np.float32), beat_times


def synth_drums_alternating(
    bpm: float,
    duration_s: float = 20.0,
    phase_s: float = 0.5,
    sr: int = SR,
) -> tuple[np.ndarray, np.ndarray]:
    """kick/snare 交替的摇滚模式（强弱对比大 → 半/倍速歧义的已知难例）。"""
    rng = np.random.default_rng(42)
    n = int(sr * duration_s)
    pcm = np.zeros(n, dtype=np.float64)
    beat_times = np.arange(phase_s, duration_s - 0.2, 60.0 / bpm)
    kick_len = int(0.05 * sr)
    snare_len = int(0.04 * sr)
    tk = np.arange(kick_len) / sr
    kick = np.sin(2 * np.pi * 60 * tk) * np.exp(-tk * 60)
    ts = np.arange(snare_len) / sr
    snare = rng.normal(0, 1, snare_len) * np.exp(-ts * 80)
    for i, bt in enumerate(beat_times):
        s = int(bt * sr)
        if i % 2 == 0:
            pcm[s : s + kick_len] += 0.5 * kick
        else:
            pcm[s : s + snare_len] += 0.5 * snare
    return pcm.astype(np.float32), beat_times


def onset_pr(detected_ms: list[int], expected_s: np.ndarray) -> tuple[float, float]:
    """±50ms 一对一贪心匹配的 precision / recall。"""
    det = sorted(np.array(detected_ms, dtype=np.float64) / 1000.0)
    exp = list(np.asarray(expected_s, dtype=np.float64))
    matched = 0
    used = [False] * len(exp)
    for d in det:
        best_j, best_err = -1, MATCH_TOLERANCE_S
        for j, e in enumerate(exp):
            if used[j]:
                continue
            err = abs(d - e)
            if err <= best_err:
                best_err, best_j = err, j
        if best_j >= 0:
            used[best_j] = True
            matched += 1
    precision = matched / len(det) if len(det) else 0.0
    recall = matched / len(exp) if len(exp) else 0.0
    return precision, recall


def phase_median_error_ms(beats_ms: list[int], expected_s: np.ndarray) -> float:
    """拍点对最近真实点击时刻的中位绝对误差（ms）。"""
    exp_ms = np.asarray(expected_s) * 1000.0
    errs = []
    for b in beats_ms:
        errs.append(float(np.min(np.abs(exp_ms - b))))
    return float(np.median(errs)) if errs else float("inf")


@pytest.mark.parametrize("bpm", [60.0, 90.0, 120.0, 150.0, 180.0])
def test_bpm_accuracy_clicks(bpm: float):
    pcm, _ = synth_clicks(bpm)
    analysis = analyze_samples(pcm, SR, int(len(pcm) / SR * 1000), "fixture")
    assert abs(analysis.bpm - bpm) <= 0.5, f"est {analysis.bpm} vs {bpm}"


@pytest.mark.parametrize("bpm", [90.0, 120.0, 150.0])
def test_bpm_accuracy_drums(bpm: float):
    pcm, _ = synth_drums(bpm)
    analysis = analyze_samples(pcm, SR, int(len(pcm) / SR * 1000), "fixture")
    assert abs(analysis.bpm - bpm) <= 0.5, f"est {analysis.bpm} vs {bpm}"


def test_bpm_no_octave_mislock_with_accent():
    """强弱交替（强拍更响）下仍锁定基础 BPM，而非半速/倍速。"""
    pcm, _ = synth_clicks(120.0, dynamic_range=True)
    analysis = analyze_samples(pcm, SR, int(len(pcm) / SR * 1000), "fixture")
    assert abs(analysis.bpm - 120.0) <= 0.5


@pytest.mark.parametrize("bpm", [90.0, 120.0, 150.0])
def test_beat_phase_median_error(bpm: float):
    pcm, clicks = synth_clicks(bpm)
    analysis = analyze_samples(pcm, SR, int(len(pcm) / SR * 1000), "fixture")
    err = phase_median_error_ms([b.time_ms for b in analysis.beats], clicks)
    assert err <= 20.0, f"phase median error {err:.1f}ms"


@pytest.mark.parametrize("noise", [0.0, 0.02])
def test_onset_precision_recall(noise: float):
    pcm, clicks = synth_clicks(120.0, noise_level=noise)
    env = onset_envelope(pcm, SR)
    onsets = pick_onsets(env)
    p, r = onset_pr([o.time_ms for o in onsets], clicks)
    assert p >= 0.90, f"precision {p:.2f}"
    assert r >= 0.90, f"recall {r:.2f}"


def test_onset_precision_recall_dynamic():
    pcm, clicks = synth_clicks(120.0, dynamic_range=True, noise_level=0.01)
    env = onset_envelope(pcm, SR)
    onsets = pick_onsets(env)
    p, r = onset_pr([o.time_ms for o in onsets], clicks)
    assert p >= 0.90 and r >= 0.90, f"P={p:.2f} R={r:.2f}"


def test_alternating_rock_pattern_octave_ambiguity():
    """kick/snare 交替摇滚模式：基础或半速皆算锁定（已知歧义，Gate D 观察项）。"""
    pcm, _ = synth_drums_alternating(120.0)
    analysis = analyze_samples(pcm, SR, int(len(pcm) / SR * 1000), "fixture")
    ok = abs(analysis.bpm - 120.0) <= 0.5 or abs(analysis.bpm - 60.0) <= 0.5
    assert ok, f"est {analysis.bpm} not in {{60, 120}} octave"


def test_silence_generates_no_notes():
    pcm = np.zeros(SR * 10, dtype=np.float32)
    analysis = analyze_samples(pcm, SR, 10000, "fixture")
    assert analysis.onset_count == 0
    assert analysis.low_confidence is True


def test_track_beats_grid_period():
    pcm, clicks = synth_clicks(120.0)
    env = onset_envelope(pcm, SR)
    beats = track_beats(env, 120.0, 500.0, int(len(pcm) / SR * 1000))
    times = [b.time_ms for b in beats]
    # 网格从 phase 回推可多出 t=0 拍（夹具点击从 0.5s 起），计数容差 ±1
    assert abs(len(times) - len(clicks)) <= 1
    err = phase_median_error_ms(times, clicks)
    assert err <= 20.0
