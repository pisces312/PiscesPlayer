"""Render A/B chart previews as audio: the song with a click on every chart note.

Version A = current ONSET selection (drums win the density budget).
Version B = melody-priority selection (split melody/percussion slots; at NORMAL that is
3 melody + 1 percussion slots per second).

Melody-band notes get a bright ping, percussion-band notes a dull noise tock, so the
melody line is audible as a ping sequence. The song itself plays underneath unchanged.

Output: build/rhythm-lab/preview/preview_{A_base,B_melody}.wav

Usage (repository root):
    python tools/rhythm-lab/experiments/preview_audio.py
"""

from pathlib import Path

import numpy as np
from scipy.io import wavfile

from melody_select import CLASS_MELODY, PROFILES, load_song, select, select_pooled
from selfscore import all_songs

SONG_ID = "baseline"
DIFFICULTY = "NORMAL"
OUT_DIR = Path(__file__).resolve().parents[3] / "build" / "rhythm-lab" / "preview"
PITCH_HZ = 1318.0
PING_SECONDS = 0.06
PING_DECAY = 0.018
PING_AMPLITUDE = 0.22
TOCK_SECONDS = 0.03
TOCK_DECAY = 0.008
TOCK_AMPLITUDE = 0.30


def make_clicks(sample_rate: int) -> dict[str, np.ndarray]:
    t_ping = np.arange(int(PING_SECONDS * sample_rate)) / sample_rate
    ping = PING_AMPLITUDE * np.sin(2.0 * np.pi * PITCH_HZ * t_ping) * np.exp(-t_ping / PING_DECAY)
    t_tock = np.arange(int(TOCK_SECONDS * sample_rate)) / sample_rate
    rng = np.random.default_rng(20261103)
    tock = TOCK_AMPLITUDE * rng.standard_normal(t_tock.size) * np.exp(-t_tock / TOCK_DECAY)
    return {"ping": ping, "tock": tock}


def render(base: np.ndarray, notes, clicks: dict[str, np.ndarray], sample_rate: int) -> np.ndarray:
    out = base.copy()
    for note in notes:
        start = int(note.time * sample_rate / 1000.0)
        if start < 0 or start >= out.size:
            continue
        click = clicks["ping"] if note.voice == CLASS_MELODY else clicks["tock"]
        end = min(out.size, start + click.size)
        out[start:end] += click[: end - start]
    return np.clip(out, -1.0, 1.0)


def main() -> None:
    spec = next(s for s in all_songs() if s.song_id == SONG_ID)
    master, timing = load_song(spec)
    nps_cap, min_gap_ms = PROFILES[DIFFICULTY]
    variants = {
        "A_base": select(master, timing, nps_cap, min_gap_ms),
        "B_melody": select_pooled(master, timing, nps_cap, min_gap_ms, 0.8),
    }

    wav_path = spec.decoded_wav
    sample_rate, pcm = wavfile.read(wav_path)
    base = pcm.astype(np.float64) / 32768.0
    if base.ndim > 1:
        base = base.mean(axis=1)
    clicks = make_clicks(sample_rate)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, notes in variants.items():
        melody = sum(1 for n in notes if n.voice == CLASS_MELODY)
        perc = len(notes) - melody
        audio = render(base, notes, clicks, sample_rate)
        out_path = OUT_DIR / f"preview_{name}.wav"
        wavfile.write(out_path, sample_rate, (audio * 32767.0).astype(np.int16))
        print(f"{name}: {len(notes)} notes ({melody} melody-band pings, {perc} perc tocks)")
        print(f"  -> {out_path}")


if __name__ == "__main__":
    main()
