"""FastAPI service entrypoint."""

import logging
import pathlib

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from server.api.routes.asr import router as asr_router
from server.api.routes.chat import router as chat_router
from server.api.routes.products import router as products_router
from server.rag.retriever import warmup_rag

logger = logging.getLogger("uvicorn.error")

app = FastAPI(title="RAG Shopping Agent API")

# CORS — demo 阶段全放开，App 和后端不同 origin 时不会被拦
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# 路由
app.include_router(chat_router)
app.include_router(products_router)
app.include_router(asr_router)


@app.on_event("startup")
def warmup_rag_on_startup() -> None:
    logger.info("RAG 预热开始")
    try:
        warmup_rag()
    except Exception:
        logger.exception("RAG 预热失败，后续请求仍会尝试正常检索")
    logger.info("ASR 模型预热开始")
    try:
        from server.asr.recognizer import warmup as warmup_asr
        warmup_asr()
    except Exception:
        logger.exception("ASR 预热失败（模型可能未下载），/asr 首次调用会重试加载")

# 静态文件服务（商品图片）— 放在路由后面，避免 /static 拦截其他路径
project_root = pathlib.Path(__file__).parent.parent.parent
data_dir = project_root / "data" / "raw"
if data_dir.exists():
    app.mount("/static", StaticFiles(directory=str(data_dir)), name="static")
