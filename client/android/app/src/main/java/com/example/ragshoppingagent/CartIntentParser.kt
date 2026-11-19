package com.example.ragshoppingagent

import org.json.JSONObject

/**
 * 购物车意图识别与商品匹配。
 * 从用户自然语言中解析加购/删除/改数量/下单等意图，
 * 并通过序数词、指示词、关键词匹配定位目标商品。
 */
object CartIntentParser {

    // ── 意图识别正则 ──────────────────────────────────────────

    val checkoutPattern = Regex("(下单|结算|买单|去付款|提交订单|我要买了)")

    val removeFromCartPattern = Regex(
        "(从购物车.{0,3}(删除|删掉|移除|去掉))" +
        "|(删除|删掉|移除|去掉).{0,15}(购物车|商品)" +
        "|购物车.{0,5}(删除|删掉|移除|去掉)" +
        "|(把.{1,20}(从购物车)?(删除|删掉|移除|去掉))" +
        "|(删除|删掉|移除).{1,20}$" +                       // "删除阿迪达斯"（去掉"去掉"，太容易和排除条件冲突）
        "|(.{2,20})(不要了|不想要了)"                        // "阿迪达斯不要了"（至少2字避免"不要了"单独触发）
    )

    val changeQtyPattern = Regex(
        "(改成|改为|变成|调成|改到|设为)\\s*([一二三四五六七八九十\\d]+)\\s*(个|份|件)?" +
        "|(数量).{0,5}([一二三四五六七八九十\\d]+)" +
        "|(多|再)(加|来|买)([一二三四五六七八九十\\d]+)[个份件款]" +  // "多加一个"/"再来一件"
        "|(加|多要)([一二三四五六七八九十\\d]+)[个份件款]" +         // "加一款"
        "|(减|少要?)([一二三四五六七八九十\\d]+)[个份件款]"          // "减一个"
    )

    val addToCartPattern = Regex(
        "(加入购物车|加购|加到购物车|放入购物车|放购物车|买这个|买那个)" +
        "|(我[要想]买(这|那|它))" +            // "我想买这个" OK, "我想买3款精华" 不触发
        "|(把.{1,25}(加入|加到|放入|放进|放到)购物车)" +
        "|(.{1,25}(加入|加到|放入|放进|放到)购物车)"
    )

    val ordinalPattern = Regex("第([一二三四五六七八九十\\d]+)(个|款)")
    val batchAllPattern = Regex("(都|全部|全都|统统|通通|每一?[个款]|所有|这[几\\d一二三四五六七八九十]+[个款]|前[\\d一二三四五六七八九十]+[个款]|后[\\d一二三四五六七八九十]+[个款])")
    val batchCountPattern = Regex("(?:这|前|后)([\\d一二三四五六七八九十]+)[个款]")
    val batchTailPattern = Regex("后([\\d一二三四五六七八九十]+)[个款]")
    val demonstrativePattern = Regex("(这个|那个|这款|那款)")

    // ── 意图分类 ──────────────────────────────────────────────

    val clearCartPattern = Regex("(清空|清除|清掉|倒空)购物车")
    val viewCartPattern = Regex("(购物车.{0,3}(有什么|有啥|里面|里有|有哪些|是什么|都有))" +
        "|(看看|查看|打开|显示)购物车" +
        "|购物车.{0,3}(看看|查看|瞧瞧)")

    enum class Intent {
        ADD_AND_CHECKOUT,  // 加购+下单
        CHECKOUT,          // 纯下单
        CLEAR_CART,        // 清空购物车
        VIEW_CART,         // 查看购物车
        REMOVE,            // 删除
        CHANGE_QTY,        // 改数量
        ADD,               // 加购
        NONE               // 非购物车意图
    }

    fun classify(query: String, hasProducts: Boolean, hasCartItems: Boolean): Intent {
        val hasCheckout = checkoutPattern.containsMatchIn(query)
        val hasAdd = addToCartPattern.containsMatchIn(query)

        if (hasAdd && hasCheckout && hasProducts) return Intent.ADD_AND_CHECKOUT
        if (hasCheckout) return Intent.CHECKOUT
        if (clearCartPattern.containsMatchIn(query) && hasCartItems) return Intent.CLEAR_CART
        if (viewCartPattern.containsMatchIn(query)) return Intent.VIEW_CART
        if (removeFromCartPattern.containsMatchIn(query) && hasCartItems) return Intent.REMOVE
        if (changeQtyPattern.containsMatchIn(query) && hasCartItems) return Intent.CHANGE_QTY
        if (hasAdd && hasProducts) return Intent.ADD
        return Intent.NONE
    }

    // ── 关键词提取 ──────────────────────────────────────────

