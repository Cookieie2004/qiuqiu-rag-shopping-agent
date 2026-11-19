"""
数据清洗脚本
将 data/raw/ 下的原始商品数据清洗为 products.jsonl 和 chunks.jsonl

用法：
    python -m data.scripts.clean
"""

import json
import pathlib

RAW_DIR = pathlib.Path(__file__).parent.parent / "raw"
OUT_PRODUCTS = pathlib.Path(__file__).parent.parent / "processed" / "products.jsonl"
OUT_CHUNKS = pathlib.Path(__file__).parent.parent / "processed" / "chunks.jsonl"


def truncate(text: str, length: int = 500) -> str:
    """截断文本至指定长度，避免 Embedding 模型 token 上限溢出"""
    if len(text) <= length:
        return text
    return text[:length]


def clean_product(raw: dict) -> tuple[list[dict], list[dict]]:
    """清洗单条原始商品数据，返回 (商品列表, chunk列表)。

    核心产出：
    - search_text：拼接商品核心字段，作为向量检索的文档文本
    - FAQ chunks：官方问答拆分为独立片段，通过 product_id 关联商品
    - Review chunks：用户评价拆分为独立片段，保留评分信息
    """
    product_id = raw["product_id"]
    name = raw["title"]
    brand = raw.get("brand", "")
    category = raw["category"]
    sub_category = raw.get("sub_category", "")
    marketing_desc = raw.get("rag_knowledge", {}).get("marketing_description", "")

    skus = raw.get("skus", [])
    chunks = []

    prices = [sku.get("price", 0.0) for sku in skus]
    # search_text 是商品向量化的核心文本，拼接 5 个字段：商品名+品牌+品类+子类目+营销描述
    # 不包含 FAQ 和评价——它们作为独立 chunks 存储，避免噪声稀释商品语义向量
    search_text = " ".join([name, brand, category, sub_category, marketing_desc])

    product = {
        "id": product_id,
        "product_id": product_id,
        "name": name,
        "brand": brand,
        "category": category,
        "sub_category": sub_category,
        "price_min": min(prices) if prices else 0.0,
        "price_max": max(prices) if prices else 0.0,
        "skus": skus,
        "image_path": raw.get("image_path", ""),
        "search_text": search_text,
    }

    # 拆分 FAQ chunks —— 每条问答独立存储，检索到商品后按 product_id 关联拉取
    faq_list = raw.get("rag_knowledge", {}).get("official_faq", [])
    for idx, faq in enumerate(faq_list):
        question = faq.get("question", "")
        answer = faq.get("answer", "")
        content = truncate(f"{question} {answer}")
        chunks.append({
            "chunk_id": f"{product_id}_faq_{idx}",
            "product_id": product_id,
            "type": "faq",
            "content": content,
            "question": question,
            "answer": truncate(answer),
            "rating": None,
        })

    # 拆分 Review chunks —— 每条评价独立存储，保留原始评分用于引用展示
    review_list = raw.get("rag_knowledge", {}).get("user_reviews", [])
    for idx, review in enumerate(review_list):
        content_text = review.get("content", "")
        rating = review.get("rating", None)
        content = truncate(content_text)
        chunks.append({
            "chunk_id": f"{product_id}_review_{idx}",
            "product_id": product_id,
            "type": "review",
            "content": content,
            "rating": rating,
        })

    return [product], chunks


def main():
    """遍历 4 大品类目录，清洗所有商品并输出 products.jsonl + chunks.jsonl"""
    OUT_PRODUCTS.parent.mkdir(parents=True, exist_ok=True)

    # 4 大品类目录，编号前缀用于排序
    categories = ["1_美妆护肤", "2_数码电子", "3_服饰运动", "4_食品生活"]
    total_products = 0
    total_chunks = 0

    with open(OUT_PRODUCTS, "w", encoding="utf-8") as pf, \
         open(OUT_CHUNKS, "w", encoding="utf-8") as cf:

        for cat in categories:
            cat_dir = RAW_DIR / cat / "data"
            if not cat_dir.exists():
                print(f"[跳过] {cat} data 目录不存在")
                continue

            json_files = sorted(cat_dir.glob("*.json"))
            print(f"[{cat}] 找到 {len(json_files)} 个商品")

            for json_file in json_files:
                with open(json_file, encoding="utf-8") as f:
                    raw = json.load(f)

                products, chunks = clean_product(raw)

                for p in products:
                    pf.write(json.dumps(p, ensure_ascii=False) + "\n")
                    total_products += 1

                for c in chunks:
                    cf.write(json.dumps(c, ensure_ascii=False) + "\n")
                    total_chunks += 1

    print(f"\n完成")
    print(f"  products: {total_products} 行 -> {OUT_PRODUCTS}")
    print(f"  chunks:   {total_chunks} 行 -> {OUT_CHUNKS}")


if __name__ == "__main__":
    main()