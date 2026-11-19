"""
语音识别接口

1. POST /asr          — 客户端上传 WAV 字节，返回识别文本（真机用）
2. POST /asr/start    — 让服务端开始用电脑麦克风录音（模拟器用）
3. POST /asr/stop     — 让服务端停止录音并返回识别文本
"""

import asyncio
import logging
import time

import numpy as np
from typing import Optional
from fastapi import APIRouter, HTTPException, Request
from openai import OpenAI

from server.asr.recognizer import transcribe_wav, get_recognizer, _normalize
from server.config.settings import LLM_API_KEY, LLM_MODEL, LLM_BASE_URL

logger = logging.getLogger("uvicorn.error")
router = APIRouter()

_llm = OpenAI(api_key=LLM_API_KEY, base_url=LLM_BASE_URL)

_ASR_FIX_PROMPT = (
    "你是语音识别后处理器。用户通过语音输入了一段话（购物场景），"
    "语音识别结果可能有错别字和缺少标点。请你：\n"
    "1. 纠正明显的同音错别字（如 有皮→油皮，干皮→干皮，经化→精华，方筛→防晒）\n"
    "2. 加上正确的标点符号\n"
    "3. 不要改变原意，不要添加或删除内容，不要扩写\n"
    "直接输出纠正后的文本，不要加任何解释。"
)


def _fix_asr_text(raw: str) -> str:
    """用 LLM 纠正 ASR 错别字并加标点。"""
    if not raw or len(raw) <= 1:
        return raw
    try:
        resp = _llm.chat.completions.create(
            model=LLM_MODEL,
            messages=[
                {"role": "system", "content": _ASR_FIX_PROMPT},
                {"role": "user", "content": raw},
            ],
            temperature=0,
            max_tokens=200,
        )
        fixed = resp.choices[0].message.content.strip()
        if fixed:
            logger.info("ASR 纠错: %r → %r", raw, fixed)
            return fixed
    except Exception:
        logger.exception("ASR LLM 纠错失败，返回原文")
    return raw

# ── 原有：客户端上传 WAV ──

@router.post("/asr")
async def asr(request: Request) -> dict[str, str]:
    data = await request.body()
    if not data:
        raise HTTPException(status_code=400, detail="empty audio body")
    try:
        t0 = time.perf_counter()
        text = transcribe_wav(data)
        logger.info("ASR %d bytes → %r (%.2fs)", len(data), text, time.perf_counter() - t0)
        return {"text": text}
    except Exception as e:  # noqa: BLE001
        logger.exception("ASR 失败")
        raise HTTPException(status_code=500, detail=f"asr failed: {e}")


# ── 新增：服务端用电脑麦克风录音 ──

_SAMPLE_RATE = 16000
_recording = False
_chunks: list[np.ndarray] = []
_stream = None  # Optional[sd.InputStream], sounddevice 延迟导入


def _audio_callback(indata, frames, time_info, status):
    if status:
        logger.warning("sounddevice 状态: %s", status)
    _chunks.append(indata[:, 0].copy())


@router.post("/asr/start")
async def asr_start() -> dict[str, str]:
    import sounddevice as sd
    global _recording, _stream
    if _recording:
        # 上一轮没正常结束，先强制清理
        try:
            if _stream:
                _stream.abort()
                _stream.close()
        except Exception:
            pass
        _stream = None
        _recording = False
        _chunks.clear()

    _chunks.clear()
    _stream = sd.InputStream(
        samplerate=_SAMPLE_RATE,
        channels=1,
        dtype="float32",
        callback=_audio_callback,
    )
    _stream.start()
    _recording = True
    logger.info("ASR 服务端录音开始")
    return {"status": "recording"}


@router.post("/asr/stop")
async def asr_stop() -> dict[str, str]:
    global _recording, _stream
    if not _recording:
        return {"text": "", "error": "not_recording"}

    _recording = False
    if _stream:
        # abort 比 stop 更快，不等回调结束
        try:
            _stream.abort()
            _stream.close()
        except Exception:
            pass
        _stream = None

    if not _chunks:
        return {"text": "", "error": "no_audio"}

    samples = np.concatenate(_chunks)
    _chunks.clear()
    duration = len(samples) / _SAMPLE_RATE
    peak = float(np.max(np.abs(samples)))
    logger.info("ASR 服务端录音: %.2fs, peak=%.4f", duration, peak)

    if duration < 0.3:
        return {"text": "", "error": "too_short"}

    # 归一化
    if 0 < peak < 0.5:
        samples = samples / peak * 0.9

    # 识别放到线程池，不阻塞事件循环
    def _recognize():
        rec = get_recognizer()
        s = rec.create_stream()
        s.accept_waveform(_SAMPLE_RATE, samples)
        rec.decode_stream(s)
        return _normalize(s.result.text)

    t0 = time.perf_counter()
    text = await asyncio.get_event_loop().run_in_executor(None, _recognize)
    logger.info("ASR 服务端识别: %r (%.2fs)", text, time.perf_counter() - t0)

    # LLM 纠错 + 加标点
    if text:
        text = await asyncio.get_event_loop().run_in_executor(None, _fix_asr_text, text)

    return {"text": text}