    fun extractCartKeyword(query: String): String {
        Regex("把(.{1,25}?)(加入|加到|放入|放进|放到)购物车").find(query)?.let {
            val kw = it.groupValues[1].trim()
            if (kw.isNotBlank() && kw !in listOf("我", "帮我", "请", "给我", "它", "这个", "那个")) return kw
        }
        Regex("(.{1,25}?)(加入|加到|放入|放进|放到)购物车").find(query)?.let {
            val kw = it.groupValues[1].trim()
            if (kw.isNotBlank() && kw !in listOf("我", "帮我", "请", "给我", "它", "这个", "那个", "")) return kw
        }
        return ""
    }

    fun extractCartOperationKeyword(query: String): String {
        Regex("把(.{1,20}?)(从购物车)?(删除|删掉|移除|去掉)").find(query)?.let {
            val kw = it.groupValues[1].trim()
            if (kw.isNotBlank() && kw !in listOf("我", "帮我", "请", "它")) return kw
        }
        Regex("(删除|删掉|移除|去掉)(.{1,20}?)$").find(query)?.let {
            val kw = it.groupValues[2].trim().replace(Regex("(商品|购物车)"), "").trim()
            if (kw.isNotBlank()) return kw
        }
        // "阿迪达斯不要了"
        Regex("(.{1,20}?)(不要了|不想要了)").find(query)?.let {
            val kw = it.groupValues[1].trim().replace(Regex("(购物车里?的?|那个|把)"), "").trim()
            if (kw.isNotBlank() && kw !in listOf("我", "帮我", "请", "它")) return kw
        }
        Regex("(把)?(.{1,20}?)(改成|改为|变成|调成|要|改到|设为)").find(query)?.let {
            val kw = it.groupValues[2].trim().replace("把", "").trim()
            if (kw.isNotBlank() && kw !in listOf("我", "帮我", "请", "它", "数量")) return kw
        }
        // "购物车那个xxx给我加一款" — 提取购物车后面到动词前面的部分
        Regex("购物车.{0,3}(那个|的)?(.{1,20}?)(给我|帮我)?(多|再)?(加|减|来)").find(query)?.let {
            val kw = it.groupValues[2].trim().replace(Regex("(那个|给我|帮我)"), "").trim()
            if (kw.isNotBlank() && kw !in listOf("我", "帮我", "请", "它")) return kw
        }
        return ""
    }

    /**
     * 提取目标数量。
     * 支持绝对量（"改成3个"→3）和增减量（"加一个"→+1，"减一个"→-1）。
     * 增减量返回负数表示减少，正数表示增加，需调用方用当前数量计算最终值。
     * 返回 null 表示无法解析。isRelative=true 表示是增减量。
     */
    data class QtyResult(val qty: Int, val isRelative: Boolean)

    fun extractTargetQty(query: String): Int? {
        val result = extractTargetQtyEx(query) ?: return null
        return result.qty
    }

    fun extractTargetQtyEx(query: String): QtyResult? {
        // 绝对量：改成/变成 X 个
        Regex("(改成|改为|变成|调成|改到|设为)\\s*([一二三四五六七八九十\\d]+)\\s*(个|份|件)?").find(query)?.let {
            val n = parseChineseOrdinal(it.groupValues[2]) ?: return null
            return QtyResult(n, false)
        }
        Regex("(数量).{0,5}([一二三四五六七八九十\\d]+)").find(query)?.let {
            val n = parseChineseOrdinal(it.groupValues[2]) ?: return null
            return QtyResult(n, false)
        }
        // 增量：加/多加/再加 X 个
        Regex("(多|再)?(加|来|多要|买)([一二三四五六七八九十\\d]+)[个份件款]").find(query)?.let {
            val n = parseChineseOrdinal(it.groupValues[3]) ?: return null
            return QtyResult(n, true)
        }
        // 减量：减 X 个
        Regex("(减|少要?)([一二三四五六七八九十\\d]+)[个份件款]").find(query)?.let {
            val n = parseChineseOrdinal(it.groupValues[2]) ?: return null
            return QtyResult(-n, true)
        }
        return null
    }

    // ── 商品匹配 ──────────────────────────────────────────────

