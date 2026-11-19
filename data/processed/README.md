# Processed Data — 数据格式说明

清洗后的数据，供 RAG 链路使用。

## 文件结构

```
data/processed/
├── products.jsonl    # 商品数据（一行一条 SKU）
├── chunks.jsonl      # 检索片段（FAQ + 用户评价拆分）
└── README.md         # 本文件
```

---

## products.jsonl

每行一条 SKU，字段如下：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | SKU 唯一 ID，格式：`s_` + product_id + `_` + sku_id 序号 |
| `product_id` | string | 原始商品 ID，如 `p_beauty_001` |
| `name` | string | 商品标题（原始 title） |
| `brand` | string | 品牌名 |
| `category` | string | 一级类目（美妆护肤 / 数码电子 / 服饰运动 / 食品生活） |
| `sub_category` | string | 二级类目（精华 / 手机 / 跑鞋等） |
| `price` | float | 该 SKU 的价格（元） |
| `image_path` | string | 图片相对路径 |
| `search_text` | string | 检索用文本，由 title + brand + category + sub_category + marketing_description 拼接 |

### search_text 拼接规则

```
name + " " + brand + " " + category + " " + sub_category + " " + marketing_description
```

全文拼接，不做分词或权重调整。

---

## chunks.jsonl

FAQ 和用户评价拆出，每行一条独立检索片段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `chunk_id` | string | 唯一 ID，格式：`{product_id}_{faq/review}_{序号}` |
| `product_id` | string | 关联的商品 ID |
| `type` | string | `faq` 或 `review` |
| `content` | string | 检索文本，FAQ 为 question + answer 前 100 字，Review 为评价内容前 100 字 |
| `rating` | integer | 仅 `review` 有，1-5 分；`faq` 该字段为空 |

---

## 数据量

- 100 条商品原始数据，约 400+ 条 SKU
- 每条商品约 2-5 个 FAQ chunk
- 每条商品约 2-5 条 Review chunk

---

## 下游使用

### 向量入库

`products.jsonl` 和 `chunks.jsonl` 分别入库，embedding 字段为：
- products：`search_text`
- chunks：`content`

### 元数据过滤

Chromadb filter 支持按以下字段精确筛选：
- `category`
- `brand`
- `sub_category`
- `price`（范围）
- `rating`（仅 chunks）
- `type`（仅 chunks）

---

## 示例

### products.jsonl 示例

```json
{"id": "s_p_beauty_001_1", "product_id": "p_beauty_001", "name": "雅诗兰黛特润修护肌活精华露淡纹紧致保湿夜间修护抗初老精华30ml", "brand": "雅诗兰黛", "category": "美妆护肤", "sub_category": "精华", "price": 720.0, "image_path": "1_美妆护肤/images/p_beauty_001_live.jpg", "search_text": "雅诗兰黛特润修护肌活精华露淡纹紧致保湿夜间修护抗初老精华30ml 雅诗兰黛 美妆护肤 精华 雅诗兰黛特润修护肌活精华露（小棕瓶）是品牌经典抗初老单品..."}
```

### chunks.jsonl 示例

```json
{"chunk_id": "p_beauty_001_faq_0", "product_id": "p_beauty_001", "type": "faq", "content": "这款精华的核心成分二裂酵母发酵产物溶胞物有什么作用？二裂酵母发酵产物溶胞物是这款精华的核心修护成分，能模拟皮肤微生态，帮助修护日间紫外线...", "rating": null}
```

```json
{"chunk_id": "p_beauty_001_review_0", "product_id": "p_beauty_001", "type": "review", "content": "熬夜党救星！每晚3滴吸收超快不黏腻，第二天皮肤不暗沉。半个月后眼角干纹淡了...", "rating": 5}
```

---

## 注意事项

- `products.jsonl` 一行一个 SKU，同一商品多个规格会占多行
- `chunks.jsonl` 的 `rating` 字段：faq 为 `null`，review 为整数 1-5
- 原始数据存在 `data/raw/`，处理后的数据存在 `data/processed/`
- 如需完整 FAQ answer 或 Review 原文，请查阅原始 JSON