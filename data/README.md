# 商品数据集

共 100 条商品，覆盖 4 个类目（每类 25 条）。

## 目录结构

```
data/
├── raw/                          # 官方原始数据（不要修改）
│   ├── 1_美妆护肤/
│   │   ├── data/                 # 25 个 JSON 文件
│   │   └── images/               # 25 张商品图片
│   ├── 2_数码电子/
│   ├── 3_服饰运动/
│   └── 4_食品生活/
├── processed/                    # 清洗后的数据（入库用）
└── scripts/
    └── ingest.py                 # 数据入库脚本
```

## 原始数据字段说明

每个 JSON 文件的结构如下：

| 字段 | 类型 | 说明 |
|------|------|------|
| product_id | string | 商品唯一标识，如 `p_beauty_001` |
| title | string | 商品完整标题 |
| brand | string | 品牌名 |
| category | string | 一级类目（美妆护肤/数码电子/服饰运动/食品生活） |
| sub_category | string | 二级类目（精华/手机/跑鞋等） |
| base_price | float | 基础价格（元） |
| image_path | string | 图片相对路径，如 `1_美妆护肤/images/p_beauty_001_live.jpg` |
| skus | array | SKU 列表（不同规格/配置） |
| rag_knowledge | object | RAG 检索用的知识内容 |

### SKU 结构

```json
{
  "sku_id": "s_p_beauty_001_1",
  "properties": {"容量": "30ml 经典装"},
  "price": 720.0
}
```

### rag_knowledge 结构

| 子字段 | 类型 | 说明 |
|--------|------|------|
| marketing_description | string | 营销文案，详细描述商品卖点、成分、适用人群（RAG 检索的核心内容） |
| official_faq | array | 官方 FAQ，每条含 question + answer |
| user_reviews | array | 用户评价，每条含 nickname + rating(1-5) + content |

## 数据处理要点

- `marketing_description` 是 RAG 向量检索的主要内容，信息最丰富
- `official_faq` 可拆成独立 chunk，提升问答匹配精度
- `user_reviews` 包含正面和负面评价，可用于生成更真实的推荐话术
- 图片已在本地 `images/` 目录，不存在外链失效问题
- 一个商品可能有多个 SKU（不同规格/价格），注意价格范围展示
