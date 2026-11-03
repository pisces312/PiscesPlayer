"""Error summaries for chart-vs-reference alignment."""

import numpy as np


def summarize(errors: np.ndarray, target_ms: float) -> dict:
    errors = np.asarray(errors, dtype=np.float64)
    if errors.size == 0:
        return {"count": 0}
    absolute = np.abs(errors)
    return {
        "count": int(errors.size),
        "bias_ms": float(np.median(errors)),
        "mean_abs_ms": float(absolute.mean()),
        "median_abs_ms": float(np.median(absolute)),
        "p95_abs_ms": float(np.percentile(absolute, 95)),
        "p99_abs_ms": float(np.percentile(absolute, 99)),
        "max_abs_ms": float(absolute.max()),
        "rms_ms": float(np.sqrt(np.mean(errors ** 2))),
        "within_target_pct": float(100.0 * np.mean(absolute <= target_ms)),
        "pass": bool(absolute.max() <= target_ms),
    }


def format_summary(label: str, stats: dict, target_ms: float) -> str:
    if stats.get("count", 0) == 0:
        return f"{label:<28} no grid lines"
    verdict = "PASS" if stats["pass"] else "FAIL"
    return (
        f"{label:<28} n={stats['count']:4d}  "
        f"bias={stats['bias_ms']:+6.1f}  "
        f"med={stats['median_abs_ms']:5.1f}  "
        f"p95={stats['p95_abs_ms']:5.1f}  "
        f"p99={stats['p99_abs_ms']:5.1f}  "
        f"max={stats['max_abs_ms']:5.1f} ms  "
        f"<= {target_ms:.0f}ms {stats['within_target_pct']:5.1f}%  {verdict}"
    )


def bucket_table(times_ms: np.ndarray, errors: np.ndarray, bucket_s: float = 30.0) -> list[dict]:
    if times_ms.size == 0:
        return []
    first = float(times_ms.min())
    rows: list[dict] = []
    start = first
    while start <= float(times_ms.max()):
        stop = start + bucket_s * 1000.0
        selected = (times_ms >= start) & (times_ms < stop)
        if selected.any():
            local = errors[selected]
            rows.append(
                {
                    "start_s": (start - first) / 1000.0,
                    "mean_ms": float(local.mean()),
                    "median_ms": float(np.median(local)),
                    "max_abs_ms": float(np.abs(local).max()),
                }
            )
        start = stop
    return rows
