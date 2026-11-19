# 语音识别（ASR）

本地中文语音转文字，基于 **sherpa-onnx + SenseVoiceSmall（int8 ONNX）**。

- 纯 onnxruntime，CPU 即可，无需 PyTorch
- 中文 / 粤语 / 英 / 日 / 韩，自带逆文本归一化(ITN)
- 接口：`POST /asr`，body = 16kHz 单声道 16-bit PCM WAV 字节，返回 `{"text": "..."}`

## 模型下载（不入 git，约 227MB）

模型放在项目根的 `models/` 下，已在 `.gitignore` 中忽略。部署/换机器时执行：

```bash
mkdir -p models && cd models
curl -L -o sv.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2
tar xjf sv.tar.bz2 && rm sv.tar.bz2
```

得到 `models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/{model.int8.onnx,tokens.txt}`。
`server/asr/recognizer.py` 用 glob 自动定位该目录。

## 依赖

```bash
pip install sherpa-onnx numpy   # 已加入 server/requirements.txt
```

## 切换到云端 ASR（可选）

若想换成火山引擎/讯飞等云端 ASR 提升准确率，只需改 `server/asr/recognizer.py` 里的
`transcribe_wav()` 改为调云 API 即可，**Android 端无需改动**（仍是录音上传到 `/asr`）。
