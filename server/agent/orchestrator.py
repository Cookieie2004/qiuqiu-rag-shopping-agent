from typing import Optional
"""
Agent 编排 — 串联 RAG 检索 + 豆包大模型生成

两种调用方式：
- generate(): 普通请求，返回完整回复
- generate_stream(): 流式请求，yield 逐块文本和商品卡片

混合模式：LLM 在回复末尾用 <!--RECOMMEND: id1, id2--> 标注推荐商品，
后端解析后只发选中的卡片，解决"卡片不受 LLM 控制"问题。
"""

import logging
import re
import time
from pathlib import Path

from openai import OpenAI
from server.config.settings import LLM_API_KEY, LLM_MODEL, LLM_BASE_URL
from server.rag.retriever import retrieve, get_products_by_ids, _ALIASES, _detect_scenario, retrieve_scenario, cached_retrieve, cached_retrieve_scenario

logger = logging.getLogger("uvicorn.error")

# ── 多轮 query 改写 ──────────────────────────────────────────

_REWRITE_PROMPT = """你是一个购物对话的 query 改写器。你需要做三件事：

1. 判断是否需要重新搜索商品（SEARCH 或 NO_SEARCH）
2. 如果需要搜索，改写 query 使其独立完整
3. 从当前对话和历史中提取用户明确排除的商品/品类/成分关键词

判断逻辑：
- SEARCH：用户想找新商品、换品类、加新筛选条件、否定/排除上轮推荐的整个品类
- NO_SEARCH：用户在已推荐的具体商品里挑选、追问细节、对比已推荐的几款

关键区分：
- 否定/排除整个品类（"不吃泡面""泡面算了""对坚果过敏"）→ SEARCH
- 追加约束需要不同商品（加预算、换场景、换功效）→ SEARCH
- 在已推荐的几款里挑选、问细节、做对比 → NO_SEARCH

输出格式（严格遵守，只输出一行）：
SEARCH: 改写后的query
SEARCH: 改写后的query | EXCLUDE: 关键词1, 关键词2
NO_SEARCH
NO_SEARCH | EXCLUDE: 关键词1

EXCLUDE 规则：
- 只提取用户明确拒绝的具体品类/品牌/成分/食材词（如：泡面、坚果、酒精、兰蔻）
- 不提取主观模糊词（如：太贵、不好用、太油）
- 回溯整段对话历史，不只看当前轮——用户前几轮说的排除偏好也要提取
- 没有排除项时不输出 EXCLUDE 部分

示例（"历史"只是示意，实际你要看真实对话内容来改写 query）：
- 用户问便宜的油皮洗面奶 → SEARCH: 平价油皮洗面奶
- 用户之前问防晒霜，现在加条件要SPF50以上 → SEARCH: SPF50以上的防晒霜
- 用户之前问跑步鞋，现在说要跑马拉松 → SEARCH: 适合跑马拉松的跑步鞋
- 上轮推荐了泡面，用户说不吃泡面 → SEARCH: 零食推荐 | EXCLUDE: 泡面
- 上轮推荐了坚果和饼干，用户说不要坚果 → SEARCH: 零食推荐 | EXCLUDE: 坚果
- 用户之前说过不要酒精成分，现在问推荐面霜 → SEARCH: 面霜推荐 | EXCLUDE: 酒精
- 上轮推荐了美白精华，用户说不想美白要抗老 → SEARCH: 抗老精华
- 上轮对比了小棕瓶和小黑瓶，用户说我是油皮哪个适合 → NO_SEARCH
- 上轮推荐了3款精华，用户问第二款怎么样 → NO_SEARCH
- 上轮推荐了手机，用户说帮我选一个 → NO_SEARCH
- 用户前几轮说过不吃坚果，现在问露营食品推荐 → SEARCH: 露营食品推荐 | EXCLUDE: 坚果
- 首轮，用户说推荐一款面霜 → SEARCH: 面霜推荐"""


_rewrite_cache: dict[str, tuple[str, bool, list[str]]] = {}
_REWRITE_CACHE_MAX = 256


def _parse_exclude(raw: str) -> list[str]:
    """从 LLM 输出中解析 '| EXCLUDE: kw1, kw2' 部分"""
    idx = raw.find("| EXCLUDE:")
    if idx < 0:
        idx = raw.find("|EXCLUDE:")
    if idx < 0:
        return []
    exclude_part = raw[idx:].split(":", 1)[1].strip()
    return [kw.strip() for kw in exclude_part.split(",") if kw.strip()]


