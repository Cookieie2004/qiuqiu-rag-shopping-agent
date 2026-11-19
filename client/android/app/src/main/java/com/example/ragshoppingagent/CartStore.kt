package com.example.ragshoppingagent

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 购物车 / 收藏 的本地状态，SharedPreferences 持久化。
 *
 * - 购物车项：商品 JSON + "qty" 字段，按商品 id 去重
 * - 收藏项：商品 JSON
 * 任何修改都会即时落盘并通知监听者（角标、购物车页刷新）。
 */
object CartStore {
    private const val PREFS = "cart_store"
    private const val KEY_CART = "cart_items"
    private const val KEY_FAV = "fav_items"
    private const val KEY_BUILD_TIME = "last_build_time"

    private var prefs: SharedPreferences? = null
    private val cart = mutableListOf<JSONObject>()   // 每项含商品字段 + qty
    private val favs = mutableListOf<JSONObject>()    // 商品 JSON
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 安装新版本时清空购物车和收藏
        val buildTime = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: PackageManager.NameNotFoundException) { 0L }
        val savedBuildTime = prefs!!.getLong(KEY_BUILD_TIME, 0L)
        if (buildTime != savedBuildTime) {
            prefs!!.edit().putLong(KEY_BUILD_TIME, buildTime).apply()
            // 新安装 → 不 load 旧数据，直接清空
            clearAll()
            return
        }
        load()
    }

    fun clearAll() {
        cart.clear()
        favs.clear()
        notifyChanged()
    }

    fun clearCart() {
        cart.clear()
        notifyChanged()
    }

    // ── 监听 ──
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() {
        save()
        listeners.toList().forEach { it() }
    }

    // ── 查询 ──
    fun cartItems(): List<JSONObject> = cart.toList()
    fun favItems(): List<JSONObject> = favs.toList()
    fun cartCount(): Int = cart.size                                   // 去重后的商品种类数
    fun favCount(): Int = favs.size
    fun cartTotal(): Int = cart.sumOf { parsePrice(it.optString("price")) * it.optInt("qty", 1) }
    fun isFav(id: String): Boolean = favs.any { it.optString("id") == id }
    fun inCart(id: String): Boolean = cart.any { it.optString("id") == id }

    // ── 购物车操作 ──
    /** 加入购物车：按 id + 规格 去重，同款同规格数量 +1，否则新增。返回该商品当前数量。 */
    fun addToCart(product: JSONObject): Int {
        val id = product.optString("id")
        if (id.isBlank()) return 0
        val specs = product.optString("selected_specs", "")
        val existing = cart.firstOrNull {
            it.optString("id") == id && it.optString("selected_specs", "") == specs
        }
        val qty: Int
        if (existing != null) {
            qty = existing.optInt("qty", 1) + 1
            existing.put("qty", qty)
        } else {
            val item = copyProduct(product).put("qty", 1)
            cart.add(item)
            qty = 1
        }
        notifyChanged()
        return qty
    }

    fun setQty(id: String, qty: Int) {
        val item = cart.firstOrNull { it.optString("id") == id } ?: return
        if (qty <= 0) {
            cart.remove(item)
        } else {
            item.put("qty", qty)
        }
        notifyChanged()
    }

    fun removeFromCart(id: String) {
        cart.removeAll { it.optString("id") == id }
        notifyChanged()
    }

    // ── 收藏操作 ──
    /** 切换收藏，返回切换后是否已收藏。 */
    fun toggleFav(product: JSONObject): Boolean {
        val id = product.optString("id")
        if (id.isBlank()) return false
        val existing = favs.firstOrNull { it.optString("id") == id }
        val nowFaved: Boolean
        if (existing != null) {
            favs.remove(existing)
            nowFaved = false
        } else {
            favs.add(copyProduct(product))
            nowFaved = true
        }
        notifyChanged()
        return nowFaved
    }

    // ── 价格解析：从 "¥89" / "¥89-128" 取首个整数 ──
    fun parsePrice(price: String): Int =
        Regex("\\d+").find(price)?.value?.toIntOrNull() ?: 0

    // ── 持久化 ──
    private fun copyProduct(p: JSONObject): JSONObject = JSONObject(p.toString())

    private fun load() {
        val p = prefs ?: return
        cart.clear(); favs.clear()
        runCatching {
            val arr = JSONArray(p.getString(KEY_CART, "[]"))
            for (i in 0 until arr.length()) cart.add(arr.getJSONObject(i))
        }
        runCatching {
            val arr = JSONArray(p.getString(KEY_FAV, "[]"))
            for (i in 0 until arr.length()) favs.add(arr.getJSONObject(i))
        }
    }

    private fun save() {
        val p = prefs ?: return
        p.edit()
            .putString(KEY_CART, JSONArray(cart).toString())
            .putString(KEY_FAV, JSONArray(favs).toString())
            .apply()
    }
}
