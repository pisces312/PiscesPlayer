"""Does melody-blind strength ranking starve the melody in ONSET derivation?

Mirrors RhythmChartDeriver.deriveOnsetNative in Python and compares selection variants that
favor pitched (melody-band) material:

  base      current behaviour: score = (0.05 + strength) * beat prior, one global budget
  norm      strength replaced by its percentile rank within the note's voice class per song
            (the "threshold is song-dependent" idea, applied per class)
  pool      split budgets: melody-band and percussion-band onsets selected in separate pools,
            each with its own NPS share, so drums cannot eat the melody's slots
  norm_pool both

Voice classes come from the onset's dominant band (8 log-spaced bands over 21 Hz..22 kHz):
LOW = 0..1 (kick/sub), MELODY = 2..5 (108 Hz..2.4 kHz: vocals, bass lines, leads),
HIGH = 6..7 (hats/cymbals). This is a proxy -- snare cracks can land in MELODY -- so the
final verdict is listening; the numbers show whether the *distribution* moves.

Metrics per song/variant/difficulty: notes kept, melody recall and percussion recall
(against the master's own onsets of that class), melody pair survival (consecutive melody
onsets <= 500 ms apart where both survive: a proxy for "the line stays intact"), and the
band histogram.

Usage (repository root):
    python tools/rhythm-lab/experiments/melody_select.py
"""

import json
from dataclasses import dataclass, replace

import numpy as np

from selfscore import all_songs

CHORD_WINDOW_MS = 70.0
CHORD_MIN_STRENGTH_RATIO = 0.2
BASE_NOTE_SCORE = 0.05
BEAT_PRIOR_AMPLITUDE = 0.25
SCORE_EPSILON = 1e-9
NPS_WINDOW_MS = 1000.0
MELODY_PAIR_MS = 500.0

CLASS_LOW, CLASS_MELODY, CLASS_HIGH = 0, 1, 2
CLASS_NAMES = ["perc-low", "melody", "perc-high"]

PROFILES = {
    "EASY": (2.0, 300.0),
    "NORMAL": (4.0, 150.0),
    "HARD": (7.0, 75.0),
}
# Melody share of the NPS budget in the pooled variants. v1 showed 50/50 starves the
# melody (onset supply is melody-heavy), so the sweep leans melody.
POOL_MELODY_SHARES = (0.5, 0.65, 0.8)
MELODY_PAIR_MS = 500.0
RUN_BONUS = 1.3


@dataclass
class Note:
    id: int
    time: int
    strength: float
    band: int
    lane: int = 0

    @property
    def voice(self) -> int:
        if self.band <= 1:
            return CLASS_LOW
        if self.band <= 5:
            return CLASS_MELODY
        return CLASS_HIGH


def load_song(spec) -> tuple[list[Note], list[tuple[float, float]]]:
    master = json.loads((spec.chart_out_dir / "master.json").read_text(encoding="utf-8"))
    notes = [
        Note(id=int(n["id"]), time=int(n["timeMs"]),
             strength=float(n.get("strength", 0.0)), band=int(n.get("band", -1)),
             lane=int(n.get("lane", 0)))
        for n in master["notes"]
    ]
    timing = [(float(p["timeMs"]), float(p["beatMs"])) for p in master.get("timing", [])]
    return notes, timing


def beat_weight(time_ms: float, timing: list[tuple[float, float]]) -> float:
    point = None
    for anchor, beat in timing:
        if anchor <= time_ms:
            point = (anchor, beat)
        else:
            break
    if point is None:
        point = timing[0] if timing else (0.0, 0.0)
    anchor, beat = point
    if beat <= 0.0:
        return 1.0
    fraction = ((time_ms - anchor) / beat) % 1.0
    return 1.0 + BEAT_PRIOR_AMPLITUDE * np.cos(2.0 * np.pi * fraction)


