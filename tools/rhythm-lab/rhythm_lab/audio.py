"""Decode the baseline song and read the 16-bit mono WAV the Kotlin harness consumes."""

import shutil
import subprocess
from pathlib import Path

import numpy as np
from scipy.io import wavfile

FFMPEG_CANDIDATES = (
    "ffmpeg",
    r"D:/dev/ffmpeg9/bin/ffmpeg.exe",
    r"D:/dev/ffmpeg/bin/ffmpeg.exe",
)


def find_ffmpeg() -> str:
    for candidate in FFMPEG_CANDIDATES:
        resolved = shutil.which(candidate) if "/" not in candidate and "\\" not in candidate else candidate
        if resolved and Path(resolved).is_file():
            return resolved
    raise SystemExit("ffmpeg not found; set one of FFMPEG_CANDIDATES in rhythm_lab/audio.py")


def decode(song: Path, wav: Path, sample_rate: int) -> Path:
    if not song.is_file():
        raise SystemExit(f"baseline song not found: {song}")
    wav.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            find_ffmpeg(),
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(song),
            "-ac",
            "1",
            "-ar",
            str(sample_rate),
            "-sample_fmt",
            "s16",
            str(wav),
        ],
        check=True,
    )
    print(f"decoded : {song.name} -> {wav} ({wav.stat().st_size / 1e6:.1f} MB)")
    return wav


def read_wav(path: Path) -> tuple[np.ndarray, int]:
    sample_rate, data = wavfile.read(path)
    if data.ndim > 1:
        data = data.mean(axis=1)
    if data.dtype == np.int16:
        pcm = data.astype(np.float32) / 32768.0
    elif data.dtype == np.int32:
        pcm = (data.astype(np.float64) / 2147483648.0).astype(np.float32)
    else:
        pcm = data.astype(np.float32)
    return pcm, int(sample_rate)
