"""商品详情接口 — GET /products/{product_id}"""

import json
from fastapi import APIRouter, HTTPException
from server.rag.retriever import _get_client, search_chunks

router = APIRouter()


@router.get("/products/{product_id}")
def get_product(product_id: str):
    """根据商品 ID 返回详情，包括 FAQ 和用户评价。"""
    client, ef = _get_client()
    col = client.get_collection("products", embedding_function=ef)

    # 按 ID 精确查询
    result = col.get(ids=[product_id])
    if not result["ids"]:
        raise HTTPException(
            status_code=404,
            detail={"error": {"code": "NOT_FOUND", "message": "商品不存在"}},
        )

    meta = result["metadatas"][0]
    raw_text = result["documents"][0] if result["documents"] else ""
    # 去掉开头的 "商品名 品牌 品类 子类目" 前缀，保留正式描述
    name = meta.get("name", "")
    brand = meta.get("brand", "")
    category = meta.get("category", "")
    sub_category = meta.get("sub_category", "")
    prefix = " ".join(filter(None, [name, brand, category, sub_category]))
    search_text = raw_text[len(prefix):].strip() if raw_text.startswith(prefix) else raw_text

    # 拉该商品的 FAQ 和 Review 作为补充
    chunks = search_chunks(
        search_text[:50],  # 用商品描述前 50 字做检索
        n_results=20,
        where={"product_id": product_id},
    )

    # 组装 attributes
    attributes = {"品牌": meta.get("brand", "")}

    # FAQ 和评价（优先用结构化 Q/A，兜底用 content）
    faq_list = []
    for c in chunks:
        if c["type"] != "faq":
            continue
        q = c.get("question", "")
        a = c.get("answer", "")
        if q and a:
            faq_list.append({"question": q, "answer": a})
        else:
            faq_list.append({"question": "", "answer": c["content"]})
    review_chunks = [c for c in chunks if c["type"] == "review"]
    review_list = [c["content"] for c in review_chunks]
    review_items = [
        {"content": c["content"], "rating": c.get("rating")}
        for c in review_chunks
    ]

    price_min = meta.get("price_min", 0)
    price_max = meta.get("price_max", 0)

    return {
        "id": product_id,
        "name": meta.get("name", ""),
        "category": meta.get("category", ""),
        "price": int(price_min) if isinstance(price_min, float) and price_min == int(price_min) else price_min,
        "price_range": f"¥{int(price_min) if isinstance(price_min, float) and price_min == int(price_min) else price_min}-¥{int(price_max) if isinstance(price_max, float) and price_max == int(price_max) else price_max}" if price_max != price_min else f"¥{int(price_min) if isinstance(price_min, float) and price_min == int(price_min) else price_min}",
        "description": search_text,
        "image_url": f"/static/{meta.get('image_path', '')}",
        "attributes": attributes,
        "skus": json.loads(meta.get("skus", "[]")),
        "faq": faq_list[:10],
        "reviews": review_list[:10],
        "review_items": review_items[:10],
    }
