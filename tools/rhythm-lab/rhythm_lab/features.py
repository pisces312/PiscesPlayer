"""特征提取：onset / tempo / beat / section energy（纯 numpy/scipy DSP）。

时间轴一律毫秒，且映射回原始歌曲（解码重采样不产生累计漂移）。
STFT 起点参数：n_fft=1024, hop=256 @22050Hz ≈ 11.6ms/帧。
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view
from scipy.signal import find_peaks

from rhythm_lab.model import (
    ANALYZER_VERSION,
    SCHEMA_VERSION,
    AudioAnalysis,
    Beat,
    Onset,
    Section,
)

N_FFT = 1024
HOP = 256

MIN_BPM = 60.0
MAX_BPM = 180.0
TEMPO_PRIOR_CENTER_BPM = 120.0
TEMPO_PRIOR_SIGMA_OCT = 0.7

# onset 峰值拾取
PEAK_MIN_GAP_MS = 90.0
PEAK_DELTA = 0.35  # 高于局部均值的增量（env 已按 95 分位归一）
PEAK_PROMINENCE = 0.15

# 拍点吸附
BEAT_SNAP_TOLERANCE_MS = 70.0

# 置信度
LOW_CONFIDENCE_THRESHOLD = 0.35
MIN_ONSET_DENSITY_PER_S = 0.2

SECTION_WINDOW_MS = 2000


@dataclass
class OnsetEnvelope:
    times_ms: np.ndarray  # (T,) 每帧中心时刻
    values: np.ndarray  # (T,) 归一化通量包络


def _frame_times_ms(n_frames: int, sr: int) -> np.ndarray:
    return (np.arange(n_frames) * HOP + N_FFT / 2) / sr * 1000.0


def onset_envelope(samples: np.ndarray, sr: int) -> OnsetEnvelope:
    """频谱通量 onset 包络。"""
    if samples.size < N_FFT:
        return OnsetEnvelope(np.zeros(0), np.zeros(0))
    frames = sliding_window_view(samples, N_FFT)[::HOP]
    win = np.hanning(N_FFT).astype(np.float64)
    spec = np.fft.rfft(frames * win, axis=1)
    logmag = np.log1p(np.abs(spec) * 10.0)
    flux = np.diff(logmag, axis=0, prepend=logmag[:1])
    flux = np.maximum(flux, 0.0).sum(axis=1)
    norm = np.percentile(flux, 95)
    env = flux / norm if norm > 1e-12 else flux
    env = np.clip(env, 0.0, None)
    return OnsetEnvelope(_frame_times_ms(len(env), sr), env)


def _parabolic_refine(values: np.ndarray, i: int) -> float:
    if 0 < i < len(values) - 1:
        a, b, c = float(values[i - 1]), float(values[i]), float(values[i + 1])
        denom = a - 2.0 * b + c
        if abs(denom) > 1e-12:
            delta = 0.5 * (a - c) / denom
            if abs(delta) <= 1.0:
                return i + delta
    return float(i)


def pick_onsets(env: OnsetEnvelope) -> list[Onset]:
    """自适应阈值 + 局部峰值，抑制重复 onset。"""
    if env.values.size == 0:
        return []
    values = env.values
    if env.times_ms.size >= 2:
        frame_ms = float(env.times_ms[1] - env.times_ms[0])
    else:
        frame_ms = HOP / 22050.0 * 1000.0
    # 局部均值阈值（约 1s 窗）
    win = max(1, int(round(1000.0 / frame_ms)))
    kernel = np.ones(win) / win
    local_mean = np.convolve(values, kernel, mode="same")
    distance = max(1, int(round(PEAK_MIN_GAP_MS / frame_ms)))
    peaks, props = find_peaks(
        values,
        height=local_mean + PEAK_DELTA,
        prominence=PEAK_PROMINENCE,
        distance=distance,
    )
    onsets: list[Onset] = []
    for p in peaks:
        t_idx = _parabolic_refine(values, int(p))
        time_ms = float(env.times_ms[0]) + t_idx * frame_ms
        onsets.append(Onset(int(round(time_ms)), float(values[p])))
    return onsets


def _fft_autocorr(x: np.ndarray) -> np.ndarray:
    x = x - x.mean()
    n = 1 << (2 * len(x) - 1).bit_length()
    spec = np.fft.rfft(x, n)
    ac = np.fft.irfft(spec * np.conj(spec), n)[: len(x)]
    if ac[0] > 0:
        ac = ac / ac[0]
    return ac


@dataclass
class TempoEstimate:
    bpm: float
    phase_ms: float
    confidence: float
    period_frames: float


def estimate_tempo(env: OnsetEnvelope, sr: int = 22050) -> TempoEstimate:
    """自相关 tempo（60–180 BPM）+ 拍相位 + 置信度，显式处理半/倍速歧义。"""
    if env.values.size < 32:
        return TempoEstimate(TEMPO_PRIOR_CENTER_BPM, 0.0, 0.0, 0.0)
    fps = sr / HOP  # ≈86.13 帧/秒
    values = env.values
    ac = _fft_autocorr(values)

    lag_min = max(2, int(np.floor(fps * 60.0 / MAX_BPM)))
    lag_max = min(len(ac) - 2, int(np.ceil(fps * 60.0 / MIN_BPM)))
    if lag_max <= lag_min:
        return TempoEstimate(TEMPO_PRIOR_CENTER_BPM, 0.0, 0.0, 0.0)

    lags = np.arange(lag_min, lag_max + 1)
    bpm_of_lag = 60.0 * fps / lags
    # log-normal 先验（log2 域），偏好 ~120 BPM
    prior = np.exp(-0.5 * ((np.log2(bpm_of_lag / TEMPO_PRIOR_CENTER_BPM)) / TEMPO_PRIOR_SIGMA_OCT) ** 2)
    scores = ac[lags] * prior

    best_i = int(np.argmax(scores))

    # 半/倍速歧义：在搜索范围内取「有实质支持（ac ≥ 0.2×max）的最细局部峰」
    # = 基础速度。均匀点击/鼓点夹具由此锁到夹具声明的 BPM 而非半速；
    # 若无局部峰达标则退回先验加权的全局最优。
    seg = ac[lag_min : lag_max + 1]
    ac_max = float(seg.max())
    best_lag = float(lags[best_i])
    if ac_max > 1e-6:
        peaks, _ = find_peaks(seg, height=0.2 * ac_max)
        if peaks.size:
            best_lag = float(lag_min + int(peaks[0]))  # 最小 lag = 最快速度

    # 亚帧细化：自相关抛物线插值（整数量化在 150 BPM 附近误差可达 >0.5 BPM）
    li = int(round(best_lag))
    if lag_min < li < lag_max:
        a, b, c = float(ac[li - 1]), float(ac[li]), float(ac[li + 1])
        denom = a - 2.0 * b + c
        if abs(denom) > 1e-12:
            delta = 0.5 * (a - c) / denom
            if abs(delta) <= 1.0:
                best_lag = li + delta

    period_frames = float(best_lag)
    bpm = 60.0 * fps / period_frames

    # 拍相位：0..period 内取包络折叠和最大
    phase_frames = _estimate_phase(values, period_frames)
    phase_ms = phase_frames / fps * 1000.0

    # 置信度：自相关峰显著性 × 拍点命中率
    seg = ac[lag_min : lag_max + 1]
    prominence = float(scores[best_i] / (np.median(seg) + 1e-9))
    conf_ac = float(np.clip((prominence - 1.0) / 4.0, 0.0, 1.0))
    hit_rate = _beat_hit_rate(env, bpm, phase_ms)
    confidence = float(np.clip(0.5 * conf_ac + 0.5 * hit_rate, 0.0, 1.0))
    return TempoEstimate(bpm, phase_ms, confidence, period_frames)


def _estimate_phase(values: np.ndarray, period_frames: float) -> float:
    n = len(values)
    p_int = max(1, int(round(period_frames)))
    best_s = -1.0
    best_phi = 0.0
    for phi in range(p_int):
        idx = np.round(phi + np.arange(0, (n - phi) / period_frames) * period_frames).astype(int)
        idx = idx[(idx >= 0) & (idx < n)]
        if idx.size == 0:
            continue
        s = float(values[idx].sum())
        if s > best_s:
            best_s = s
            best_phi = float(phi)
    # 亚帧细化：±1 帧抛物线
    if period_frames > 2:
        p2 = max(1, int(round(period_frames)))
        s_lo = _phase_score(values, max(0.0, best_phi - 1.0), period_frames)
        s_hi = _phase_score(values, min(p2 - 1e-6, best_phi + 1.0), period_frames)
        denom = s_lo - 2 * best_s + s_hi
        if abs(denom) > 1e-12:
            delta = 0.5 * (s_lo - s_hi) / denom
            if abs(delta) <= 1.0:
                best_phi = float(np.clip(best_phi + delta, 0.0, period_frames))
    return best_phi


def _phase_score(values: np.ndarray, phi: float, period_frames: float) -> float:
    n = len(values)
    idx = np.round(phi + np.arange(0, (n - phi) / period_frames) * period_frames).astype(int)
    idx = idx[(idx >= 0) & (idx < n)]
    return float(values[idx].sum()) if idx.size else 0.0


def _beat_hit_rate(env: OnsetEnvelope, bpm: float, phase_ms: float) -> float:
    if env.values.size == 0 or bpm <= 0:
        return 0.0
    period_ms = 60000.0 / bpm
    times = env.times_ms
    t = phase_ms
    hits = 0
    total = 0
    while t <= times[-1]:
        total += 1
        i = int(np.searchsorted(times, t))
        lo, hi = max(0, i - 2), min(len(times), i + 3)
        if lo < hi and env.values[lo:hi].max() >= 0.2:
            hits += 1
        t += period_ms
    return hits / total if total else 0.0


def track_beats(env: OnsetEnvelope, bpm: float, phase_ms: float, duration_ms: int) -> list[Beat]:
    """均匀拍网格 + 局部吸附（吸附容差内向包络峰靠拢）。"""
    if bpm <= 0:
        return []
    period_ms = 60000.0 / bpm
    times = env.times_ms
    beats: list[Beat] = []
    t = phase_ms
    # phase 可能超出第一个周期，回退到 [0, period)
    while t - period_ms >= 0:
        t -= period_ms
    while t < duration_ms:
        if t >= 0:
            snapped = t
            if times.size:
                i = int(np.searchsorted(times, t))
                lo, hi = max(0, i - 3), min(len(times), i + 4)
                if lo < hi:
                    window_vals = env.values[lo:hi]
                    j = lo + int(np.argmax(window_vals))
                    cand = float(times[j])
                    if abs(cand - t) <= BEAT_SNAP_TOLERANCE_MS and window_vals.max() >= 0.15:
                        snapped = cand
            beats.append(Beat(int(round(snapped))))
        t += period_ms
    return beats


def section_energies(samples: np.ndarray, sr: int, duration_ms: int) -> list[Section]:
    """固定 2s 窗的相对能量分段（不做结构标签猜测）。"""
    if samples.size == 0 or duration_ms <= 0:
        return []
    win = int(sr * SECTION_WINDOW_MS / 1000.0)
    hop = win
    sections: list[Section] = []
    max_rms = 0.0
    raw: list[tuple[int, int, float]] = []
    pos = 0
    while pos < samples.size:
        chunk = samples[pos : pos + win]
        if chunk.size < win // 2:
            break
        rms = float(np.sqrt(np.mean(chunk.astype(np.float64) ** 2)))
        start_ms = int(round(pos / sr * 1000.0))
        end_ms = int(round(min(pos + win, samples.size) / sr * 1000.0))
        raw.append((start_ms, end_ms, rms))
        max_rms = max(max_rms, rms)
        pos += hop
    for start_ms, end_ms, rms in raw:
        energy = rms / max_rms if max_rms > 1e-9 else 0.0
        sections.append(Section(start_ms, end_ms, round(energy, 4)))
    return sections


def analyze_samples(samples: np.ndarray, sr: int, duration_ms: int,
                    fingerprint: str) -> AudioAnalysis:
    env = onset_envelope(samples, sr)
    onsets = pick_onsets(env)
    tempo = estimate_tempo(env, sr)
    beats = track_beats(env, tempo.bpm, tempo.phase_ms, duration_ms)
    sections = section_energies(samples, sr, duration_ms)

    density = len(onsets) / (duration_ms / 1000.0) if duration_ms > 0 else 0.0
    low_conf = (
        tempo.confidence < LOW_CONFIDENCE_THRESHOLD
        or density < MIN_ONSET_DENSITY_PER_S
    )
    return AudioAnalysis(
        schema_version=SCHEMA_VERSION,
        analyzer_version=ANALYZER_VERSION,
        source_fingerprint=fingerprint,
        duration_ms=duration_ms,
        sample_rate=sr,
        bpm=round(tempo.bpm, 3),
        beat_phase_ms=round(tempo.phase_ms, 1),
        confidence=round(tempo.confidence, 3),
        low_confidence=low_conf,
        onset_count=len(onsets),
        beats=beats,
        onsets=onsets,
        sections=sections,
    )


def analyze(decoded) -> AudioAnalysis:
    """对 DecodedAudio 运行完整分析。"""
    return analyze_samples(
        decoded.samples,
        decoded.sample_rate,
        decoded.duration_ms,
        decoded.fingerprint,
    )
