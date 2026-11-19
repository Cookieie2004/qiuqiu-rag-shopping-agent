"""项目配置 — API Key 等敏感信息从 .env 读取"""

import os
from dotenv import load_dotenv

load_dotenv()

# 豆包大模型
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3/")
LLM_MODEL = os.getenv("LLM_MODEL", "")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")

# Embedding（自行选型，豆包不提供 Key）
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "")
EMBEDDING_API_KEY = os.getenv("EMBEDDING_API_KEY", "")

# 向量数据库
VECTOR_DB_PATH = os.getenv("VECTOR_DB_PATH", "./data/chroma_db")
