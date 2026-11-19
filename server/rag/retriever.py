from typing import Optional
import json
"""
RAG 检索模块 — 从 Chroma 向量数据库检索匹配商品和问答片段
"""

import hashlib
import logging
import pathlib
import re as _cache_re
import time
from collections import OrderedDict

import chromadb
from chromadb.utils.embedding_functions import SentenceTransformerEmbeddingFunction
from sentence_transformers import CrossEncoder
from server.config.settings import EMBEDDING_MODEL as CONFIG_EMBEDDING_MODEL
from server.config.settings import VECTOR_DB_PATH

logger = logging.getLogger("uvicorn.error")

DB_DIR = pathlib.Path(VECTOR_DB_PATH)

# ── 缓存基础设施 ─────────────────────────────────────────────

# Embedding 缓存：避免相同文本重复计算向量（LRU，上限 1024 条）
_embedding_cache: OrderedDict[str, list] = OrderedDict()
_EMBEDDING_CACHE_MAX = 1024

# RAG 检索缓存：query 归一化后作为 key
_retrieve_cache: OrderedDict[str, dict] = OrderedDict()
_RETRIEVE_CACHE_MAX = 128

def _normalize_query(q: str) -> str:
    """归一化 query：去空格/标点差异、统一小写"""
    q = q.lower().strip()
    q = _cache_re.sub(r'[\s，。！？、·\-]+', ' ', q)
    return q

def _cache_key(query: str, n_products: int, n_chunks: int) -> str:
    norm = _normalize_query(query)
    return f"{norm}|{n_products}|{n_chunks}"

def cached_retrieve(
    query: str, n_products: int = 3, n_chunks: int = 5, *, filters: dict | None = None
) -> dict:
    """带 LRU 缓存的 retrieve 包装"""
    key = _cache_key(query, n_products, n_chunks)
    if filters:
        key += "|" + json.dumps(filters, sort_keys=True, ensure_ascii=False)
    if key in _retrieve_cache:
        _retrieve_cache.move_to_end(key)
        logger.info("RAG 缓存命中 query=%r", query)
        return _retrieve_cache[key]
    result = retrieve(query, n_products=n_products, n_chunks=n_chunks, filters=filters)
    _retrieve_cache[key] = result
    if len(_retrieve_cache) > _RETRIEVE_CACHE_MAX:
        _retrieve_cache.popitem(last=False)
    return result

def cached_retrieve_scenario(query: str, scenario_key: str) -> dict:
    """带缓存的场景检索包装"""
    key = f"scenario|{scenario_key}|{_normalize_query(query)}"
    if key in _retrieve_cache:
        _retrieve_cache.move_to_end(key)
        logger.info("场景缓存命中 scenario=%r query=%r", scenario_key, query)
        return _retrieve_cache[key]
    result = retrieve_scenario(query, scenario_key)
    _retrieve_cache[key] = result
    if len(_retrieve_cache) > _RETRIEVE_CACHE_MAX:
        _retrieve_cache.popitem(last=False)
    return result

# ── 品类关键词 → sub_category 硬过滤 ─────────────────────────
# 当 query 中明确包含品类关键词时，用 ChromaDB metadata filter 强制限定 sub_category
# 解决 embedding 模型对相近品类（笔记本vs平板、精华vs化妆水）区分度不足的问题
# 注意：长关键词优先匹配（"笔记本电脑" 优先于 "笔记本"）
_SUBCATEGORY_KEYWORDS = {
    # 数码
    "笔记本电脑": "笔记本电脑",
    "笔记本": "笔记本电脑",
    "laptop": "笔记本电脑",
    "平板电脑": "平板电脑",
    "平板": "平板电脑",
    "ipad": "平板电脑",
    "手机": "智能手机",
    "耳机": "真无线耳机",
    # 护肤 — 易混淆品类
    "精华液": "精华",
    "精华": "精华",
    "化妆水": "化妆水",
    "护肤水": "化妆水",
    "爽肤水": "化妆水",
    "柔肤水": "化妆水",
    "防晒霜": "防晒",
    "防晒": "防晒",
    "面霜": "面霜",
    "面膜": "面膜",
    "洗面奶": "洁面",
    "洁面": "洁面",
    "洁面乳": "洁面",
    "眼霜": "眼霜",
    "卸妆": "卸妆",
    "粉底液": "粉底液",
    "粉底": "粉底液",
    "蜜粉": "蜜粉",
    "散粉": "蜜粉",
    "唇釉": "唇釉",
    "口红": "唇釉",
    "眉笔": "眉笔",
    # 鞋
    "跑步鞋": "跑步鞋",
    "跑鞋": "跑步鞋",
    "篮球鞋": "篮球鞋",
    "徒步鞋": "徒步鞋",
    "登山鞋": "徒步鞋",
    # 服装
    "瑜伽裤": "瑜伽裤",
    "卫衣": "卫衣",
    # 食品饮料
    "咖啡": "咖啡",
    "方便面": "方便食品",
    "泡面": "方便食品",
}