    /** 对一组候选商品按关键词打分，返回得分>0 的商品（降序） */
    fun scoreByKeyword(keyword: String, candidates: List<JSONObject>): List<JSONObject> {
        if (keyword.isBlank()) return candidates
        val cleanKw = keyword
            .replace(Regex("(这款|那款|这个|那个|帮我|把|请|给我)"), "")
            .trim()
        if (cleanKw.isBlank()) return candidates

        val scored = candidates.map { product ->
            val name = product.optString("name", "")
            val shortName = product.optString("short_name", "")
            val brand = product.optString("brand", "")
            val aliasArr = product.optJSONArray("aliases")
            val aliasStr = if (aliasArr != null) {
                (0 until aliasArr.length()).joinToString(" ") { aliasArr.optString(it) }
            } else ""

            var score = 0
            if (aliasArr != null) {
                for (i in 0 until aliasArr.length()) {
                    if (aliasArr.optString(i).equals(cleanKw, ignoreCase = true)) {
                        score += 20; break
                    }
                }
            }
            if (name.contains(cleanKw, ignoreCase = true)) score += 10
            if (shortName.contains(cleanKw, ignoreCase = true)) score += 10
            if (brand.contains(cleanKw, ignoreCase = true)) score += 5
            if (aliasStr.contains(cleanKw, ignoreCase = true)) score += 15
            val keyChars = cleanKw.filter { it !in " 的了个一" }.toSet()
            val nameChars = (name + shortName + brand + aliasStr).toSet()
            val overlap = keyChars.intersect(nameChars).size
            if (keyChars.isNotEmpty() && keyChars.size >= 2) {
                score += overlap * 3 / keyChars.size
            }
            Pair(product, score)
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        return scored.map { it.first }
    }

    /**
     * 从会话商品池中匹配目标商品。
     * @param keyword 提取的关键词
     * @param query 原始用户输入
     * @param lastTurnProductIds 最近一轮推荐的商品 ID 列表
     * @param allSessionProducts 全会话商品池（id→JSONObject）
     */
    fun matchProducts(
        keyword: String,
        query: String,
        lastTurnProductIds: List<String>,
        allSessionProducts: Map<String, JSONObject>,
    ): List<JSONObject> {
        // 0. 位置词："最后一款"、"倒数第X款"
        if (Regex("最后(一个|一款|那个|那款|的)").containsMatchIn(query) && lastTurnProductIds.isNotEmpty()) {
            val last = allSessionProducts[lastTurnProductIds.last()]
            if (last != null) return listOf(last)
        }
        if (Regex("第一个|第一款|头一个").containsMatchIn(query) && lastTurnProductIds.isNotEmpty()) {
            val first = allSessionProducts[lastTurnProductIds.first()]
            if (first != null) return listOf(first)
        }

        // 1. 序数词
        val ordMatches = ordinalPattern.findAll(query).toList()
        if (ordMatches.isNotEmpty()) {
            return ordMatches.mapNotNull { m ->
                val ord = parseChineseOrdinal(m.groupValues[1])
                if (ord != null && ord >= 1 && ord <= lastTurnProductIds.size) {
                    allSessionProducts[lastTurnProductIds[ord - 1]]
                } else null
            }.distinctBy { it.optString("id") }
        }

        // 2. 指示词 + 关键词
        if (demonstrativePattern.containsMatchIn(query)) {
            val recentProducts = lastTurnProductIds.mapNotNull { allSessionProducts[it] }
            val pool = recentProducts.ifEmpty { allSessionProducts.values.toList().takeLast(3) }
            if (pool.size == 1) return pool
            val filtered = scoreByKeyword(keyword, pool)
            if (filtered.isNotEmpty()) return filtered
            return pool
        }

        // 3. 无关键词
        if (keyword.isBlank()) {
            val products = lastTurnProductIds.mapNotNull { allSessionProducts[it] }
            return products.ifEmpty { allSessionProducts.values.toList() }
        }

        // 4. 纯关键词匹配
        return scoreByKeyword(keyword, allSessionProducts.values.toList())
    }

    /** 在购物车商品中匹配（复用 scoreByKeyword 的完整打分逻辑） */
    fun matchCartProducts(keyword: String, query: String, cartItems: List<JSONObject>): List<JSONObject> {
        if (cartItems.isEmpty()) return emptyList()

        // 位置词
        if (Regex("最后(一个|一款|那个|那款|的)?").containsMatchIn(query)) {
            return listOf(cartItems.last())
        }

        ordinalPattern.find(query)?.let { m ->
            val ord = parseChineseOrdinal(m.groupValues[1])
            if (ord != null && ord >= 1 && ord <= cartItems.size) return listOf(cartItems[ord - 1])
            return emptyList()
        }

        if (keyword.isBlank()) return cartItems

        return scoreByKeyword(keyword, cartItems)
    }

    fun parseChineseOrdinal(s: String): Int? {
        val map = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
        map[s.firstOrNull()]?.let { return it }
        return s.toIntOrNull()
    }

    /** 根据商品品类生成后续钩子话术 */
    fun cartFollowUpHook(product: JSONObject): String {
        val category = product.optString("sub_category")
            .ifBlank { product.optString("category", "") }
        val catShort = category.substringAfterLast("/").ifBlank { "商品" }

        val hooks = listOf(
            "还想看看其他${catShort}吗？或者对这款有什么想了解的，随时问我。",
            "要不要我帮你对比一下类似的${catShort}？看看还有没有更合适的。",
            "还有其他想逛的吗？我可以继续帮你找。",
            "需要我帮你看看这款${catShort}的详细评价吗？",
            "如果还想挑挑${catShort}，告诉我你的预算和需求，我帮你筛选。",
        )
        return hooks[(System.currentTimeMillis() % hooks.size).toInt()]
    }
}
