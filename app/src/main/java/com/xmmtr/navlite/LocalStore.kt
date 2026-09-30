package com.xmmtr.navlite

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

data class SavedPoi(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)

data class HistoryEntry(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val time: Long
)

data class SavedPlace(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)

object LocalStore {
    const val SLOT_HOME = "home"
    const val SLOT_COMPANY = "company"

    const val HISTORY_LIMIT = 50
    const val HISTORY_RECENT = 8
    const val PLACES_LIMIT = 10
    const val PLACE_NAME_MAX = 10

    // M1.9/M1.10 设置项取值常量（与 strings.xml 的 string-array 顺序一一对应）
    const val DAY_NIGHT_AUTO = 0
    const val DAY_NIGHT_DAY = 1
    const val DAY_NIGHT_NIGHT = 2

    const val STRATEGY_SMART = 0
    const val STRATEGY_SHORTEST_TIME = 1
    const val STRATEGY_AVOID_CONGESTION = 2
    const val STRATEGY_AVOID_HIGHSPEED = 3
    const val STRATEGY_PRIORITY_HIGHSPEED = 4

    const val SEARCH_TOP = 0
    const val SEARCH_BOTTOM = 1

    const val ORIENT_CAR_UP = 0
    const val ORIENT_NORTH_UP = 1

    const val BROADCAST_DETAIL = 0
    const val BROADCAST_CONCISE = 1

    // M1.14 R25②：App 语言（设置页单按钮循环切换，默认中文=现状）
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    // 同目的地判定容差（01 v1.3 默认拍板 9：约 1 米级，避免同一地点重复占条）
    private const val COORD_TOLERANCE = 1e-5

    private const val PREFS = "navlite"
    private const val KEY_HISTORY = "history.items"
    private const val KEY_PLACES = "places.items"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedPoi(context: Context, slot: String): SavedPoi? {
        val store = prefs(context)
        val name = store.getString("$slot.name", null)
        val latitude = store.getString("$slot.lat", null)?.toDoubleOrNull()
        val longitude = store.getString("$slot.lng", null)?.toDoubleOrNull()
        if (name == null || latitude == null || longitude == null) return null
        return SavedPoi(name, store.getString("$slot.address", "").orEmpty(), latitude, longitude)
    }

    fun savePoi(context: Context, slot: String, poi: SavedPoi) {
        prefs(context).edit()
            .putString("$slot.name", poi.name)
            .putString("$slot.address", poi.address)
            .putString("$slot.lat", poi.latitude.toString())
            .putString("$slot.lng", poi.longitude.toString())
            .apply()
    }

    fun clearPoi(context: Context, slot: String) {
        prefs(context).edit()
            .remove("$slot.name")
            .remove("$slot.address")
            .remove("$slot.lat")
            .remove("$slot.lng")
            .apply()
    }

    fun isMuted(context: Context) = prefs(context).getBoolean("voice.muted", false)

    fun setMuted(context: Context, muted: Boolean) {
        prefs(context).edit().putBoolean("voice.muted", muted).apply()
    }

    fun isSimulate(context: Context) = prefs(context).getBoolean("navi.simulate", false)

    fun setSimulate(context: Context, simulate: Boolean) {
        prefs(context).edit().putBoolean("navi.simulate", simulate).apply()
    }

    // ---- M1.9 R16 通用设置 / R17 首页设置 / R18 算路偏好（全部仅存本机 SharedPreferences）----

    // 昼夜模式：0=自动跟随系统深色模式（默认） 1=白天 2=夜间；导航页与首页地图均应用
    fun dayNightMode(context: Context) = prefs(context).getInt("map.daynight", DAY_NIGHT_AUTO)

    fun setDayNightMode(context: Context, mode: Int) {
        prefs(context).edit().putInt("map.daynight", mode).apply()
    }

    // 路况显示（AMapNaviViewOptions.setTrafficLine），默认开
    fun isTrafficLineOn(context: Context) = prefs(context).getBoolean("map.trafficline", true)