def normalize_strengths(notes: list[Note]) -> list[Note]:
    """Percentile rank of strength within the note's voice class (per song)."""
    out = []
    for voice in (CLASS_LOW, CLASS_MELODY, CLASS_HIGH):
        class_notes = [n for n in notes if n.voice == voice]
        if not class_notes:
            continue
        order = np.argsort([n.strength for n in class_notes], kind="stable")
        ranks = np.empty(len(class_notes), dtype=np.float64)
        ranks[order] = np.arange(len(class_notes)) / max(1, len(class_notes) - 1)
        for note, rank in zip(class_notes, ranks):
            out.append(replace(note, strength=float(rank)))
    return sorted(out, key=lambda n: n.id)


def melody_run_ids(notes: list[Note]) -> set[int]:
    """Melody onsets that have a melody neighbour within MELODY_PAIR_MS (a run)."""
    melody = sorted((n for n in notes if n.voice == CLASS_MELODY), key=lambda n: n.time)
    runs = set()
    for first, second in zip(melody, melody[1:]):
        if second.time - first.time <= MELODY_PAIR_MS:
            runs.add(first.id)
            runs.add(second.id)
    return runs


def group_onsets(notes: list[Note], timing, run_ids: set[int] | None = None) -> list[dict]:
    run_ids = run_ids or set()
    ordered = sorted(notes, key=lambda n: (n.time, n.id))
    groups = []
    index = 0
    while index < len(ordered):
        first = ordered[index]
        index += 1
        candidates = [first]
        while index < len(ordered) and ordered[index].time - first.time <= CHORD_WINDOW_MS:
            candidates.append(ordered[index])
            index += 1
        candidates.sort(key=lambda n: (-n.strength, n.id))
        primary = candidates[0]
        members = [primary]
        lanes = {primary.band}
        for candidate in candidates[1:]:
            if candidate.strength < primary.strength * CHORD_MIN_STRENGTH_RATIO:
                break
            if candidate.band in lanes:
                continue
            lanes.add(candidate.band)
            members.append(candidate)
        score = 0.0
        for note in members:
            bonus = RUN_BONUS if note.voice == CLASS_MELODY and note.id in run_ids else 1.0
            score += (BASE_NOTE_SCORE + note.strength) * beat_weight(note.time, timing) * bonus
        groups.append({
            "start": min(n.time for n in members),
            "end": max(n.time for n in members),
            "notes": members,
            "score": score,
        })
    return groups


def min_gap_selection(groups: list[dict], min_gap_ms: float) -> list[dict]:
    """Weighted interval scheduling, mirroring RhythmChartDeriver.minGapSelection."""
    n = len(groups)
    best_score = np.zeros(n + 1)
    best_count = np.zeros(n + 1, dtype=int)
    take = np.zeros(n + 1, dtype=bool)
    previous = np.zeros(n + 1, dtype=int)
    ends = [g["end"] for g in groups]
    for i in range(1, n + 1):
        group = groups[i - 1]
        limit = group["start"] - min_gap_ms
        eligible = int(np.searchsorted(ends, limit, side="right"))
        include_score = group["score"] + best_score[eligible]
        include_count = len(group["notes"]) + best_count[eligible]
        skip_score = best_score[i - 1]
        skip_count = best_count[i - 1]
        if abs(include_score - skip_score) <= SCORE_EPSILON:
            include = include_count >= skip_count
        else:
            include = include_score > skip_score
        if include:
            best_score[i] = include_score
            best_count[i] = include_count
            take[i] = True
            previous[i] = eligible
        else:
            best_score[i] = skip_score
            best_count[i] = skip_count
            previous[i] = i - 1
    selected = []
    i = n
    while i > 0:
        if take[i]:
            selected.append(groups[i - 1])
            i = int(previous[i])
        else:
            i -= 1
    selected.reverse()
    return selected


def enforce_density(groups: list[dict], nps_cap: float) -> list[dict]:
    """Admission by 1 s window cap, mirroring RhythmChartDeriver.enforceDensity."""
    per_window = max(1, int(nps_cap))
    kept = []
    window: list[float] = []
    head = 0
    for group in groups:
        times = sorted(n.time for n in group["notes"])
        fits = True
        for time_ms in times:
            while head < len(window) and window[head] <= time_ms - NPS_WINDOW_MS:
                head += 1
            accepted = len(window) - head
            pending = sum(1 for t in times if time_ms - NPS_WINDOW_MS < t <= time_ms)
            if accepted + pending > per_window:
                fits = False
                break
        if fits:
            kept.append(group)
            window.extend(times)
    return kept