def _detect_subcategory(query: str) -> Optional[str ]:
    """从 query 中检测品类关键词，返回对应的 sub_category。
    长关键词优先匹配，避免"笔记本电脑"被"笔记本"截断。"""
    q_lower = query.lower()
    # 按关键词长度降序排列，确保长词优先匹配
    for keyword in sorted(_SUBCATEGORY_KEYWORDS.keys(), key=len, reverse=True):
        if keyword.lower() in q_lower:
            subcat = _SUBCATEGORY_KEYWORDS[keyword]
            logger.info("品类关键词命中: %r → sub_category=%r", keyword, subcat)
            return subcat
    return None


# ── 商品别名/昵称映射 ──────────────────────────────────────
# 用户常用的昵称 → 实际搜索关键词（品牌+品类+特征）
_ALIASES = {
    "小棕瓶": "雅诗兰黛 精华 特润修护肌活精华露",
    "小黑瓶": "兰蔻 精华 肌底液",
    "神仙水": "SK-II 护肤精华露 化妆水",
    "红腰子": "资生堂 红妍肌活精华露",
    "大红瓶": "玉兰油 新生塑颜金纯面霜",
    "紫铁罐": "资生堂 红妍肌活精华",
    "双抗精华": "珀莱雅 双抗精华 弹润透亮",
    "安耐晒": "安热沙 防晒",
    "大粉水": "兰蔻 清滢柔肤水 大粉水",
    "飞马": "Nike Pegasus 跑步鞋",
    "ub": "adidas Ultraboost 跑步鞋",
    "碳板跑鞋": "特步 160X 碳板竞速跑鞋",
}


def _expand_aliases(query: str) -> tuple[str, bool]:
    """将用户 query 中的昵称替换为完整搜索词。返回 (扩展后query, 是否命中别名)"""
    q_lower = query.lower()
    expansions = []
    for alias, expansion in _ALIASES.items():
        if alias.lower() in q_lower:
            logger.info("别名扩展: %r → 追加 %r", alias, expansion)
            expansions.append(expansion)
    if expansions:
        return f"{query} {' '.join(expansions)}", True
    return query, False


EMBEDDING_MODEL = CONFIG_EMBEDDING_MODEL or "shibing624/text2vec-base-chinese"
RERANKER_MODEL = "BAAI/bge-reranker-base"

# 单例，避免重复加载模型
_client = None
_ef = None
_reranker: CrossEncoder | None = None


def _get_client():
    global _client, _ef
    if _client is None:
        _ef = SentenceTransformerEmbeddingFunction(model_name=EMBEDDING_MODEL)
        _client = chromadb.PersistentClient(path=str(DB_DIR))
    return _client, _ef


def _get_reranker() -> CrossEncoder:
    global _reranker
    if _reranker is None:
        logger.info("加载 Reranker 模型: %s", RERANKER_MODEL)
        _reranker = CrossEncoder(RERANKER_MODEL, max_length=256)
        logger.info("Reranker 模型加载完成")
    return _reranker


def rerank(query: str, documents: list[dict], text_key: str = "search_text", top_k: int | None = None) -> list[dict]:
    """用 cross-encoder 对粗召回结果重排序。

    Args:
        query: 用户 query
        documents: 粗召回结果列表，每个 dict 必须有 text_key 指定的字段
        text_key: 用于 rerank 的文本字段名
        top_k: 返回前 k 个，None 则返回全部（已排序）

    Returns:
        按 reranker 分数降序排列的结果
    """
    if not documents:
        return documents

    ranker = _get_reranker()
    pairs = [(query, doc.get(text_key, "")) for doc in documents]
    scores = ranker.predict(pairs)

    for doc, score in zip(documents, scores):
        doc["rerank_score"] = float(score)

    ranked = sorted(documents, key=lambda d: d["rerank_score"], reverse=True)
    if top_k:
        ranked = ranked[:top_k]
    return ranked