    fun setTrafficLineOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("map.trafficline", on).apply()
    }

    // 算路偏好：0=智能推荐（默认） 1=时间短 2=躲避拥堵 3=不走高速 4=高速优先；
    // 下标仅作存储序号，SDK 策略常量映射在 NaviActivity（strategyConstant）
    fun routeStrategy(context: Context) = prefs(context).getInt("navi.strategy", STRATEGY_SMART)

    fun setRouteStrategy(context: Context, strategy: Int) {
        prefs(context).edit().putInt("navi.strategy", strategy).apply()
    }

    // ---- M1.10 R17 重写（首页地图化）/ R16 完整清单（§2.8 本期档）----

    // 搜索框位置（R17 重写，v1.3.0 的 home.shortcuts 快捷区位置方案作废）：0=顶部（默认） 1=底部
    fun searchPosition(context: Context) = prefs(context).getInt("home.searchpos", SEARCH_TOP)

    fun setSearchPosition(context: Context, position: Int) {
        prefs(context).edit().putInt("home.searchpos", position).apply()
    }

    // 导航朝向：0=车头朝上（默认） 1=北向上（AMapNaviView.setNaviMode）
    fun naviOrientation(context: Context) = prefs(context).getInt("navi.orientation", ORIENT_CAR_UP)

    fun setNaviOrientation(context: Context, orientation: Int) {
        prefs(context).edit().putInt("navi.orientation", orientation).apply()
    }

    // 3D 视角（options.setTilt/setAutoChangeZoom），默认开
    fun isTilt3dOn(context: Context) = prefs(context).getBoolean("navi.tilt3d", true)

    fun setTilt3dOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("navi.tilt3d", on).apply()
    }

    // 手势进入 3D（UiSettings.setTiltGesturesEnabled），默认开
    fun isTiltGestureOn(context: Context) = prefs(context).getBoolean("navi.tiltgesture", true)

    fun setTiltGestureOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("navi.tiltgesture", on).apply()
    }

    // 导航屏幕常亮（options.setScreenAlwaysBright），默认开
    fun isScreenBrightOn(context: Context) = prefs(context).getBoolean("navi.screenbright", true)

    fun setScreenBrightOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("navi.screenbright", on).apply()
    }

    // 车道信息（options.setLaneInfoShow），默认开
    fun isLaneInfoOn(context: Context) = prefs(context).getBoolean("navi.laneinfo", true)

    fun setLaneInfoOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("navi.laneinfo", on).apply()
    }

    // 路口放大图（options.setRealCrossDisplayShow/setModeCrossDisplayShow），默认开
    fun isCrossShowOn(context: Context) = prefs(context).getBoolean("navi.crossshow", true)

    fun setCrossShowOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("navi.crossshow", on).apply()
    }

    // 电子眼气泡与提醒（options.setCameraBubbleShow/setCameraInfoUpdateEnabled），默认开
    fun isCameraBubbleOn(context: Context) = prefs(context).getBoolean("navi.camerabubble", true)

    fun setCameraBubbleOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("navi.camerabubble", on).apply()
    }

    // 指南针（主页地图 UiSettings + 导航页 options.setCompassEnabled），默认关（对齐高德）
    fun isCompassOn(context: Context) = prefs(context).getBoolean("map.compass", false)

    fun setCompassOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("map.compass", on).apply()
    }

    // 播报详细度（AMapNavi.setBroadcastMode + BroadcastMode）：0=详细（默认） 1=简洁
    fun broadcastMode(context: Context) = prefs(context).getInt("navi.broadcast", BROADCAST_DETAIL)

    fun setBroadcastModePref(context: Context, mode: Int) {
        prefs(context).edit().putInt("navi.broadcast", mode).apply()
    }

    // M1.14 R25②：界面语言（新增键，既有键值语义不动）
    fun appLang(context: Context) = prefs(context).getString("app.lang", LANG_ZH).orEmpty().let {
        if (it == LANG_EN) LANG_EN else LANG_ZH
    }

    fun setAppLang(context: Context, lang: String) {
        prefs(context).edit().putString("app.lang", if (lang == LANG_EN) LANG_EN else LANG_ZH).apply()
    }

    // v1.1：高德 Key 改为运行时方案，用户在设置页粘贴，仅存本机（原 local.properties 编译期机制已废除）。
    fun amapKey(context: Context): String =
        prefs(context).getString("amap.key", "").orEmpty().trim()

    fun setAmapKey(context: Context, key: String) {
        prefs(context).edit().putString("amap.key", key.trim()).apply()
    }

    // ---- M1.6 R13 历史记录（仅本机 SharedPreferences，零云端零上报）----

    fun isHistoryOn(context: Context) = prefs(context).getBoolean("history.on", true)

    fun setHistoryOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("history.on", on).apply()
    }

    fun addHistory(context: Context, poi: SavedPoi) {
        if (!isHistoryOn(context)) return
        val items = readHistory(context).toMutableList()
        val destination = items.indexOfFirst { isSamePlace(it, poi) }
        if (destination >= 0) items.removeAt(destination)
        items.add(0, HistoryEntry(poi.name, poi.address, poi.latitude, poi.longitude, System.currentTimeMillis()))
        while (items.size > HISTORY_LIMIT) items.removeAt(items.size - 1)
        writeHistory(context, items)
    }

    fun recentHistory(context: Context, limit: Int = HISTORY_RECENT): List<HistoryEntry> =
        readHistory(context).take(limit)

    fun historyCount(context: Context): Int = readHistory(context).size

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }

    private fun isSamePlace(entry: HistoryEntry, poi: SavedPoi): Boolean =
        kotlin.math.abs(entry.latitude - poi.latitude) <= COORD_TOLERANCE &&
            kotlin.math.abs(entry.longitude - poi.longitude) <= COORD_TOLERANCE

    private fun readHistory(context: Context): List<HistoryEntry> {
        val raw = prefs(context).getString(KEY_HISTORY, "")
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val items = ArrayList<HistoryEntry>(array.length())
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val latitude = json.optString("lat").toDoubleOrNull() ?: continue
                val longitude = json.optString("lng").toDoubleOrNull() ?: continue
                items.add(
                    HistoryEntry(
                        name = json.optString("name"),
                        address = json.optString("address"),
                        latitude = latitude,
                        longitude = longitude,
                        time = json.optLong("time")
                    )
                )
            }
            items
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun writeHistory(context: Context, items: List<HistoryEntry>) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it.name, it.address, it.latitude, it.longitude, it.time)) }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    // ---- M1.6 R14 自定义位置（≤10 个，名称 ≤10 字由调用方校验）----

    fun places(context: Context): List<SavedPlace> {
        val raw = prefs(context).getString(KEY_PLACES, "")
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val items = ArrayList<SavedPlace>(array.length())
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val latitude = json.optString("lat").toDoubleOrNull() ?: continue
                val longitude = json.optString("lng").toDoubleOrNull() ?: continue
                items.add(
                    SavedPlace(
                        id = json.optString("id"),
                        name = json.optString("name"),
                        address = json.optString("address"),
                        latitude = latitude,
                        longitude = longitude
                    )
                )
            }
            items.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun addPlace(context: Context, name: String, poi: SavedPoi): SavedPlace? {
        val items = places(context).toMutableList()
        if (items.size >= PLACES_LIMIT) return null
        val place = SavedPlace(UUID.randomUUID().toString(), name, poi.address, poi.latitude, poi.longitude)
        items.add(place)
        writePlaces(context, items)
        return place
    }

    fun updatePlace(context: Context, id: String, name: String? = null, poi: SavedPoi? = null): Boolean {
        val items = places(context).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val current = items[index]
        items[index] = SavedPlace(
            id = current.id,
            name = name?.takeIf { it.isNotBlank() } ?: current.name,
            address = poi?.address ?: current.address,
            latitude = poi?.latitude ?: current.latitude,
            longitude = poi?.longitude ?: current.longitude
        )
        writePlaces(context, items)
        return true
    }

    fun deletePlace(context: Context, id: String) {
        writePlaces(context, places(context).filter { it.id != id })
    }

    fun findPlace(context: Context, id: String): SavedPlace? = places(context).firstOrNull { it.id == id }

    private fun writePlaces(context: Context, items: List<SavedPlace>) {
        val array = JSONArray()
        items.forEach {
            val json = toJson(it.name, it.address, it.latitude, it.longitude, 0L)
            json.put("id", it.id)
            array.put(json)
        }
        prefs(context).edit().putString(KEY_PLACES, array.toString()).apply()
    }

    private fun toJson(name: String, address: String, latitude: Double, longitude: Double, time: Long): JSONObject =
        JSONObject()
            .put("name", name)
            .put("address", address)
            .put("lat", latitude.toString())
            .put("lng", longitude.toString())
            .put("time", time)
}
