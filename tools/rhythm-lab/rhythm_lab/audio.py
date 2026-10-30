"""FFmpeg 解码与文件指纹。

解码统一为单声道 float32 PCM（默认 22050 Hz），时间轴一律映射回原始歌曲毫秒。
"""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

DEFAULT_SAMPLE_RATE = 22050

# 本机安装位置（研发机固定路径），可用环境变量覆盖，PATH 兜底。
_KNOWN_FFMPEG_DIRS = (
    r"D:\dev\ffmpeg\bin",
    r"D:\dev\ffmpeg9\bin",
)


@dataclass
class DecodedAudio:
    samples: np.ndarray  # float32 mono
    sample_rate: int
    duration_ms: int
    source_path: str
    fingerprint: str
    source_duration_ms: float | None = field(default=None)


def _find_tool(name: str, env_var: str) -> str:
    env = os.environ.get(env_var)
    if env and Path(env).exists():
        return env
    exe = name + (".exe" if os.name == "nt" else "")
    for d in _KNOWN_FFMPEG_DIRS:
        cand = Path(d) / exe
        if cand.exists():
            return str(cand)
    found = shutil.which(name)
    if found:
        return found
    raise RuntimeError(f"{name} not found; set {env_var} or add it to PATH")


def find_ffmpeg() -> str:
    return _find_tool("ffmpeg", "RHYTHM_LAB_FFMPEG")


def find_ffprobe() -> str:
    return _find_tool("ffprobe", "RHYTHM_LAB_FFPROBE")


def file_fingerprint(path: str | Path) -> str:
    """文件指纹：大小 + 全文 sha256（截断 32 hex）。缓存键组成部分。"""
    p = Path(path)
    h = hashlib.sha256()
    h.update(str(p.stat().st_size).encode("utf-8"))
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:32]


def probe_duration_ms(path: str | Path, ffprobe: str | None = None) -> float:
    """容器声明时长（ms）。与解码时长对照用（Gate A）。"""
    fp = ffprobe or find_ffprobe()
    out = subprocess.run(
        [fp, "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", str(path)],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=True,
    ).stdout.strip()
    return float(out) * 1000.0


def find_ape_payload_offset(path: str | Path) -> int | None:
    """ID3v2 前缀的 APE：返回 'MAC ' 载荷偏移；不匹配返回 None。

    实测存在 ID3v2 标签被写在 Monkey's Audio 前面的文件，ffmpeg APE demuxer
    只认文件头的 'MAC '，会整文件报 Invalid data。此函数定位剥离点。
    """
    with open(path, "rb") as f:
        head = f.read(10)
        if len(head) < 10 or head[:3] != b"ID3":
            return None
        tag_size = (
            ((head[6] & 0x7F) << 21)
            | ((head[7] & 0x7F) << 14)
            | ((head[8] & 0x7F) << 7)
            | (head[9] & 0x7F)
        )
        offset = 10 + tag_size
        f.seek(offset)
        if f.read(4) == b"MAC ":
            return offset
    return None


def _decode_via_ffmpeg(ff: str, path: Path, target_sr: int) -> np.ndarray:
    cmd = [
        ff, "-v", "error", "-i", str(path),
        "-f", "f32le", "-ac", "1", "-ar", str(target_sr), "-",
    ]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(
            f"ffmpeg decode failed for {path}: {proc.stderr.decode('utf-8', 'replace')}"
        )
    samples = np.frombuffer(proc.stdout, dtype=np.float32).copy()
    if samples.size == 0:
        raise RuntimeError(f"ffmpeg decoded 0 samples for {path}")
    return samples


def decode(
    path: str | Path,
    target_sr: int = DEFAULT_SAMPLE_RATE,
    ffmpeg: str | None = None,
) -> DecodedAudio:
    """解码任意 FFmpeg 可读音频为单声道 float32 PCM。

    对「ID3v2 前缀 + APE 载荷」的文件自动剥离标签到临时文件后解码。
    指纹始终基于原始文件。
    """
    ff = ffmpeg or find_ffmpeg()
    p = Path(path)
    if not p.exists():
        raise RuntimeError(f"audio file not found: {p}")
    ape_offset = find_ape_payload_offset(p)
    tmp: Path | None = None
    try:
        if ape_offset is not None:
            fd, tmp_name = tempfile.mkstemp(suffix=".ape")
            os.close(fd)
            tmp = Path(tmp_name)
            with open(p, "rb") as fin, open(tmp, "wb") as fout:
                fin.seek(ape_offset)
                shutil.copyfileobj(fin, fout)
            samples = _decode_via_ffmpeg(ff, tmp, target_sr)
        else:
            samples = _decode_via_ffmpeg(ff, p, target_sr)
    finally:
        if tmp is not None:
            tmp.unlink(missing_ok=True)
    duration_ms = int(round(samples.size / target_sr * 1000.0))
    try:
        src_dur = probe_duration_ms(p, ffprobe=_find_tool("ffprobe", "RHYTHM_LAB_FFPROBE"))
    except Exception:
        src_dur = None
    return DecodedAudio(
        samples=samples,
        sample_rate=target_sr,
        duration_ms=duration_ms,
        source_path=str(p),
        fingerprint=file_fingerprint(p),
        source_duration_ms=src_dur,
    )
