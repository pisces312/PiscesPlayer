"""Gate A：解码与时间轴。

- WAV / MP3 / FLAC / M4A / Ogg 样本均可解码；
- 解码后时长与源时长误差在正常范围；
- 合成点击音在不同采样率、声道下时间戳无累计漂移（常量编解码偏移允许，漂移不允许）。
"""
from __future__ import annotations

import subprocess
from pathlib import Path

import numpy as np
import pytest

from rhythm_lab.audio import decode, find_ape_payload_offset, find_ffmpeg, probe_duration_ms

SR = 22050
FPS = 1000  # 1ms 粒度能量包络


def make_click_pcm(
    sr: int = SR,
    duration_s: float = 10.0,
    bpm: float = 120.0,
    phase_s: float = 0.5,
    n_channels: int = 1,
    amplitude: float = 0.5,
) -> tuple[np.ndarray, np.ndarray]:
    """返回 (pcm float32, 点击时刻秒数组)。点击 = 5ms 指数衰减 1kHz 正弦。"""
    n = int(sr * duration_s)
    t = np.arange(n) / sr
    pcm = np.zeros((n, n_channels), dtype=np.float32)
    click_times = np.arange(phase_s, duration_s - 0.1, 60.0 / bpm)
    click_len = int(0.005 * sr)
    tt = np.arange(click_len) / sr
    click = (amplitude * np.sin(2 * np.pi * 1000 * tt) * np.exp(-tt * 500)).astype(np.float32)
    for ct in click_times:
        s = int(ct * sr)
        for ch in range(n_channels):
            pcm[s : s + click_len, ch] += click
    mono = pcm.mean(axis=1)
    return mono.astype(np.float32), click_times


def write_wav(path: Path, samples: np.ndarray, sr: int = SR) -> None:
    import wave

    data = (np.clip(samples, -1, 1) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(data.tobytes())


def encode_via_ffmpeg(src_wav: Path, dst: Path) -> None:
    subprocess.run(
        [find_ffmpeg(), "-y", "-v", "error", "-i", str(src_wav), str(dst)],
        check=True,
    )


def energy_onset_times(samples: np.ndarray, sr: int, thresh: float = 0.25) -> np.ndarray:
    """样本级 onset：|x| 平滑包络的组起点（秒）。

    仅用于解码漂移测量，不做特征算法评估。组起点避免 1ms 网格量化伪影。
    """
    from scipy.ndimage import uniform_filter1d

    smooth = uniform_filter1d(np.abs(samples.astype(np.float64)), size=max(1, int(0.001 * sr)))
    if smooth.max() <= 0:
        return np.zeros(0)
    above = smooth > thresh * smooth.max()
    starts = []
    i = 0
    n = len(above)
    while i < n:
        if above[i]:
            j = i
            while j < n and above[j]:
                j += 1
            starts.append(i / sr)
            i = j
        else:
            i += 1
    return np.array(starts)


def max_drift_after_offset(detected: np.ndarray, expected: np.ndarray) -> float:
    """对齐常量偏移后，逐点残差的最大绝对值（秒）。衡量累计漂移。"""
    if len(detected) != len(expected) or len(detected) < 3:
        return float("inf")
    residuals = detected - expected
    offset = np.median(residuals)
    return float(np.max(np.abs(residuals - offset)))


@pytest.fixture(scope="module")
def click_wav(tmp_path_factory) -> Path:
    d = tmp_path_factory.mktemp("gatea")
    pcm, _ = make_click_pcm()
    path = d / "clicks.wav"
    write_wav(path, pcm)
    return path


def test_decode_wav_duration(click_wav: Path):
    dec = decode(click_wav)
    assert dec.sample_rate == SR
    assert abs(dec.duration_ms - 10000) <= 20
    src_ms = probe_duration_ms(click_wav)
    assert abs(dec.duration_ms - src_ms) <= max(20.0, src_ms * 0.02)


def test_decode_sample_count_matches_target_rate(click_wav: Path):
    for target in (22050, 44100):
        dec = decode(click_wav, target_sr=target)
        assert abs(dec.samples.size / target - 10.0) <= 0.02


@pytest.mark.parametrize("ext", ["mp3", "flac", "m4a", "ogg"])
def test_encoded_formats_no_cumulative_drift(click_wav: Path, tmp_path: Path, ext: str):
    _, expected = make_click_pcm()
    dst = tmp_path / f"clicks.{ext}"
    encode_via_ffmpeg(click_wav, dst)
    dec = decode(dst)
    # 时长对照（编解码器延迟/填充允许误差 100ms）
    assert abs(dec.duration_ms - 10000) <= 100
    detected = energy_onset_times(dec.samples, dec.sample_rate)
    assert len(detected) == len(expected), (
        f"{ext}: detected {len(detected)} vs expected {len(expected)}"
    )
    drift = max_drift_after_offset(detected, expected)
    assert drift < 0.005, f"{ext}: cumulative drift {drift * 1000:.2f}ms"


def test_decode_error_on_missing(tmp_path: Path):
    with pytest.raises(RuntimeError):
        decode(tmp_path / "nope.mp3")


def test_find_ape_payload_offset_id3_prefix(tmp_path: Path):
    """ID3v2 前缀 + MAC 载荷 → 定位剥离点；裸 MAC / 非 APE → None。"""
    tag_body = b"\x00" * 100
    size = len(tag_body)
    id3 = bytearray(b"ID3\x03\x00\x00")
    id3 += bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F, (size >> 7) & 0x7F, size & 0x7F])
    f1 = tmp_path / "id3_ape.ape"
    f1.write_bytes(bytes(id3) + tag_body + b"MAC \x82\x0f\x00\x00" + b"\x00" * 8)
    assert find_ape_payload_offset(f1) == 10 + size

    f2 = tmp_path / "bare.ape"
    f2.write_bytes(b"MAC \x82\x0f\x00\x00" + b"\x00" * 8)
    assert find_ape_payload_offset(f2) is None

    f3 = tmp_path / "id3_mp3.mp3"
    f3.write_bytes(bytes(id3) + tag_body + b"\xff\xfb" + b"\x00" * 8)
    assert find_ape_payload_offset(f3) is None