def search_products(query: str, n_results: int = 5, where: dict = None) -> list[dict]:
    """
    向量检索商品。

    Args:
        query: 用户输入
        n_results: 返回条数
        where: Chroma metadata filter，如 {"category": "美妆护肤"}

    Returns:
        匹配商品列表，每条含 id, name, brand, category, price 等
    """
    client, ef = _get_client()
    col = client.get_collection("products", embedding_function=ef)

    # embedding 缓存（LRU）：避免相同 query 重复计算向量
    if query in _embedding_cache:
        _embedding_cache.move_to_end(query)
        kwargs = {"query_embeddings": [_embedding_cache[query]], "n_results": n_results}
    else:
        emb = ef([query])[0]
        _embedding_cache[query] = emb
        if len(_embedding_cache) > _EMBEDDING_CACHE_MAX:
            _embedding_cache.popitem(last=False)
        kwargs = {"query_embeddings": [emb], "n_results": n_results}
    if where:
        kwargs["where"] = where

    results = col.query(**kwargs)

    products = []
    for i in range(len(results["ids"][0])):
        meta = results["metadatas"][0][i]
        products.append({
            "id": results["ids"][0][i],
            "name": meta.get("name", ""),
            "brand": meta.get("brand", ""),
            "category": meta.get("category", ""),
            "sub_category": meta.get("sub_category", ""),
            "price_min": meta.get("price_min", 0),
            "price_max": meta.get("price_max", 0),
            "image_path": meta.get("image_path", ""),
            "skus": json.loads(meta.get("skus", "[]")),
            "avg_rating": meta.get("avg_rating"),
            "review_count": meta.get("review_count"),
            "distance": results["distances"][0][i],
            "search_text": results["documents"][0][i][:200],  # 截断，用于 prompt
        })
    return products


def get_products_by_ids(product_ids: list[str]) -> list[dict]:
    """按 ID 批量获取商品元数据"""
    if not product_ids:
        return []
    client, ef = _get_client()
    col = client.get_collection("products", embedding_function=ef)
    results = col.get(ids=product_ids, include=["metadatas", "documents"])
    products = []
    for i in range(len(results["ids"])):
        meta = results["metadatas"][i]
        products.append({
            "id": results["ids"][i],
            "name": meta.get("name", ""),
            "brand": meta.get("brand", ""),
            "category": meta.get("category", ""),
            "sub_category": meta.get("sub_category", ""),
            "price_min": meta.get("price_min", 0),
            "price_max": meta.get("price_max", 0),
            "image_path": meta.get("image_path", ""),
            "skus": json.loads(meta.get("skus", "[]")),
            "avg_rating": meta.get("avg_rating"),
            "review_count": meta.get("review_count"),
            "distance": 0,
            "search_text": (results["documents"][i] or "")[:200],
        })
    return products


def search_chunks(query: str, n_results: int = 5, where: dict = None) -> list[dict]:
    """
    向量检索问答片段（FAQ + Review）。

    Args:
        query: 用户输入
        n_results: 返回条数
        where: metadata filter，如 {"type": "faq"} 或 {"product_id": "p_beauty_001"}

    Returns:
        匹配片段列表
    """
    client, ef = _get_client()
    col = client.get_collection("chunks", embedding_function=ef)

    # embedding 缓存（LRU）
    if query in _embedding_cache:
        _embedding_cache.move_to_end(query)
        kwargs = {"query_embeddings": [_embedding_cache[query]], "n_results": n_results}
    else:
        emb = ef([query])[0]
        _embedding_cache[query] = emb
        if len(_embedding_cache) > _EMBEDDING_CACHE_MAX:
            _embedding_cache.popitem(last=False)
        kwargs = {"query_embeddings": [emb], "n_results": n_results}
    if where:
        kwargs["where"] = where

    results = col.query(**kwargs)

    chunks = []
    for i in range(len(results["ids"][0])):
        meta = results["metadatas"][0][i]
        chunk = {
            "chunk_id": results["ids"][0][i],
            "product_id": meta.get("product_id", ""),
            "type": meta.get("type", ""),
            "content": results["documents"][0][i],
            "rating": meta.get("rating"),
            "distance": results["distances"][0][i],
        }
        if meta.get("question"):
            chunk["question"] = meta["question"]
        if meta.get("answer"):
            chunk["answer"] = meta["answer"]
        chunks.append(chunk)
    return chunks