def select(notes: list[Note], timing, nps_cap: float, min_gap_ms: float,
           run_ids: set[int] | None = None) -> list[Note]:
    groups = min_gap_selection(group_onsets(notes, timing, run_ids), min_gap_ms)
    groups = enforce_density(groups, nps_cap)
    emitted = [n for group in groups for n in group["notes"]]
    return sorted(emitted, key=lambda n: n.time)


def select_pooled(notes: list[Note], timing, nps_cap: float, min_gap_ms: float,
                  melody_share: float, run_ids: set[int] | None = None) -> list[Note]:
    """Split budgets: melody-class and percussion-class selected independently."""
    melody = [n for n in notes if n.voice == CLASS_MELODY]
    perc = [n for n in notes if n.voice != CLASS_MELODY]
    picked = select(melody, timing, nps_cap * melody_share, min_gap_ms, run_ids)
    picked += select(perc, timing, nps_cap * (1.0 - melody_share), min_gap_ms)
    return sorted(picked, key=lambda n: n.time)


def metrics(master: list[Note], picked: list[Note]) -> dict:
    picked_ids = {n.id for n in picked}
    melody_master = [n for n in master if n.voice == CLASS_MELODY]
    perc_master = [n for n in master if n.voice != CLASS_MELODY]
    melody_kept = sum(1 for n in melody_master if n.id in picked_ids)
    perc_kept = sum(1 for n in perc_master if n.id in picked_ids)
    pairs_total = pairs_kept = 0
    for first, second in zip(melody_master, melody_master[1:]):
        if second.time - first.time > MELODY_PAIR_MS:
            continue
        pairs_total += 1
        if first.id in picked_ids and second.id in picked_ids:
            pairs_kept += 1
    histogram = np.zeros(8, dtype=int)
    for n in picked:
        if 0 <= n.band < 8:
            histogram[n.band] += 1
    return {
        "notes": len(picked),
        "melody_recall": melody_kept / max(1, len(melody_master)),
        "perc_recall": perc_kept / max(1, len(perc_master)),
        "pair_survival": pairs_kept / max(1, pairs_total),
        "histogram": histogram,
    }


def main() -> None:
    for difficulty, (nps_cap, min_gap_ms) in PROFILES.items():
        print(f"\n==== {difficulty} (nps<= {nps_cap}, minGap {min_gap_ms:.0f} ms)")
        print(f"{'song':<18} {'variant':<10} {'notes':>5} {'melody%':>8} {'perc%':>7} "
              f"{'pairs%':>7}  band histogram (0..7)")
        for spec in all_songs():
            master, timing = load_song(spec)
            run_ids = melody_run_ids(master)
            variants = {
                "base": lambda: select(master, timing, nps_cap, min_gap_ms),
                "norm": lambda: select(normalize_strengths(master), timing, nps_cap, min_gap_ms),
            }
            for share in POOL_MELODY_SHARES:
                variants[f"pool{int(share * 100)}"] = (
                    lambda s=share: select_pooled(master, timing, nps_cap, min_gap_ms, s)
                )
            variants["run65"] = lambda: select_pooled(
                master, timing, nps_cap, min_gap_ms, 0.65, run_ids)
            variants["norm_run65"] = lambda: select_pooled(
                normalize_strengths(master), timing, nps_cap, min_gap_ms, 0.65, run_ids)
            for name, run in variants.items():
                picked = run()
                m = metrics(master, picked)
                hist = " ".join(f"{h:3d}" for h in m["histogram"])
                print(f"{spec.song_id:<18} {name:<10} {m['notes']:5d} "
                      f"{m['melody_recall']*100:7.1f}% {m['perc_recall']*100:6.1f}% "
                      f"{m['pair_survival']*100:6.1f}%  [{hist}]")


if __name__ == "__main__":
    main()
