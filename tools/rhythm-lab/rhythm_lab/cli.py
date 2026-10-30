"""命令行入口：analyze / chart / validate / play（后三者 Phase 1/2 落地）。"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from rhythm_lab import audio as audio_mod
from rhythm_lab import features as feat
from rhythm_lab.model import analysis_to_camel_dict


def _cmd_analyze(args: argparse.Namespace) -> int:
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = []
    rc = 0
    for src in args.audio:
        path = Path(src)
        if not path.exists():
            print(f"[error] not found: {path}", file=sys.stderr)
            rc = 1
            continue
        try:
            decoded = audio_mod.decode(path, target_sr=args.sample_rate)
        except Exception as e:  # noqa: BLE001 — 调研工具：单文件失败不中断批量
            print(f"[error] decode failed: {path}: {e}", file=sys.stderr)
            rc = 1
            continue
        analysis = feat.analyze(decoded)
        payload = analysis_to_camel_dict(analysis)
        payload["sourcePath"] = str(path)
        if decoded.source_duration_ms is not None:
            payload["sourceDurationMs"] = round(decoded.source_duration_ms, 1)
        out_path = out_dir / (path.stem + ".analysis.json")
        out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        onset_density = (
            analysis.onset_count / (analysis.duration_ms / 1000.0)
            if analysis.duration_ms
            else 0.0
        )
        summary.append({
            "file": path.name,
            "durationMs": analysis.duration_ms,
            "bpm": analysis.bpm,
            "beatPhaseMs": analysis.beat_phase_ms,
            "confidence": analysis.confidence,
            "lowConfidence": analysis.low_confidence,
            "onsetCount": analysis.onset_count,
            "onsetDensityPerS": round(onset_density, 2),
            "out": str(out_path),
        })
        flag = "  [low-conf]" if analysis.low_confidence else ""
        print(
            f"{path.name}: {analysis.bpm:.1f} BPM, conf={analysis.confidence:.2f}, "
            f"onsets={analysis.onset_count} ({onset_density:.2f}/s), "
            f"{analysis.duration_ms / 1000:.1f}s{flag}"
        )
    (out_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return rc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="rhythm_lab")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_an = sub.add_parser("analyze", help="音频特征分析 → JSON")
    p_an.add_argument("audio", nargs="+", help="音频文件路径（可多个）")
    p_an.add_argument("--out-dir", default="output")
    p_an.add_argument("--sample-rate", type=int, default=22050)
    p_an.set_defaults(func=_cmd_analyze)

    for name, note in (
        ("chart", "三难度谱面生成（Phase 1）"),
        ("validate", "谱面合法性校验（Phase 1）"),
        ("play", "D/F/J/K 可玩预览（Phase 2）"),
    ):
        p = sub.add_parser(name, help=f"{note} — 待实现")
        p.add_argument("args", nargs="*")
        p.set_defaults(func=lambda a, n=name: _not_implemented(n))

    args = parser.parse_args(argv)
    return args.func(args)


def _not_implemented(name: str) -> int:
    print(f"'{name}' 尚未实现（见 docs/rhythm-game-mode-plan.md Phase 1/2）")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