def _filter_by_dominant_subcategory(products: list[dict]) -> list[dict]:
    """品类过滤：保留主品类商品，去除跨品类噪音。

    逻辑：
    1. 统计各 sub_category 出现次数，取出现最多的作为主品类（多数投票）
    2. 出现次数相同时，优先取距离最近的那个 sub_category
    3. 主品类商品 ≥3 个才过滤，否则保留全部（结果太分散时过滤可能误伤）
    """
    if len(products) <= 1:
        return products

    sorted_prods = sorted(products, key=lambda p: p["distance"])

    # 统计各 sub_category 出现次数
    from collections import Counter
    subcat_counts = Counter(p["sub_category"] for p in sorted_prods)

    # 取出现最多的 sub_category；相同次数时取距离最近的
    max_count = max(subcat_counts.values())
    # 按首次出现顺序（即距离顺序）找到第一个达到 max_count 的 sub_category
    dominant_subcat = None
    for p in sorted_prods:
        if subcat_counts[p["sub_category"]] == max_count:
            dominant_subcat = p["sub_category"]
            break

    same_cat = [p for p in sorted_prods if p["sub_category"] == dominant_subcat]
    # 主品类至少 3 个才过滤；2 个说明结果太分散，过滤可能误伤
    if len(same_cat) < 3:
        return products

    logger.info("品类过滤: 保留 sub_category=%r（%d→%d 个商品）",
                dominant_subcat, len(products), len(same_cat))
    return same_cat


def retrieve(
    query: str, n_products: int = 3, n_chunks: int = 5, *, filters: dict | None = None
) -> dict:
    """
    综合检索：先找商品，再找相关 chunks 补充信息。

    流程：别名扩展 → 向量检索 → 品类过滤 → 补充 chunks

    Returns:
        {"products": [...], "chunks": [...]}
    """
    # 别名扩展：小棕瓶 → 雅诗兰黛 精华 ...
    expanded_query, alias_hit = _expand_aliases(query)

    # 品类关键词检测：query 明确提到品类时，用 metadata filter 强制限定
    subcat_filter = None
    if not alias_hit:
        detected_subcat = _detect_subcategory(query)
        if detected_subcat:
            subcat_filter = {"sub_category": detected_subcat}

    # 粗召回：多检索一些，给 reranker 留余量
    where = {"$and": [subcat_filter, filters]} if subcat_filter and filters else subcat_filter or filters
    products = search_products(expanded_query, n_results=max(n_products + 5, 10), where=where)

    # 品类过滤：去除跨品类噪音
    # 别名查询 → 跳过（找特定商品）
    # 品类关键词命中 → 跳过（已用 metadata filter）
    # 其他 → 多数投票过滤
    if not alias_hit and not subcat_filter:
        products = _filter_by_dominant_subcategory(products)

    # 二阶段精排：cross-encoder rerank
    if len(products) > 1:
        products = rerank(query, products, text_key="search_text", top_k=n_products)
        logger.info("Rerank 完成: 保留 top-%d，最高分 %.3f",
                    len(products), products[0].get("rerank_score", 0))
    else:
        products = products[:n_products]

    # 找到的商品对应的 product_id，用于过滤 chunks
    product_ids = [p["id"] for p in products]

    # 同时检索全局 chunks 和关联商品的 chunks
    chunks = search_chunks(expanded_query, n_results=n_chunks)

    # 如果有匹配商品，额外拉它们的 FAQ/Review
    if product_ids:
        for pid in product_ids[:3]:
            extra = search_chunks(
                expanded_query,
                n_results=2,
                where={"product_id": pid},
            )
            for c in extra:
                if c["chunk_id"] not in [x["chunk_id"] for x in chunks]:
                    chunks.append(c)

    return {"products": products, "chunks": chunks}


