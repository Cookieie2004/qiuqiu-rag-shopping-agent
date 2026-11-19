"""
对话接口 — 接收用户消息，流式返回 AI 回复 + 商品卡片

SSE 事件格式：
- data: {"type": "text", "content": "..."}      文本片段
- data: {"type": "clarify", "content": {...}}   追问引导卡片
- data: {"type": "product", "content": {...}}    商品卡片
- data: [DONE]                                   结束标记
"""

import asyncio
import json
from typing import Literal, Optional

from fastapi import APIRouter
from pydantic import BaseModel, Field
from sse_starlette.sse import EventSourceResponse

from server.agent.orchestrator import generate_stream

router = APIRouter()


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class ChatRequest(BaseModel):
    query: str
    session_id: str
    history: list[ChatMessage] = Field(default_factory=list)
    image_url: Optional[str ] = None


@router.get("/hello")
async def hello() -> dict[str, str]:
    return {"message": "hello world"}


@router.post("/chat")
async def chat(request: ChatRequest) -> EventSourceResponse:
    async def event_generator():
        history = [{"role": m.role, "content": m.content} for m in request.history]
        loop = asyncio.get_event_loop()
        queue: asyncio.Queue = asyncio.Queue()

        def _run_sync():
            try:
                for event in generate_stream(request.query, history=history):
                    loop.call_soon_threadsafe(queue.put_nowait, event)
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, None)

        loop.run_in_executor(None, _run_sync)

        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=15)
            except asyncio.TimeoutError:
                # 15 秒无数据，发 SSE 心跳注释保活，防止中间网络设备断开连接
                yield {"comment": "keepalive"}
                continue
            if event is None:
                break
            if event["type"] == "done":
                yield {"data": "[DONE]"}
            else:
                yield {"data": json.dumps(event, ensure_ascii=False)}

    return EventSourceResponse(event_generator(), ping=20)
