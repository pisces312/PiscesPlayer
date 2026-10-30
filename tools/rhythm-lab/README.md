# rhythm-lab — 节奏游戏模式 PC 验证台

> 研发工具，**不打进 APK、不引入 App 依赖**。方案与验收门禁见 `docs/rhythm-game-mode-plan.md`。
> 真实歌曲、分析结果、谱面一律留在本地 `output/`（已 gitignore），只提交合成夹具与代码。

## 环境

- Python ≥ 3.11（本机：`D:\dev\miniconda3\python.exe`，numpy/scipy/pytest）
- FFmpeg（本机：`D:\dev\ffmpeg\bin\ffmpeg.exe`，可用环境变量 `RHYTHM_LAB_FFMPEG` 覆盖）

## 用法（在本目录执行）

```powershell
python -m rhythm_lab analyze <audio...> --out-dir output          # 特征分析 → JSON
python -m rhythm_lab chart <audio> --all-difficulties --out-dir output/charts   # （Phase 1）
python -m rhythm_lab validate output/charts                       # （Phase 1）
python -m rhythm_lab play <audio> --chart output/charts/normal.json            # （Phase 2）
```

## 测试

```powershell
python -m pytest
```

- `tests/test_decode.py` — Gate A：解码、时间轴、无累计漂移
- `tests/test_features.py` — Gate B：合成夹具 BPM/相位/onset 客观指标
