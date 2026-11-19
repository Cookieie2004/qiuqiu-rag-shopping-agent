"""
数据入库脚本 — 把 products.jsonl 和 chunks.jsonl 灌进 Chroma 向量数据库

双 collection 设计：
- products collection：document 为 search_text（商品核心字段拼接），metadata 存商品属性
- chunks collection：FAQ/Review 独立存储，通过 product_id 关联商品
这样做是为了让商品向量聚焦核心语义，FAQ/评价作为补充信息按需拉取，避免噪声稀释检索精度。

用法：
    export DYLD_LIBRARY_PATH="/opt/homebrew/opt/expat/lib"
    cd rag-shopping-agent
    python -m data.scripts.ingest
"""

import json
import pathlib
import chromadb
from chromadb.utils.embedding_functions import SentenceTransformerEmbeddingFunction
from server.config.settings import EMBEDDING_MODEL as CONFIG_EMBEDDING_MODEL
from server.config.settings import VECTOR_DB_PATH

DATA_DIR = pathlib.Path(__file__).parent.parent / "processed"
DB_DIR = pathlib.Path(VECTOR_DB_PATH)

# 中文 embedding 模型，语义检索精度远好于英文模型
EMBEDDING_MODEL = CONFIG_EMBEDDING_MODEL or "shibing624/text2vec-base-chinese"


def load_jsonl(path: pathlib.Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def ingest_products(client, ef):
    """入库商品。metadata 中预计算 avg_rating，避免每次请求都要关联查询 chunks 算平均分。"""
    products = load_jsonl(DATA_DIR / "products.jsonl")
    print(f"Products: {len(products)} 条商品")

    # 预计算每个商品的全量平均评分
    chunks = load_jsonl(DATA_DIR / "chunks.jsonl")
    from collections import defaultdict
    product_ratings = defaultdict(list)
    for c in chunks:
        if c["type"] == "review" and c.get("rating") is not None:
            product_ratings[c["product_id"]].append(c["rating"])

    col = client.get_or_create_collection("products", embedding_function=ef)

    ids = []
    documents = []
    metadatas = []
    for p in products:
        pid = p["product_id"]
        ids.append(pid)
        documents.append(p["search_text"])
        meta = {
            "name": p["name"],
            "brand": p["brand"],
            "category": p["category"],
            "sub_category": p["sub_category"],
            "price_min": p["price_min"],
            "price_max": p["price_max"],
            "image_path": p["image_path"],
            "skus": json.dumps(p.get("skus", []), ensure_ascii=False),
        }
        if product_ratings[pid]:
            rs = product_ratings[pid]
            meta["avg_rating"] = round(sum(rs) / len(rs), 1)
            meta["review_count"] = len(rs)
        metadatas.append(meta)

    col.upsert(ids=ids, documents=documents, metadatas=metadatas)
    print(f"  → products collection: {col.count()} 条")


def ingest_chunks(client, ef):
    """入库 FAQ/Review 片段，通过 product_id 关联商品。"""
    chunks = load_jsonl(DATA_DIR / "chunks.jsonl")
    print(f"Chunks: {len(chunks)} 条")

    col = client.get_or_create_collection("chunks", embedding_function=ef)

    ids = []
    documents = []
    metadatas = []
    for c in chunks:
        ids.append(c["chunk_id"])
        documents.append(c["content"])
        meta = {
            "product_id": c["product_id"],
            "type": c["type"],
        }
        if c.get("rating") is not None:
            meta["rating"] = c["rating"]
        if c.get("question"):
            meta["question"] = c["question"]
        if c.get("answer"):
            meta["answer"] = c["answer"]
        metadatas.append(meta)

    col.upsert(ids=ids, documents=documents, metadatas=metadatas)
    print(f"  → chunks collection: {col.count()} 条")


def main():
    print(f"Embedding 模型: {EMBEDDING_MODEL}")
    print(f"数据库路径: {DB_DIR}")
    print()

    ef = SentenceTransformerEmbeddingFunction(model_name=EMBEDDING_MODEL)
    client = chromadb.PersistentClient(path=str(DB_DIR))

    ingest_products(client, ef)
    ingest_chunks(client, ef)

    print("\n入库完成！")


if __name__ == "__main__":
    main()
