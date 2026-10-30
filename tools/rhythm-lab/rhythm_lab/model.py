"""分析与谱面数据契约（PC / Android 共用的 JSON 结构，语言无关）。"""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from typing import Any

SCHEMA_VERSION = 1
ANALYZER_VERSION = "dsp-1"
GENERATOR_VERSION = "dsp-1"


@dataclass
class Onset:
    time_ms: int
    strength: float


@dataclass
class Beat:
    time_ms: int


@dataclass
class Section:
    start_ms: int
    end_ms: int
    energy: float  # 0..1 相对整曲能量


@dataclass
class AudioAnalysis:
    schema_version: int
    analyzer_version: str
    source_fingerprint: str
    duration_ms: int
    sample_rate: int
    bpm: float
    beat_phase_ms: float
    confidence: float
    low_confidence: bool
    onset_count: int
    beats: list[Beat] = field(default_factory=list)
    onsets: list[Onset] = field(default_factory=list)
    sections: list[Section] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def to_json(self, indent: int = 2) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False, indent=indent)

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "AudioAnalysis":
        return cls(
            schema_version=d["schemaVersion"] if "schemaVersion" in d else d["schema_version"],
            analyzer_version=d.get("analyzerVersion", d.get("analyzer_version", ANALYZER_VERSION)),
            source_fingerprint=d.get("sourceFingerprint", d.get("source_fingerprint", "")),
            duration_ms=d.get("durationMs", d.get("duration_ms", 0)),
            sample_rate=d.get("sampleRate", d.get("sample_rate", 0)),
            bpm=float(d["bpm"]),
            beat_phase_ms=float(d.get("beatPhaseMs", d.get("beat_phase_ms", 0.0))),
            confidence=float(d.get("confidence", 0.0)),
            low_confidence=bool(d.get("lowConfidence", d.get("low_confidence", False))),
            onset_count=int(d.get("onsetCount", d.get("onset_count", 0))),
            beats=[Beat(int(b["timeMs"] if "timeMs" in b else b["time_ms"])) for b in d.get("beats", [])],
            onsets=[
                Onset(
                    int(o["timeMs"] if "timeMs" in o else o["time_ms"]),
                    float(o["strength"]),
                )
                for o in d.get("onsets", [])
            ],
            sections=[
                Section(
                    int(s["startMs"] if "startMs" in s else s["start_ms"]),
                    int(s["endMs"] if "endMs" in s else s["end_ms"]),
                    float(s["energy"]),
                )
                for s in d.get("sections", [])
            ],
        )

    @classmethod
    def from_json(cls, text: str) -> "AudioAnalysis":
        return cls.from_dict(json.loads(text))


def to_camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(seg.capitalize() for seg in rest)


def analysis_to_camel_dict(a: AudioAnalysis) -> dict[str, Any]:
    """输出 JSON 用 camelCase（与 docs 契约示例一致）。"""
    d = asdict(a)
    out: dict[str, Any] = {}
    for k, v in d.items():
        if k in ("beats", "onsets", "sections"):
            out[to_camel(k)] = [
                {to_camel(sk): sv for sk, sv in item.items()} for item in v
            ]
        else:
            out[to_camel(k)] = v
    return out


@dataclass
class ChartNote:
    id: int
    time_ms: int
    lane: int  # 0..3
    type: str = "tap"


@dataclass
class Chart:
    schema_version: int
    generator_version: str
    source_fingerprint: str
    duration_ms: int
    difficulty: str  # easy | normal | hard
    bpm: float
    seed: int
    notes: list[ChartNote] = field(default_factory=list)
