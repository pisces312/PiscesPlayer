"""Chart JSON loading and beat-grid reconstruction."""

import json
from pathlib import Path

import numpy as np


def load_chart(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def timing_points(chart: dict) -> list[tuple[float, float]]:
    return [(float(point["timeMs"]), float(point["beatMs"])) for point in chart.get("timing", [])]


def chart_grid_times(
    chart: dict,
    grid_div: int,
    start_ms: float = 0.0,
    end_ms: float | None = None,
) -> np.ndarray:
    """Every grid line the chart's timing defines, up to the next anchor."""
    timing = timing_points(chart)
    duration = float(chart.get("durationMs", 0))
    limit = duration if end_ms is None else min(duration, end_ms)
    times: list[float] = []
    for index, (anchor, beat_ms) in enumerate(timing):
        if anchor >= limit:
            break
        segment_end = timing[index + 1][0] if index + 1 < len(timing) else limit
        segment_end = min(segment_end, limit)
        step = beat_ms / grid_div
        if step <= 0.0 or segment_end <= anchor:
            continue
        count = int(np.ceil((segment_end - anchor) / step - 1e-9))
        local = anchor + np.arange(count, dtype=np.float64) * step
        # Fitted anchors and beat lengths are rounded independently, so the last line of a
        # segment can land within a fraction of a millisecond before the next anchor. The game
        # never places two notes there; counting both would double a single grid line.
        local = local[local < segment_end - 1.0]
        times.extend(local[local >= start_ms].tolist())
    return np.unique(np.round(np.asarray(times, dtype=np.float64), 3))


def reference_grid(
    beats_ms: np.ndarray,
    grid_div: int,
    start_ms: float,
    end_ms: float,
) -> np.ndarray:
    """Subdivide the reference beat sequence linearly; extend it to both ends."""
    beats = np.sort(np.asarray(beats_ms, dtype=np.float64))
    if beats.size < 2:
        raise ValueError("reference needs at least two beats")
    period = float(np.median(np.diff(beats)))
    while beats[0] > start_ms:
        beats = np.concatenate(([beats[0] - period], beats))
    while beats[-1] < end_ms:
        beats = np.concatenate((beats, [beats[-1] + period]))

    times: list[float] = []
    for index in range(beats.size - 1):
        first, second = beats[index], beats[index + 1]
        step = (second - first) / grid_div
        times.extend((first + np.arange(grid_div, dtype=np.float64) * step).tolist())
    times.append(beats[-1])
    values = np.asarray(times, dtype=np.float64)
    return values[(values >= start_ms) & (values <= end_ms)]


def nearest_signed_errors(query_ms: np.ndarray, reference_ms: np.ndarray) -> np.ndarray:
    """Signed distance from each query line to the nearest reference line."""
    query = np.asarray(query_ms, dtype=np.float64)
    reference = np.sort(np.asarray(reference_ms, dtype=np.float64))
    if reference.size < 2:
        return np.zeros_like(query)
    index = np.clip(np.searchsorted(reference, query), 1, reference.size - 1)
    left = reference[index - 1]
    right = reference[index]
    nearest = np.where(np.abs(query - left) <= np.abs(right - query), left, right)
    return query - nearest