# ── 场景化跨品类检索 ──────────────────────────────────────────
# 用户描述一个场景（如爬山、跑步、开学），需要跨品类组合推荐。
# 按场景模板拆成多个槽位，每个槽位独立检索，合并结果。

import re as _re

# 每个槽位: (搜索词, [候选sub_category], 分组名)
_SCENARIO_TEMPLATES: dict[str, list[tuple[str, list[str], str]]] = {
    "爬山": [
        ("徒步鞋 登山鞋", ["徒步鞋"], "鞋服装备"),
        ("户外裤 运动长裤", ["户外裤", "运动长裤"], "鞋服装备"),
        ("速干T恤 运动短袖", ["速干T恤", "短袖T恤"], "鞋服装备"),
        ("帽子 遮阳帽", ["帽子"], "鞋服装备"),
        ("背包 登山包", ["背包"], "鞋服装备"),
        ("防晒霜 防晒乳", ["防晒"], "防护"),
        ("功能饮料 运动饮料", ["功能饮料"], "补给"),
        ("坚果 零食 能量棒", ["坚果/零食"], "补给"),
    ],
    "跑步": [
        ("跑步鞋 跑鞋", ["跑步鞋"], "鞋服装备"),
        ("速干T恤 运动短袖", ["速干T恤", "短袖T恤"], "鞋服装备"),
        ("运动短裤 跑步短裤", ["运动短裤"], "鞋服装备"),
        ("真无线耳机 运动耳机", ["真无线耳机"], "数码装备"),
        ("防晒霜 防晒", ["防晒"], "防护"),
        ("功能饮料 运动饮料", ["功能饮料"], "补给"),
    ],
    "开学": [
        ("笔记本电脑 学生电脑", ["笔记本电脑"], "数码装备"),
        ("智能手机 学生手机", ["智能手机"], "数码装备"),
        ("真无线耳机 蓝牙耳机", ["真无线耳机"], "数码装备"),
        ("背包 通勤包 书包", ["背包"], "日常装备"),
        ("咖啡 速溶咖啡", ["咖啡"], "日常补给"),
        ("方便面 方便食品 泡面", ["方便食品"], "日常补给"),
    ],
    "通勤": [
        ("防晒霜 防晒隔离", ["防晒"], "防晒底妆"),
        ("粉底液 底妆", ["粉底液"], "防晒底妆"),
        ("蜜粉 定妆粉", ["蜜粉"], "防晒底妆"),
        ("短袖T恤 透气T恤", ["短袖T恤"], "透气穿搭"),
        ("帽子 遮阳帽", ["帽子"], "透气穿搭"),
        ("碳酸饮料 气泡水", ["碳酸饮料"], "解暑饮料"),
        ("茶饮 冰茶", ["茶饮"], "解暑饮料"),
    ],
    "居家办公": [
        ("笔记本电脑 办公电脑", ["笔记本电脑"], "办公设备"),
        ("平板电脑", ["平板电脑"], "办公设备"),
        ("真无线耳机 蓝牙耳机", ["真无线耳机"], "办公设备"),
        ("咖啡 速溶咖啡", ["咖啡"], "吃喝囤货"),
        ("牛奶 纯牛奶", ["牛奶"], "吃喝囤货"),
        ("方便面 方便食品", ["方便食品"], "吃喝囤货"),
        ("坚果 零食", ["坚果/零食"], "吃喝囤货"),
        ("卫衣 居家卫衣", ["卫衣"], "居家穿搭"),
        ("运动长裤 休闲长裤", ["运动长裤"], "居家穿搭"),
    ],
}
# 别名
_SCENARIO_TEMPLATES["徒步"] = _SCENARIO_TEMPLATES["爬山"]
_SCENARIO_TEMPLATES["登山"] = _SCENARIO_TEMPLATES["爬山"]
_SCENARIO_TEMPLATES["户外"] = _SCENARIO_TEMPLATES["爬山"]
_SCENARIO_TEMPLATES["入职"] = _SCENARIO_TEMPLATES["开学"]
_SCENARIO_TEMPLATES["夏天"] = _SCENARIO_TEMPLATES["通勤"]
_SCENARIO_TEMPLATES["夏日"] = _SCENARIO_TEMPLATES["通勤"]
_SCENARIO_TEMPLATES["居家"] = _SCENARIO_TEMPLATES["居家办公"]
_SCENARIO_TEMPLATES["宅家"] = _SCENARIO_TEMPLATES["居家办公"]