def _rewrite_query(query: str, history: list[dict] = None) -> tuple[str, bool, list[str]]:
    """多轮场景下，改写 query、判断是否需要重新检索、提取排除关键词。

    Returns:
        (rewritten_query, needs_search, exclusions)
        needs_search=False 表示收窄，跳过 RAG
        exclusions: LLM 从对话中提取的排除关键词列表
    """
    if not history:
        return query, True, []

    # 缓存：相同 query + 最近一轮 assistant 内容 → 相同改写结果
    last_assistant = ""
    for msg in reversed(history):
        if msg.get("role") == "assistant":
            last_assistant = msg.get("content", "")[:200]
            break
    cache_key = f"{query}|{last_assistant}"
    if cache_key in _rewrite_cache:
        cached = _rewrite_cache[cache_key]
        logger.info("Query 改写缓存命中 query=%r → (%r, %s, exclude=%s)", query, cached[0], cached[1], cached[2])
        return cached

    try:
        messages = [{"role": "system", "content": _REWRITE_PROMPT}]
        # 只取最近 4 轮历史
        for msg in history[-4:]:
            messages.append(msg)
        messages.append({"role": "user", "content": query})

        client = _llm_client
        resp = client.chat.completions.create(
            model=LLM_MODEL, messages=messages, temperature=0,
        )
        result = resp.choices[0].message.content.strip()

        # 解析 EXCLUDE
        exclusions = _parse_exclude(result)

        # 去掉 EXCLUDE 部分再解析 SEARCH/NO_SEARCH
        main_part = result.split("|")[0].strip()

        if main_part.startswith("NO_SEARCH"):
            logger.info("Query 改写判断: NO_SEARCH query=%r exclude=%s", query, exclusions)
            ret = (query, False, exclusions)
        elif main_part.startswith("SEARCH:"):
            rewritten = main_part[len("SEARCH:"):].strip()
            if rewritten:
                logger.info("Query 改写: %r → %r (SEARCH) exclude=%s", query, rewritten, exclusions)
                ret = (rewritten, True, exclusions)
            else:
                ret = (query, True, exclusions)
        else:
            # 兜底：当作改写结果处理
            logger.info("Query 改写（无前缀）: %r → %r exclude=%s", query, result, exclusions)
            ret = (main_part, True, exclusions)
        if len(_rewrite_cache) >= _REWRITE_CACHE_MAX:
            # 清掉前一半，简单 FIFO
            keys = list(_rewrite_cache.keys())
            for k in keys[:len(keys) // 2]:
                del _rewrite_cache[k]
        _rewrite_cache[cache_key] = ret
        return ret
    except Exception as e:
        logger.warning("Query 改写失败 query=%r error=%s", query, e)
        return query, True, []

# ── OpenAI 客户端单例 ──────────────────────────────────────────
_llm_client = OpenAI(api_key=LLM_API_KEY, base_url=LLM_BASE_URL)

# ── 简单问候检测（替代 LLM 意图分类，省 10-20s）──────────────
_GREETING_PATTERNS = [
    re.compile(r"^(你好|hi|hello|嗨|hey)[\s!！。.]*$", re.IGNORECASE),
    re.compile(r"^(谢谢|感谢|thanks|thank you)[\s!！。.]*$", re.IGNORECASE),
    re.compile(r"^(再见|拜拜|bye)[\s!！。.]*$", re.IGNORECASE),
    re.compile(r"^(好的|OK|知道了|收到|明白)[\s!！。.]*$", re.IGNORECASE),
]


def _is_greeting(query: str) -> bool:
    """快速判断是否为问候/闲聊（不走 LLM）"""
    q = query.strip()
    return any(p.match(q) for p in _GREETING_PATTERNS)


# ── 自我介绍 / 功能询问检测 ──────────────────────────────────
_META_PATTERNS = [
    re.compile(r"(你是谁|你叫什么|你的名字|介绍一下你自己|自我介绍)", re.IGNORECASE),
    re.compile(r"(你[能会]干什么|你[有能]什么功能|你的功能|你能做什么|你可以做什么)", re.IGNORECASE),
    re.compile(r"(你有什么用|怎么用你|如何使用|使用说明|使用方法)", re.IGNORECASE),
    re.compile(r"(你是什么|你是啥|what are you|who are you)", re.IGNORECASE),
]


def _is_meta_question(query: str) -> bool:
    """判断是否在问 AI 自身（自我介绍/功能说明），不该触发商品检索"""
    q = query.strip()
    return any(p.search(q) for p in _META_PATTERNS)


# ── 闲聊兜底：短查询用 LLM 轻量分类 ──────────────────────────────
_INTENT_PROMPT = (
    "判断用户输入是「购物」还是「闲聊」。\n"
    "购物：想买东西、问商品、要推荐、比较、评价、咨询价格等。\n"
    "闲聊：打招呼、闲扯、问非购物问题、表情、无意义输入、打字不完整等。\n"
    "只回复一个字：购 或 聊"
)


def _is_casual_chat(query: str) -> bool:
    """用 LLM 判断是否闲聊，避免非购物查询误触 RAG"""
    q = query.strip()
    try:
        resp = _llm_client.chat.completions.create(
            model=LLM_MODEL,
            messages=[
                {"role": "system", "content": _INTENT_PROMPT},
                {"role": "user", "content": q},
            ],
            temperature=0,
            max_tokens=4,
        )
        answer = resp.choices[0].message.content.strip()
        is_chat = "聊" in answer
        logger.info("闲聊分类 query=%r → %s", q, answer)
        return is_chat
    except Exception as e:
        logger.warning("闲聊分类失败 query=%r: %s", q, e)
        return False  # 分类失败 → 保守走 RAG


# ── 首轮"宽泛品类浏览"判断：决定是否强制走 clarify ──────────────
# 用户给了关键约束（任一命中 → 需求不算模糊，不强制追问）
_CONDITION_KEYWORDS = [
    # 肤质
    "油皮", "干皮", "油性", "干性", "混油", "混干", "混合皮", "敏感肌", "痘痘", "痘肌",
    "大油田", "闭口", "黑头", "外油内干",
    # 预算
    "便宜", "平价", "实惠", "百元", "千元", "高端", "性价比", "预算",
    # 场景
    "通勤", "办公", "运动", "跑步", "健身", "约会", "学生", "旅行", "居家", "送人", "送礼", "礼物",
]
# 对比 / 单品评价：首轮也可能出现，不算宽泛品类浏览，不强制追问
# 注意：收敛决策词（帮我选/直接选/就买）只发生在后续轮，已被 first_turn 门槛排除，不放这里
_NON_BROWSE_KEYWORDS = [
    "对比", "比较", "哪个", "哪款", "区别", "还是", "vs", "VS",
    "怎么样", "怎样", "值不值", "值得买", "好不好", "好用吗",
]
_PRICE_RE = re.compile(r"\d+\s*(?:元|块|w|万|rmb)?\s*(?:以内|以下|左右|上下|预算|封顶)")


def _is_vague_browse(query: str) -> bool:
    """是否为首轮宽泛品类浏览：缺少肤质/预算/场景等关键条件，且非对比/评价/指定商品。"""
    q = query.strip()
    if _PRICE_RE.search(q):
        return False
    if any(k in q for k in _CONDITION_KEYWORDS):
        return False
    if any(k in q for k in _NON_BROWSE_KEYWORDS):
        return False
    ql = q.lower()
    if any(alias.lower() in ql for alias in _ALIASES):  # 指名了具体商品（小棕瓶等）
        return False
    return True


# ── clarify 模式提示（互斥注入到 user_content 末尾）──
_NO_CLARIFY_HINT = "\n\n【系统提示：本次检索结果较少，请直接推荐所有商品，不要追问用户。】"
_FORCE_CLARIFY_HINT = (
    "\n\n【强制指令（最高优先级，覆盖 <routing> 的所有默认判断）】\n"
    "用户首轮宽泛提问，没有给出肤质/预算/使用场景等关键条件。本轮必须走追问(clarify)模式，禁止直接推荐商品：\n"
    "1. 严格按 <clarify_mode> 输出：<bubble>品类教育</bubble> + <clarify>[question: 维度 ans: 选项|选项|选项]</clarify> + <bubble>钩子</bubble>\n"
    "2. 追问 1-2 个最关键维度（如肤质、预算、核心诉求或使用场景），每个给 2-3 个短选项\n"
    "3. 禁止输出任何 <!--PRODUCT: --> 标记，结尾必须输出空的 <!--RECOMMEND: -->\n"
)


_SCENARIO_HINT = (
    "\n\n【场景搭配模式】本次检索横跨多个品类，是场景化组合推荐。"
    "请按上面的场景维度分组（如鞋服装备/防护/补给）介绍商品，不要按品类分组。"
    "每个维度的商品逐个介绍（含图片、KV、评价引用、<!--PRODUCT: ID-->标记），最后用高亮块给出组合搭配建议。"
    "不要追问，直接推荐。"
)

_PROMPT_DIR = Path(__file__).parent / "prompts"


_prompt_cache: dict[str, tuple[float, str]] = {}

def _load_prompt(name: str) -> str:
    """带 mtime 检测的内存缓存，改文件后自动失效，无需重启"""
    path = _PROMPT_DIR / name
    mtime = path.stat().st_mtime
    cached = _prompt_cache.get(name)
    if cached and cached[0] == mtime:
        return cached[1]
    content = path.read_text()
    _prompt_cache[name] = (mtime, content)
    return content


_PRODUCT_TYPE_RE = re.compile(
    r'(精华露|精华液|肌底液|面霜|乳液|洁面乳|洁面膏|防晒乳|防晒霜|隔离露|特护霜|'
    r'化妆水|爽肤水|面膜|眼霜|粉底液|气垫|蜜粉饼|散粉|唇釉|眉笔|卸妆油|卸妆液|'
    r'酸奶|酸牛奶|纯牛奶|牛奶|气泡水|咖啡(?!因)|方便面|坚果|酱油|功能饮料|茶饮|饮料|'
    r'笔记本电脑|笔记本|轻薄本|平板电脑|智能手机|手机|蓝牙耳机|无线耳机|耳机|'
    r'T恤|短袖|卫衣|长裤|短裤|瑜伽裤|跑步鞋|跑鞋|篮球鞋|徒步鞋|登山鞋|运动裤|紧身裤|'
    r'背包|双肩背包|棒球帽|鸭舌帽|遮阳帽)'
)


def _make_short_name(p: dict) -> str:
    """从商品名智能截取短名称，≤18字，保留品牌+型号+品类词"""
    name = p.get("name", "")
    brand = p.get("brand", "")
    max_len = 18

    # 在品类关键词后截断
    m = _PRODUCT_TYPE_RE.search(name)
    if m:
        cleaned = name[:m.end()]
    else:
        # 没匹配到品类词，去掉规格后缀
        cleaned = re.sub(r'\d+\s*(ml|ML|g|G|片|支|瓶|盒|套装|英寸|GB|TB).*$', '', name).strip()

    if len(cleaned) > max_len:
        # 超长：截断到 max_len，但在最后一个完整词/汉字处断开
        cleaned = cleaned[:max_len]

    # 过短时（品类词截断后只剩品牌+品类词，丢了型号），用原名去规格后截断
    if len(cleaned) < 8 and len(name) > len(cleaned):
        fallback = re.sub(r'\d+\s*(ml|ML|g|G|片|支|瓶|盒|套装|英寸|GB|TB).*$', '', name).strip()
        if len(fallback) > max_len:
            fallback = fallback[:max_len]
        if len(fallback) > len(cleaned):
            cleaned = fallback

    # 兜底
    if len(cleaned) < 3:
        cleaned = f"{brand} {p.get('sub_category', '')}"
    return cleaned


def _make_product_event_from_dict(p: dict, chunks: Optional[list[dict] ] = None) -> dict:
    """构建商品卡片事件（模块级，收窄/RAG 路径共用）"""
    short_name = _make_short_name(p)
    # 价格格式化：整数不带 .0
    def _fmt_price(v):
        return str(int(v)) if isinstance(v, float) and v == int(v) else str(v)
    pmin, pmax = p['price_min'], p['price_max']
    price_str = f"¥{_fmt_price(pmin)}"
    if pmax != pmin:
        price_str = f"¥{_fmt_price(pmin)}-{_fmt_price(pmax)}"

    # 使用预计算的全量平均评分（入库时计算，覆盖全部评价）
    avg_rating = p.get("avg_rating")

    # 反查别名：如果商品名/品牌匹配到 _ALIASES 的扩展词，则收集对应昵称
    aliases = []
    name_lower = p["name"].lower()
    brand_lower = p["brand"].lower()
    for alias, expansion in _ALIASES.items():
        exp_keywords = expansion.lower().split()
        if (brand_lower and all(k in name_lower or k in brand_lower for k in exp_keywords)):
            aliases.append(alias)

    content = {
        "id": p["id"],
        "name": p["name"],
        "short_name": short_name,
        "price": price_str,
        "image_url": f"/static/{p['image_path']}",
        "brand": p["brand"],
        "category": p["category"],
        "sub_category": p["sub_category"],
        "skus": p.get("skus", []),
    }
    if aliases:
        content["aliases"] = aliases
    if avg_rating is not None:
        content["rating"] = avg_rating

    return {"type": "product", "content": content}


def _fix_image_urls(text: str) -> str:
    """修正 LLM 写错的图片 URL（加了 https:// 或域名前缀）"""
    # 匹配 ![...](https://xxx/static/...) 或 ![...](http://xxx/static/...)
    # 替换为 ![...](/static/...)
    fixed = re.sub(
        r'!\[([^\]]*)\]\(https?://[^)]*?(/static/[^)]+)\)',
        r'![\1](\2)',
        text,
    )
    return fixed


def _parse_recommend_ids(text: str) -> set[str]:
    """从 LLM 输出中解析 <!--RECOMMEND: id1, id2--> 标记"""
    match = re.search(r"<!--\s*RECOMMEND:\s*(.+?)\s*-->", text)
    if not match:
        return set()
    raw = match.group(1)
    return {pid.strip() for pid in raw.split(",") if pid.strip()}


def _parse_inline_product_ids(text: str) -> list[str]:
    """从 LLM 输出中解析所有 <!--PRODUCT: id--> 内嵌标记，按出现顺序返回"""
    return re.findall(r"<!--\s*PRODUCT:\s*(\S+?)\s*-->", text)


# ── bubble/clarify 标签 + 内嵌卡片的正则 ──
_BUBBLE_TAG_RE = re.compile(r"</?bubble>", re.IGNORECASE)
_CLARIFY_OPEN_RE = re.compile(r"<clarify>", re.IGNORECASE)
_CLARIFY_CLOSE_RE = re.compile(r"</clarify>", re.IGNORECASE)
_PRODUCT_TAG_RE = re.compile(r"<!--\s*PRODUCT:\s*(\S+?)\s*-->")
_RECOMMEND_TAG_RE = re.compile(r"<!--\s*RECOMMEND:[\s\S]*?-->")
_LEGACY_CLARIFY_RE = re.compile(r"\n?\s*\[\s*question(?:\s*[:：])?", re.IGNORECASE)


def _legacy_clarify_tail_len(text: str) -> int:
    """检测 buffer 尾部是否是旧式 [question: 协议头的半截。"""
    tail = text[-24:].lower()
    prefixes = ["[question:", "[question：", "[ question:", "[ question："]
    best = 0
    for prefix in prefixes:
        for length in range(1, len(prefix)):
            if tail.endswith(prefix[:length]):
                best = max(best, length)
    return best


def _parse_clarify_output(text: str) -> Optional[dict ]:
    """解析 clarify 输出协议，返回可直接发给客户端的结构。"""
    visible = re.sub(r"<!--\s*RECOMMEND:[\s\S]*?-->", "", text, flags=re.IGNORECASE).strip()
    question_matches = list(re.finditer(
        r"(?is)\[\s*question\s*[:：]\s*(.*?)\s+ans\s*[:：]\s*(.*?)\s*\]",
        visible,
    ))
    if not question_matches:
        return None

    questions = []
    intro_match = re.search(
        r"(?is)^\s*text\s*[:：]\s*(.*?)(?=\n\s*\[\s*question\s*[:：]|\Z)",
        visible,
    )
    if intro_match:
        intro_raw = intro_match.group(1)
    else:
        # 兼容模型漏掉 text: 前缀的情况：第一个 question 之前就是简介。
        intro_raw = visible[:question_matches[0].start()]

    intro = re.sub(r"\s+", " ", intro_raw).strip()
    for match in question_matches:
        question = re.sub(r"\s+", " ", match.group(1)).strip()
        answers = [
            item.strip()
            for item in re.split(r"\||｜", match.group(2))
            if item.strip()
        ][:3]
        if question and len(answers) >= 2:
            questions.append({"question": question, "answers": answers})

    if not questions:
        return None
    return {"text": intro, "questions": questions[:2]}


def _extract_product_ids_from_history(history: list[dict]) -> list[str]:
    """从对话历史的 assistant 消息中提取所有 <!--PRODUCT: id--> 标记的商品 ID"""
    ids = []
    seen = set()
    if not history:
        return ids
    for msg in history:
        if msg.get("role") != "assistant":
            continue
        content = msg.get("content", "")
        for pid in re.findall(r"<!--\s*PRODUCT:\s*(\S+?)\s*-->", content):
            if pid not in seen:
                ids.append(pid)
                seen.add(pid)
        # 也解析 RECOMMEND 标记
        m = re.search(r"<!--\s*RECOMMEND:\s*(.+?)\s*-->", content)
        if m:
            for pid in m.group(1).split(","):
                pid = pid.strip()
                if pid and pid not in seen:
                    ids.append(pid)
                    seen.add(pid)
    return ids


# 排除关键词模式：匹配"不要xxx""不含xxx""除了xxx""不想要xxx""不吃xxx"等
_EXCLUDE_PATTERNS = [
    re.compile(r"不要(.+?)(?:的|$)"),
    re.compile(r"不含(.+?)(?:的|$)"),
    re.compile(r"不想要(.+?)(?:的|$)"),
    re.compile(r"不想买(.+?)(?:的|$)"),
    re.compile(r"除了(.+?)(?:的|，|,|$)"),
    re.compile(r"不喜欢(.+?)(?:的|$)"),
    re.compile(r"别推荐(.+?)(?:的|了|$)"),
    re.compile(r"不吃(.+?)(?:的|了|$)"),
    re.compile(r"不用(.+?)(?:的|了|$)"),
    re.compile(r"不想吃(.+?)(?:的|$)"),
    re.compile(r"拒绝(.+?)(?:的|$)"),
]



# 模糊/主观的排除词，不应做硬过滤，留给 LLM 判断
_FUZZY_EXCLUSIONS = {"太贵", "贵", "便宜", "太便宜", "太重", "太大", "太小", "难用", "不好用", "太油", "太干"}


def _extract_exclusions(query: str) -> list[str]:
    """从当前 query 中用正则提取排除关键词（兜底，历史排除由 LLM 负责）。"""
    exclusions = []
    for pattern in _EXCLUDE_PATTERNS:
        for match in pattern.finditer(query):
            keyword = match.group(1).strip()
            if keyword and len(keyword) <= 10 and keyword not in _FUZZY_EXCLUSIONS:
                exclusions.append(keyword)
    return exclusions


def _filter_products(products: list[dict], exclusions: list[str]) -> list[dict]:
    """根据排除关键词过滤商品"""
    if not exclusions:
        return products
    filtered = []
    for p in products:
        # 拼接商品的可搜索文本
        searchable = f"{p['name']} {p['brand']} {p.get('search_text', '')}".lower()
        excluded = False
        for ex in exclusions:
            if ex.lower() in searchable:
                excluded = True
                logger.info("排除商品 %s（命中排除词 %r）", p['id'], ex)
                break
        if not excluded:
            filtered.append(p)
    return filtered


_BUDGET_CAP = re.compile(
    r"(?:预算(?:上限)?\s*[：:]?\s*|不超过\s*|最多\s*)(\d+(?:\.\d+)?)(?![\d.])"
    r"(?!\s*(?:元\s*)?(?:万|千|百|到|至|以上|起|[-~～—]))"
    r"|(?<![\d.])(\d+(?:\.\d+)?)\s*元\s*(?:以内|以下|之内)"
)
_CATALOG_CATEGORIES = ("美妆护肤", "数码电子", "服饰运动", "食品饮料")
_MAX_PRODUCTS = 5
_REFILL_PRODUCTS = 20


def _build_bounded_filters(query: str, budget: float, exclusions: list[str]) -> dict:
    clauses = [{"price_max": {"$lte": budget}}, {"price_max": {"$gt": 0}}]
    categories = [category for category in _CATALOG_CATEGORIES if category in query]
    if len(categories) == 1:
        clauses.append({"category": categories[0]})
    if exclusions:
        clauses.append({"brand": {"$nin": sorted(set(exclusions))}})
    return {"$and": clauses}


def _within_budget(product: dict, budget: float) -> bool:
    price = product.get("price_max")
    return isinstance(price, (int, float)) and 0 < price <= budget


def _build_context(query: str, search_query: str, llm_exclusions: list[str] = None) -> tuple[str, list[dict], list[dict], str]:
    """检索商品并构建 prompt context，返回 (context_text, products, chunks, search_query)

    Args:
        llm_exclusions: LLM 从对话历史中提取的排除关键词（由 _rewrite_query 返回）
    """

    exclusions = list(dict.fromkeys(
        term.removesuffix("品牌").strip()
        for term in (llm_exclusions or []) + _extract_exclusions(query)
        if term.removesuffix("品牌").strip()
    ))
    budget_match = _BUDGET_CAP.search(query)
    budget = float(budget_match.group(1) or budget_match.group(2)) if budget_match else None
    filters = _build_bounded_filters(query, budget, exclusions) if budget is not None else None

    retrieval_options = {"filters": filters} if filters else {}
    result = cached_retrieve(search_query, n_products=_MAX_PRODUCTS, n_chunks=8, **retrieval_options)
    products = _filter_products(result["products"], exclusions)
    chunks = result["chunks"]

    if budget is not None:
        products = [product for product in products if _within_budget(product, budget)]
        if len(products) < _MAX_PRODUCTS:
            try:
                refill = cached_retrieve(
                    search_query, n_products=_REFILL_PRODUCTS, n_chunks=8, filters=filters
                )
            except TimeoutError:
                logger.warning("Budget-constrained refill timed out; retaining first-pass products")
                if not products:
                    raise
            else:
                merged = {product["id"]: product for product in products}
                for product in _filter_products(refill["products"], exclusions):
                    if _within_budget(product, budget):
                        merged.setdefault(product["id"], product)
                products = list(merged.values())[:_MAX_PRODUCTS]
                chunks = list({
                    chunk["chunk_id"]: chunk for chunk in chunks + refill["chunks"]
                }.values())
        logger.info("Budget-constrained RAG: cap=%s selected=%d", budget, len(products))

    if exclusions or budget is not None:
        kept_ids = {product["id"] for product in products}
        chunks = [chunk for chunk in chunks if chunk.get("product_id") in kept_ids]

    parts = []
    for i, p in enumerate(products, 1):
        price_str = f"¥{p['price_min']}"
        if p["price_max"] != p["price_min"]:
            price_str = f"¥{p['price_min']}-{p['price_max']}"
        image_url = f"/static/{p['image_path']}" if p.get("image_path") else ""
        parts.append(
            f"商品{i}（ID: {p['id']}）：{p['name']}\n"
            f"  品牌：{p['brand']}  类目：{p['category']}/{p['sub_category']}\n"
            f"  价格：{price_str}\n"
            f"  图片：{image_url}\n"
            f"  简介：{p['search_text']}"
        )

    # 补充 FAQ/Review
    if chunks:
        parts.append("\n【相关问答和用户评价】")
        for c in chunks[:5]:
            label = "FAQ" if c["type"] == "faq" else f"用户评价(★{c.get('rating', '?')})"
            parts.append(f"- [{label}] {c['content']}")

    context = "\n\n".join(parts)
    return context, products, chunks, search_query


def _build_scenario_context(
    query: str, scenario_key: str,
) -> tuple[str, list[dict], list[dict], str]:
    """场景化跨品类检索，按分组构建 context。返回值与 _build_context 相同。"""
    start = time.perf_counter()
    result = cached_retrieve_scenario(query, scenario_key)
    products = result["products"]
    chunks = result["chunks"]
    groups = result["groups"]
    logger.info(
        "场景检索完成 scenario=%r products=%d chunks=%d 耗时 %.2fs",
        scenario_key, len(products), len(chunks), time.perf_counter() - start,
    )

    parts = [f"【场景搭配：{scenario_key}】"]
    idx = 0
    for group_name, pids in groups.items():
        parts.append(f"\n== {group_name} ==")
        for p in products:
            if p["id"] in pids:
                idx += 1
                price_str = f"¥{p['price_min']}"
                if p["price_max"] != p["price_min"]:
                    price_str = f"¥{p['price_min']}-{p['price_max']}"
                image_url = f"/static/{p['image_path']}" if p.get("image_path") else ""
                parts.append(
                    f"商品{idx}（ID: {p['id']}）：{p['name']}\n"
                    f"  品牌：{p['brand']}  类目：{p['category']}/{p['sub_category']}\n"
                    f"  价格：{price_str}\n"
                    f"  图片：{image_url}\n"
                    f"  简介：{p['search_text']}"
                )

    if chunks:
        parts.append("\n【相关问答和用户评价】")
        for c in chunks[:8]:
            label = "FAQ" if c["type"] == "faq" else f"用户评价(★{c.get('rating', '?')})"
            parts.append(f"- [{label}] {c['content']}")

    context = "\n\n".join(parts)
    return context, products, chunks, query


def generate(query: str, history: list[dict] = None) -> tuple[str, list[dict]]:
    """
    普通请求：返回 (AI 回复文本, 推荐商品列表)
    """
    client = _llm_client

    # 问候/闲聊/自我介绍：跳过 RAG，直接轻量回复
    if _is_greeting(query) or _is_meta_question(query) or _is_casual_chat(query):
        logger.info("问候/元问题/闲聊检测命中 query=%r", query)
        messages = [{"role": "system", "content": _load_prompt("chat_prompt.md")}]
        if history:
            messages.extend(history)
        messages.append({"role": "user", "content": query})
        resp = client.chat.completions.create(model=LLM_MODEL, messages=messages)
        return resp.choices[0].message.content, []

    # query 改写 + 收窄判断 + 排除词提取
    search_query, needs_search, llm_exclusions = _rewrite_query(query, history)

    # 收窄路径：不重新检索，基于上一轮推荐回答
    if not needs_search:
        logger.info("收窄检测命中 query=%r，跳过 RAG", query)
        narrowing_hint = (
            "用户在你上一轮推荐的商品基础上追加了条件或要求你做选择。"
            "请直接基于你之前推荐过的商品信息回答，不需要新的检索结果。"
            "结尾不要承诺提供更多细节、实测、评价等——你没有额外数据可以补充。"
        )
        messages = [{"role": "system", "content": _load_prompt("system_prompt.md") + "\n\n【特殊指令】" + narrowing_hint}]
        if history:
            messages.extend(history)
        messages.append({"role": "user", "content": query})
        resp = client.chat.completions.create(model=LLM_MODEL, messages=messages)
        return resp.choices[0].message.content, []

    # 所有其他 query → RAG + 推荐
    # 场景检测：跨品类场景走多品类检索路径
    scenario_key = _detect_scenario(search_query) or _detect_scenario(query)
    if scenario_key:
        context, products, _chunks, search_query = _build_scenario_context(query, scenario_key)
        mode_hint = _SCENARIO_HINT
    else:
        context, products, _chunks, search_query = _build_context(query, search_query, llm_exclusions)
        # Clarify 模式决策（与 generate_stream 保持一致）
        first_turn = not history
        if first_turn and len(products) >= 3 and _is_vague_browse(query):
            mode_hint = _FORCE_CLARIFY_HINT
        elif len(products) <= 3:
            mode_hint = _NO_CLARIFY_HINT
        else:
            mode_hint = ""

    user_content = f"用户问题：{query}\n\n【检索到的商品】\n{context}{mode_hint}"

    messages = [{"role": "system", "content": _load_prompt("system_prompt.md")}]
    if history:
        messages.extend(history)
    messages.append({"role": "user", "content": user_content})

    resp = client.chat.completions.create(model=LLM_MODEL, messages=messages)
    return resp.choices[0].message.content, products


def generate_stream(query: str, history: list[dict] = None):
    """流式请求：yield 事件字典，驱动前端实时渲染。

    三条核心路径（互斥）：
    1. 问候/闲聊路径：正则 _is_greeting → 元问题 _is_meta_question → LLM 闲聊分类 _is_casual_chat
       命中后跳过 RAG，直接用 chat_prompt 轻量回复（省 10-20s 检索耗时）
    2. 收窄路径：_rewrite_query 判定 NO_SEARCH（用户在已推荐商品中追问/对比/选择）
       不重新检索，从对话历史提取已推荐商品 ID，基于上下文直接回答
    3. RAG 路径：query 改写 → 场景检测 → 向量检索 + Rerank → Clarify/推荐模式决策 → LLM 流式生成

    流式解析层：LLM 输出的 <bubble>/<clarify>/<!--PRODUCT--><!--RECOMMEND--> 标签
    被实时解析为前端事件（text/bubble_break/clarify/product），实现边生成边渲染。

    事件类型：
    - {"type": "text", "content": "..."} — 文本片段
    - {"type": "bubble_break"} — 气泡分段标记
    - {"type": "clarify", "content": {...}} — 追问引导卡片
    - {"type": "product", "content": {...}} — 商品卡片
    - {"type": "done"} — 结束
    """
    try:
        t0 = time.perf_counter()

        # ── 问候/闲聊/自我介绍：跳过 RAG，直接轻量回复 ──
        if _is_greeting(query) or _is_meta_question(query) or _is_casual_chat(query):
            logger.info("问候/元问题/闲聊检测命中 query=%r", query)
            yield {"type": "debug", "event": "intent", "content": {
                "intent": "greeting", "time_ms": 0,
            }}
            messages = [{"role": "system", "content": _load_prompt("chat_prompt.md")}]
            if history:
                messages.extend(history)
            messages.append({"role": "user", "content": query})
            yield {"type": "debug", "event": "messages", "content": messages}
            client = _llm_client
            stream = client.chat.completions.create(
                model=LLM_MODEL, messages=messages, stream=True,
            )
            for chunk in stream:
                delta = chunk.choices[0].delta
                if delta.content:
                    yield {"type": "text", "content": delta.content}
            yield {"type": "done"}
            return

        # ── query 改写 + 收窄判断 + 排除词提取 ──
        search_query, needs_search, llm_exclusions = _rewrite_query(query, history)

        # 收窄前先确认历史里有可复用的商品；没有（如历史被截断/客户端没带 ID 标记）就回退 RAG，
        # 避免走进"基于 0 个历史商品"的收窄路径而答非所问、发不出卡片
        prev_product_ids = _extract_product_ids_from_history(history) if not needs_search else []
        narrow_products = get_products_by_ids(prev_product_ids) if prev_product_ids else []
        if not needs_search and not narrow_products:
            logger.warning("收窄判定但历史无可复用商品，回退 RAG 检索 query=%r", query)
            needs_search = True
            search_query = query

        # ── 收窄路径：不重新检索，基于上一轮推荐回答 ──
        if not needs_search:
            logger.info("收窄检测命中 query=%r，跳过 RAG", query)
            narrow_products_by_id = {p["id"]: p for p in narrow_products}
            # 拉历史商品的 chunks，用于计算卡片评分
            from server.rag.retriever import search_chunks as _search_chunks
            narrow_chunks: list[dict] = []
            for pid in prev_product_ids[:5]:
                extra = _search_chunks(query, n_results=2, where={"product_id": pid})
                for c in extra:
                    if c["chunk_id"] not in {x["chunk_id"] for x in narrow_chunks}:
                        narrow_chunks.append(c)
            logger.info("收窄路径提取到 %d 个历史商品: %s", len(narrow_products), prev_product_ids)

            yield {"type": "debug", "event": "intent", "content": {
                "intent": "narrowing", "time_ms": int((time.perf_counter() - t0) * 1000),
                "history_product_ids": prev_product_ids,
            }}
            narrowing_hint = (
                "用户在你之前推荐的商品基础上追加了条件、做对比或要求你做最终选择。"
                "请直接基于你之前推荐过的商品信息回答，不需要新的检索结果。\n\n"
                "【重要】回复规则：\n"
                "1. 不要重复前面已经详细介绍过的通用参数、性能和评价。用户已经看过了。\n"
                "2. 只针对用户本轮新提出的关切点展开说明。\n"
                "3. 如果之前用过的评价与本轮新需求无关，不要再引用。只引用和新需求直接相关的评价。\n"
                "4. 每提到一款商品，必须在介绍后紧跟 <!--PRODUCT: 商品ID--> 标记，这样用户才能看到商品卡片。\n"
                "5. 结尾不要承诺提供更多细节、实测、评价等——你没有额外数据可以补充。\n"
            )
            messages = [{"role": "system", "content": _load_prompt("system_prompt.md") + "\n\n【特殊指令】" + narrowing_hint}]
            if history:
                messages.extend(history)
            messages.append({"role": "user", "content": query})
            yield {"type": "debug", "event": "messages", "content": messages}
            client = _llm_client
            stream = client.chat.completions.create(
                model=LLM_MODEL, messages=messages, stream=True,
            )
            # 收窄路径解析 bubble + clarify + PRODUCT + RECOMMEND 标签
            narrow_buf = ""
            narrow_bubble_count = 0
            narrow_clarify_buffering = False
            narrow_clarify_buf = ""

            def _narrow_flush(buf):
                nonlocal narrow_bubble_count, narrow_clarify_buffering, narrow_clarify_buf
                events = []
                while buf:
                    m_bubble = _BUBBLE_TAG_RE.search(buf)
                    m_product = _PRODUCT_TAG_RE.search(buf)
                    m_recommend = _RECOMMEND_TAG_RE.search(buf)
                    m_clarify_open = _CLARIFY_OPEN_RE.search(buf) if not narrow_clarify_buffering else None
                    m_clarify_close = _CLARIFY_CLOSE_RE.search(buf) if narrow_clarify_buffering else None

                    first_match = None
                    for m in (m_bubble, m_product, m_recommend, m_clarify_open, m_clarify_close):
                        if m and (first_match is None or m.start() < first_match.start()):
                            first_match = m

                    if first_match is None:
                        if narrow_clarify_buffering:
                            last_lt = buf.rfind("<")
                            if last_lt >= 0 and len(buf) - last_lt < 15:
                                narrow_clarify_buf += buf[:last_lt]
                                buf = buf[last_lt:]
                            else:
                                narrow_clarify_buf += buf
                                buf = ""
                        else:
                            last_lt = buf.rfind("<")
                            if last_lt >= 0 and len(buf) - last_lt < 45:
                                if last_lt > 0:
                                    events.append({"type": "text", "content": _fix_image_urls(buf[:last_lt])})
                                buf = buf[last_lt:]
                            else:
                                if buf:
                                    events.append({"type": "text", "content": _fix_image_urls(buf)})
                                buf = ""
                        break

                    before = buf[:first_match.start()]
                    if narrow_clarify_buffering:
                        narrow_clarify_buf += before
                    elif before:
                        events.append({"type": "text", "content": _fix_image_urls(before)})

                    tag_text = first_match.group(0)

                    if first_match == m_clarify_open:
                        narrow_clarify_buffering = True
                        narrow_clarify_buf = ""
                        narrow_bubble_count += 1
                        if narrow_bubble_count > 1:
                            events.append({"type": "bubble_break"})
                    elif first_match == m_clarify_close:
                        narrow_clarify_buffering = False
                        parsed = _parse_clarify_output(narrow_clarify_buf)
                        if parsed:
                            events.append({"type": "clarify", "content": parsed})
                        else:
                            events.append({"type": "text", "content": narrow_clarify_buf})
                        narrow_clarify_buf = ""
                    elif first_match == m_bubble:
                        if not tag_text.lower().startswith("</"):
                            narrow_bubble_count += 1
                            if narrow_bubble_count > 1:
                                events.append({"type": "bubble_break"})
                    elif first_match == m_product:
                        pid = first_match.group(1)
                        if pid in narrow_products_by_id:
                            events.append(_make_product_event_from_dict(narrow_products_by_id[pid], narrow_chunks))
                            logger.info("收窄路径内嵌商品卡片 product_id=%s", pid)
                        else:
                            logger.warning("收窄路径 PRODUCT ID %s 未在历史商品中找到", pid)
                    elif first_match == m_recommend:
                        pass

                    buf = buf[first_match.end():]
                return events, buf

            for chunk in stream:
                delta = chunk.choices[0].delta
                if delta.content:
                    narrow_buf += delta.content
                    evts, narrow_buf = _narrow_flush(narrow_buf)
                    for evt in evts:
                        yield evt
            if narrow_buf:
                evts, _ = _narrow_flush(narrow_buf + " ")
                for evt in evts:
                    if evt.get("type") == "text" and evt["content"].endswith(" "):
                        evt["content"] = evt["content"][:-1]
                        if not evt["content"]:
                            continue
                    yield evt
            yield {"type": "done"}
            return

        # ── 所有其他 query → RAG + 流式推荐 ──
        # 场景检测：跨品类场景走多品类检索路径
        scenario_key = _detect_scenario(search_query) or _detect_scenario(query)
        if scenario_key:
            context, products, chunks, search_query = _build_scenario_context(query, scenario_key)
            mode_hint = _SCENARIO_HINT
            logger.info("场景搭配模式: scenario=%r, %d个商品", scenario_key, len(products))
        else:
            context, products, chunks, search_query = _build_context(query, search_query, llm_exclusions)
            # ── Clarify 模式决策 ──
            first_turn = not history
            if first_turn and len(products) >= 3 and _is_vague_browse(query):
                mode_hint = _FORCE_CLARIFY_HINT
                logger.info("首轮宽泛品类浏览（%d商品），强制 clarify query=%r", len(products), query)
            elif len(products) <= 3:
                mode_hint = _NO_CLARIFY_HINT
                logger.info("商品数 ≤3（%d个），注入不追问指令", len(products))
            else:
                mode_hint = ""

        yield {"type": "debug", "event": "rag", "content": {
            "search_query": search_query,
            "original_query": query,
            "product_count": len(products),
            "scenario": scenario_key,
            "products": [
                {"id": p["id"], "name": p["name"], "brand": p["brand"],
                 "category": f"{p['category']}/{p['sub_category']}",
                 "price": f"¥{p['price_min']}-{p['price_max']}" if p['price_max'] != p['price_min'] else f"¥{p['price_min']}"}
                for p in products
            ],
            "time_ms": int((time.perf_counter() - t0) * 1000),
        }}

        user_content = f"用户问题：{query}\n\n【检索到的商品】\n{context}{mode_hint}"

        messages = [{"role": "system", "content": _load_prompt("system_prompt.md")}]
        if history:
            messages.extend(history)
        messages.append({"role": "user", "content": user_content})
        yield {"type": "debug", "event": "messages", "content": messages}

        client = _llm_client
        llm_start = time.perf_counter()
        stream = client.chat.completions.create(
            model=LLM_MODEL,
            messages=messages,
            stream=True,
        )
        logger.info("豆包流式请求已建立 query=%r 耗时 %.2fs", query, time.perf_counter() - llm_start)

        # ── 收集完整文本 + 流式解析 bubble/clarify/product 标签 ──
        full_text = []
        emitted_len = 0
        protocol_started = False
        first_token_logged = False
        buffer = ""  # 用于跨 chunk 检测标签
        bubble_count = 0  # 已遇到的 <bubble>/<clarify> 开标签数量
        clarify_buffering = False  # 是否在 <clarify>...</clarify> 内
        clarify_buf = ""  # <clarify> 内的缓冲文本
        clarify_emitted = False  # 是否已通过 <clarify> 标签发出 clarify 事件
        legacy_clarify_buffering = False
        legacy_clarify_buf = ""
        products_by_id = {p["id"]: p for p in products}

        def _flush_buffer(buf: str):
            """处理 buffer 中的文本：过滤标签，yield text/bubble_break/product/clarify 事件。
            返回 (events_list, remaining_buffer)"""
            nonlocal bubble_count, clarify_buffering, clarify_buf, clarify_emitted
            nonlocal legacy_clarify_buffering, legacy_clarify_buf
            events = []
            while buf:
                if legacy_clarify_buffering:
                    legacy_clarify_buf += buf
                    buf = ""
                    break

                # 检查各类标签
                m_bubble = _BUBBLE_TAG_RE.search(buf)
                m_product = _PRODUCT_TAG_RE.search(buf)
                m_recommend = _RECOMMEND_TAG_RE.search(buf)
                m_clarify_open = _CLARIFY_OPEN_RE.search(buf) if not clarify_buffering else None
                m_clarify_close = _CLARIFY_CLOSE_RE.search(buf) if clarify_buffering else None
                m_legacy_clarify = _LEGACY_CLARIFY_RE.search(buf) if not clarify_buffering else None

                # 找最早出现的标签
                first_match = None
                for m in (
                    m_bubble, m_product, m_recommend, m_clarify_open,
                    m_clarify_close, m_legacy_clarify,
                ):
                    if m and (first_match is None or m.start() < first_match.start()):
                        first_match = m

                if first_match is None:
                    if clarify_buffering:
                        # 在 <clarify> 内，检查尾部是否有不完整的 </clarify> 标签
                        last_lt = buf.rfind("<")
                        if last_lt >= 0 and len(buf) - last_lt < 15:
                            clarify_buf += buf[:last_lt]
                            buf = buf[last_lt:]
                        else:
                            clarify_buf += buf
                            buf = ""
                    else:
                        last_lt = buf.rfind("<")
                        legacy_tail = _legacy_clarify_tail_len(buf)
                        if last_lt >= 0 and len(buf) - last_lt < 45:
                            if last_lt > 0:
                                events.append({"type": "text", "content": _fix_image_urls(buf[:last_lt])})
                            buf = buf[last_lt:]
                        elif legacy_tail > 0:
                            visible = buf[:-legacy_tail]
                            if visible:
                                events.append({"type": "text", "content": _fix_image_urls(visible)})
                            buf = buf[-legacy_tail:]
                        else:
                            if buf:
                                events.append({"type": "text", "content": _fix_image_urls(buf)})
                            buf = ""
                    break

                # 标签前的文本
                before = buf[:first_match.start()]
                if clarify_buffering:
                    clarify_buf += before
                elif before:
                    events.append({"type": "text", "content": _fix_image_urls(before)})

                tag_text = first_match.group(0)

                if first_match == m_clarify_open:
                    # <clarify> → 开始缓冲 clarify 内容，同时作为新段落
                    clarify_buffering = True
                    clarify_buf = ""
                    bubble_count += 1
                    if bubble_count > 1:
                        events.append({"type": "bubble_break"})
                elif first_match == m_clarify_close:
                    # </clarify> → 解析并发出 clarify 事件
                    clarify_buffering = False
                    parsed = _parse_clarify_output(clarify_buf)
                    if parsed:
                        events.append({"type": "clarify", "content": parsed})
                        clarify_emitted = True
                        logger.info("clarify 标签已解析 questions=%d", len(parsed["questions"]))
                    else:
                        # 解析失败，当作纯文本
                        events.append({"type": "text", "content": clarify_buf})
                        logger.warning("clarify 标签解析失败，降级为纯文本")
                    clarify_buf = ""
                elif first_match == m_bubble:
                    if not tag_text.lower().startswith("</"):
                        bubble_count += 1
                        if bubble_count > 1:
                            events.append({"type": "bubble_break"})
                elif first_match == m_product:
                    pid = first_match.group(1)
                    if pid in products_by_id:
                        events.append(_make_product_event_from_dict(products_by_id[pid], chunks))
                        logger.info("内嵌商品卡片 product_id=%s", pid)
                    else:
                        logger.warning("内嵌 PRODUCT ID %s 未在检索结果中找到", pid)
                elif first_match == m_recommend:
                    pass
                elif first_match == m_legacy_clarify:
                    # 兼容旧 prompt 输出的 [question: ... ans: ...] 协议。
                    # 从这里开始不再发 text，等待结束后统一解析成 clarify 事件。
                    legacy_clarify_buffering = True
                    legacy_clarify_buf += tag_text

                buf = buf[first_match.end():]

            return events, buf

        for chunk in stream:
            delta = chunk.choices[0].delta
            if delta.content:
                if not first_token_logged:
                    logger.info("豆包首 token query=%r 耗时 %.2fs", query, time.perf_counter() - llm_start)
                    first_token_logged = True
                full_text.append(delta.content)
                buffer += delta.content

                # 尝试处理 buffer
                events, buffer = _flush_buffer(buffer)
                for evt in events:
                    # 只丢弃真正的空串；保留纯换行/空格——它们是 Markdown 的段落/标题结构。
                    # 豆包流式把 \n 单独成 token，用 not strip() 误删会让标题/分段全塌成一行。
                    if evt.get("type") == "text" and evt["content"] == "":
                        continue
                    yield evt

        # 流式结束，flush 剩余 buffer
        if buffer:
            events, buffer = _flush_buffer(buffer + " ")  # 加空格确保尾部标签不被误留
            for evt in events:
                if evt.get("type") == "text" and evt["content"].endswith(" "):
                    evt["content"] = evt["content"][:-1]  # 去掉补的空格
                    if not evt["content"]:
                        continue
                # 同上：只丢弃真正的空串，保留换行
                if evt.get("type") == "text" and evt["content"] == "":
                    continue
                yield evt

        complete_text = "".join(full_text)

        # ── 后置 clarify 兜底：仅当流式阶段未发出过 clarify（LLM 没用 <clarify> 标签但输出了 [question:] 格式）──
        clean_for_clarify = re.sub(r"</?(?:bubble|clarify)>", "", complete_text, flags=re.IGNORECASE)
        if not clarify_emitted and "[question" in clean_for_clarify.lower():
            clarify_source = clean_for_clarify
            clarify = _parse_clarify_output(clarify_source)
            if clarify:
                yield {"type": "clarify", "content": clarify}
                logger.info("后置 clarify 兜底 query=%r questions=%d", query, len(clarify["questions"]))
                yield {"type": "done"}
                return

        # ── 解析末尾 RECOMMEND 标记（1-2款大卡场景）──
        # 内嵌 PRODUCT 标记已在流式阶段实时发出，这里只处理 RECOMMEND
        inline_product_ids = _parse_inline_product_ids(complete_text)
        if inline_product_ids:
            logger.info("内嵌 PRODUCT 标记已处理 %d 个: %s", len(inline_product_ids), inline_product_ids)
        else:
            # 没有内嵌标记，尝试末尾 RECOMMEND
            recommended_ids = _parse_recommend_ids(complete_text)
            logger.info("LLM 推荐商品 IDs=%s（检索到 %d 个）", recommended_ids, len(products))

            products_to_send = []
            if recommended_ids:
                products_to_send = [p for p in products if p["id"] in recommended_ids]
                if not products_to_send:
                    products_to_send = products
                    logger.warning("RECOMMEND ID 匹配失败，兜底发全部商品卡片")
            elif re.search(r"<!--\s*RECOMMEND:\s*-->", complete_text):
                logger.info("RECOMMEND 为空，不发商品卡片")
            else:
                logger.info("LLM 未输出 RECOMMEND/PRODUCT 标记，不发商品卡片")

            for p in products_to_send:
                yield _make_product_event_from_dict(p, chunks)
    except Exception as e:
        logger.exception("生成回复失败 query=%r", query)
        yield {"type": "text", "content": f"抱歉，服务暂时出了点问题，请稍后再试（{type(e).__name__}）"}

    yield {"type": "done"}


if __name__ == "__main__":
    print("=== 测试 Agent 编排（普通模式）===\n")
    reply, prods = generate("推荐一款适合油皮的平价洗面奶")
    print(reply)
    print(f"\n推荐了 {len(prods)} 个商品")

    print("\n\n=== 测试 Agent 编排（流式模式）===\n")
    for event in generate_stream("有没有好用的防晒霜"):
        if event["type"] == "text":
            print(event["content"], end="", flush=True)
        elif event["type"] == "product":
            print(f"\n  [商品卡片] {event['content']['name']} ¥{event['content']['price']}")
        elif event["type"] == "done":
            print("\n\n--- 结束 ---")
