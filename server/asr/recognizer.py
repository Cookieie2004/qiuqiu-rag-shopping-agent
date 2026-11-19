"""
本地语音识别（ASR）— sherpa-onnx + SenseVoiceSmall（int8 ONNX）

- 纯 onnxruntime，CPU 即可，无需 PyTorch
- 中文/粤语/英/日/韩，自带标点与逆文本归一化(ITN)
- 模型目录：<project>/models/sherpa-onnx-sense-voice-*/

客户端录制 16kHz 单声道 16-bit PCM WAV，POST 到 /asr。
"""

import io
import logging
import re
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

logger = logging.getLogger("uvicorn.error")

# SenseVoice 可能带的特殊标记（语种/情感/事件，如 <|zh|><|NEUTRAL|><|Speech|>）
_TAG_RE = re.compile(r"<\|[^|]*\|>")


def _normalize(text: str) -> str:
    """把识别结果对齐成"和用户手打一样的纯文本"：去特殊标记、折叠空白。

    sherpa-onnx 正常情况下 result.text 已不含标记（标记在 lang/emotion/event 字段），
    这里再兜底一次，保证 /asr 出口永远是干净纯文本，与输入框打字内容同构。
    """
    text = _TAG_RE.sub("", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text

_MODEL_ROOT = Path(__file__).resolve().parent.parent.parent / "models"
_recognizer = None


def _find_model() -> tuple[str, str]:
    """在 models/ 下定位 SenseVoice 的 model(.int8).onnx 与 tokens.txt。"""
    dirs = sorted(_MODEL_ROOT.glob("sherpa-onnx-sense-voice-*"))
    if not dirs:
        raise FileNotFoundError(
            f"未找到 SenseVoice 模型目录（{_MODEL_ROOT}/sherpa-onnx-sense-voice-*）。"
            "请先下载模型，见 server/asr/README。"
        )
    d = dirs[-1]
    model = d / "model.int8.onnx"
    if not model.exists():
        model = d / "model.onnx"
    tokens = d / "tokens.txt"
    if not model.exists() or not tokens.exists():
        raise FileNotFoundError(f"模型文件缺失：{model} / {tokens}")
    return str(model), str(tokens)


def get_recognizer() -> "sherpa_onnx.OfflineRecognizer":
    """懒加载单例（也用于启动预热）。"""
    global _recognizer
    if _recognizer is None:
        model, tokens = _find_model()
        start = time.perf_counter()
        _recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=model,
            tokens=tokens,
            num_threads=2,
            use_itn=True,        # 数字/标点归一化
            language="auto",     # 自动判别中/英等，兼容中英混说的品牌名
            debug=False,
        )
        logger.info("SenseVoice ASR 模型加载完成 %.2fs (%s)", time.perf_counter() - start, model)
    return _recognizer


def warmup() -> None:
    """启动时预热：加载模型 + 跑一段静音，避免首个请求慢。"""
    rec = get_recognizer()
    stream = rec.create_stream()
    stream.accept_waveform(16000, np.zeros(16000, dtype=np.float32))
    rec.decode_stream(stream)


def _decode_wav(wav_bytes: bytes) -> tuple[np.ndarray, int]:
    """解析 16-bit PCM WAV → (float32 [-1,1] 单声道样本, 16kHz)。

    如果原始采样率不是 16kHz（如模拟器录的 44100Hz），自动重采样到 16kHz。
    """
    with wave.open(io.BytesIO(wav_bytes), "rb") as wf:
        n_channels = wf.getnchannels()
        sample_rate = wf.getframerate()
        sampwidth = wf.getsampwidth()
        frames = wf.readframes(wf.getnframes())
    if sampwidth != 2:
        raise ValueError(f"仅支持 16-bit PCM WAV（当前 {sampwidth * 8} bit）")
    data = np.frombuffer(frames, dtype=np.int16)
    if n_channels > 1:
        data = data.reshape(-1, n_channels).mean(axis=1).astype(np.int16)
    samples = data.astype(np.float32) / 32768.0
    # SenseVoice 期望 16kHz，非 16kHz 输入需要重采样
    if sample_rate != 16000:
        logger.info("ASR 重采样 %dHz → 16000Hz（%d 样本）", sample_rate, len(samples))
        new_length = int(len(samples) * 16000 / sample_rate)
        samples = np.interp(
            np.linspace(0, len(samples) - 1, new_length),
            np.arange(len(samples)),
            samples,
        ).astype(np.float32)
        sample_rate = 16000
    return samples, sample_rate


def transcribe_wav(wav_bytes: bytes) -> str:
    """识别一段 WAV 音频，返回文本。"""
    samples, sample_rate = _decode_wav(wav_bytes)

    # 音频归一化：模拟器麦克风音量可能极低，拉满到 90% 峰值
    max_amp = np.max(np.abs(samples))
    logger.info(
        "ASR 音频: %.2fs, %dHz, peak=%.4f, size=%d bytes",
        len(samples) / sample_rate, sample_rate, max_amp, len(wav_bytes),
    )
    if 0 < max_amp < 0.5:
        samples = samples / max_amp * 0.9
        logger.info("ASR 音频增益归一化: peak %.4f → 0.9", max_amp)

    rec = get_recognizer()
    stream = rec.create_stream()
    stream.accept_waveform(sample_rate, samples)
    rec.decode_stream(stream)
    return _normalize(stream.result.text)