# 搭配意图词：query 必须同时包含场景关键词 + 意图词才触发，防止 "跑步鞋推荐" 误触发
_INTENT_RE = _re.compile(r"搭配|帮我|准备|需要什么|需要哪些|推荐.*和|一套|全套|方案|清单|装备")


def _detect_scenario(query: str) -> Optional[str ]:
    """检测 query 是否包含跨品类场景意图。返回场景 key 或 None。"""
    q = query.lower()
    if not _INTENT_RE.search(q):
        return None
    for kw in sorted(_SCENARIO_TEMPLATES.keys(), key=len, reverse=True):
        if kw in q:
            return kw
    return None


def retrieve_scenario(query: str, scenario_key: str) -> dict:
    """
    场景化多品类检索：按模板槽位逐个检索，每槽取 top 1，返回分组结果。

    Returns:
        {
            "products": [dict, ...],
            "chunks": [dict, ...],
            "groups": {"鞋服装备": ["id1", "id2"], "防护": ["id3"], ...},
        }
    """
    template = _SCENARIO_TEMPLATES[scenario_key]
    products: list[dict] = []
    groups: dict[str, list[str]] = {}
    seen_ids: set[str] = set()

    for slot_query, subcats, group_name in template:
        # 尝试每个候选 sub_category，粗召回后 rerank 取最佳
        found = None
        for subcat in subcats:
            hits = search_products(slot_query, n_results=3, where={"sub_category": subcat})
            if hits:
                ranked = rerank(query, hits, text_key="search_text", top_k=1)
                found = ranked[0]
                break
        if found and found["id"] not in seen_ids:
            found["scenario_role"] = group_name
            products.append(found)
            seen_ids.add(found["id"])
            groups.setdefault(group_name, []).append(found["id"])

    # 收集所有命中商品的 chunks
    chunks: list[dict] = []
    chunk_ids: set[str] = set()
    for p in products[:5]:
        extra = search_chunks(query, n_results=2, where={"product_id": p["id"]})
        for c in extra:
            if c["chunk_id"] not in chunk_ids:
                chunks.append(c)
                chunk_ids.add(c["chunk_id"])

    logger.info("场景检索 [%s]: %d 个商品, %d 个chunks, 分组=%s",
                scenario_key, len(products), len(chunks),
                {k: len(v) for k, v in groups.items()})

    return {"products": products, "chunks": chunks, "groups": groups}


_PRESET_QUERIES = [
    "给我推荐一款笔记本电脑",
    "给我推荐一款护肤精华",
    "帮我搭配户外徒步装备",
    "开学需要准备什么",
    "神仙水怎么样",
    "小棕瓶和小黑瓶哪个好",
]

def warmup_rag(query: str = "预热") -> None:
    """启动时预热 RAG：加载 embedding + reranker 模型 + 缓存预设查询结果。"""
    start = time.perf_counter()
    retrieve(query, n_products=1, n_chunks=1)
    logger.info("RAG 模型预热完成（含 Reranker），耗时 %.2fs", time.perf_counter() - start)

    # 预热预设查询，结果存入缓存
    t0 = time.perf_counter()
    for pq in _PRESET_QUERIES:
        scenario = _detect_scenario(pq)
        if scenario:
            cached_retrieve_scenario(pq, scenario)
        else:
            cached_retrieve(pq, n_products=5, n_chunks=8)
    logger.info("预设查询预热完成（%d 条），耗时 %.2fs",
                len(_PRESET_QUERIES), time.perf_counter() - t0)


if __name__ == "__main__":
    # 快速测试
    print("=== 测试检索 ===\n")
    result = retrieve("推荐一款适合油皮的洗面奶")

    print(f"找到 {len(result['products'])} 个商品：")
    for p in result["products"]:
        print(f"  - {p['name'][:30]}... ({p['brand']}) ¥{p['price_min']}-{p['price_max']} [距离:{p['distance']:.3f}]")

    print(f"\n找到 {len(result['chunks'])} 个相关片段：")
    for c in result["chunks"]:
        print(f"  - [{c['type']}] {c['content'][:50]}... [距离:{c['distance']:.3f}]")
