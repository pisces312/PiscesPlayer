"""A/B playtest launcher: export the two NORMAL ONSET selections as osu!mania charts,
convert to the RhythmChartLab playtest JSON format and play them in its D/F/J/K player.

Version A = current selection (drums win the density budget).
Version B = melody-priority selection (3 melody + 1 percussion slots per second).

The .osu files are kept under build/rhythm-lab/preview/osu/ and are real osu!mania
beatmaps (4K) over the baseline song, one red timing line per tracked-beat anchor.

Usage:
    python tools/rhythm-lab/experiments/play_ab.py A
    python tools/rhythm-lab/experiments/play_ab.py B [rebuild] [noplay]

Controls (RhythmChartLab player): D/F/J/K hit, Space/P pause/resume, 1/2/3 difficulty,
- / = adjust offset 5 ms, Q or Esc quit. Switch the IME to English before playing.
"""

import subprocess
import sys
from pathlib import Path

from melody_select import PROFILES, load_song, select, select_pooled
from selfscore import all_songs

REPO_ROOT = Path(__file__).resolve().parents[3]
CHART_LAB = Path(r"D:\3rd-party-projects\RhythmChartLab\rhythm-lab")
SONG_ID = "baseline"
DIFFICULTY = "NORMAL"
LANES = 4
OUT_DIR = REPO_ROOT / "build" / "rhythm-lab" / "preview" / "osu"

VERSIONS = {
    "A": ("A base", lambda m, t, cap, gap: select(m, t, cap, gap)),
    "B": ("B melody", lambda m, t, cap, gap: select_pooled(m, t, cap, gap, 0.8)),
}


def write_osu(path: Path, notes, timing, title: str, audio_filename: str) -> None:
    """osu!mania v14, same layout as RhythmChartLab's dsp_core.to_osu_text."""
    lines = [
        "osu file format v14", "", "[General]",
        f"AudioFilename: {audio_filename}", "Mode: 3", "",
        "[Metadata]", f"Title:{title}", "Artist:Unknown",
        "Creator:PiscesPlayer-lab", "Version:Generated", "",
        "[Difficulty]", f"CircleSize:{LANES}", "OverallDifficulty:8",
        "ApproachRate:5", "HPDrainRate:8", "SliderMultiplier:1.4",
        "SliderTickRate:1", "", "[TimingPoints]",
    ]
    # One red line per tracked-beat anchor; the osu beat is a quarter note = 2 chart beats.
    for anchor, beat_ms in timing:
        lines.append(f"{anchor:.0f},{beat_ms * 2.0:.6f},4,2,1,60,1,0")
    lines += ["", "[HitObjects]"]
    col_w = 512 // LANES
    for note in notes:
        x = col_w // 2 + col_w * (note.lane % LANES)
        lines.append(f"{x},192,{note.time},1,0,0:0:0:0:")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def run_lab(*args: str) -> None:
    subprocess.run([sys.executable, "-m", "rhythm_lab", *args], cwd=CHART_LAB, check=True)


def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1].upper() not in VERSIONS:
        raise SystemExit("usage: play_ab.py A|B [rebuild]")
    version = sys.argv[1].upper()
    flags = {a.lower() for a in sys.argv[2:]}
    rebuild = "rebuild" in flags
    name, build = VERSIONS[version]

    spec = next(s for s in all_songs() if s.song_id == SONG_ID)
    master, timing = load_song(spec)
    nps_cap, min_gap_ms = PROFILES[DIFFICULTY]
    notes = build(master, timing, nps_cap, min_gap_ms)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    osu_path = OUT_DIR / f"{SONG_ID}_{version}.osu"
    if rebuild or not osu_path.is_file():
        write_osu(osu_path, notes, timing, f"Yingfeng {name}", spec.song_path.name)
        print(f"osu  : {osu_path}  ({len(notes)} notes)")

    charts_dir = OUT_DIR / "charts"
    run_lab("convert", str(osu_path), "--out-dir", str(charts_dir),
            "--difficulty", name, "--audio", str(spec.song_path))
    chart_json = charts_dir / f"{name.lower().replace(' ', '_')}.json"

    if "noplay" in flags:
        print(f"chart: {chart_json}")
        return
    print(f"play : {spec.song_path.name}  chart={name}")
    run_lab("play", str(spec.song_path), "--charts", str(chart_json),
            "--difficulty", name.lower(), "--log", "")


if __name__ == "__main__":
    main()
