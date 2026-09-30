package com.xmmtr.navlite

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapUtils
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.services.core.AMapException

// M1.11 R20 首页交互定稿（七批拍板）：搜索框三态=未聚焦/聚焦/选中。
// 未聚焦态只有搜索框+小组件 chip 行+地图+右下定位钮与设置钮（无历史、无联想、无开始导航）；
// 聚焦态才出「最近去过」/联想；选中目的地（搜索点选或 chip 单击，默认拍板 18）才打点飞行并滑出
// 底部控制块（开始导航+偏好行）。核心原则：无目的地时页面上不存在任何"开始导航"入口。
// R21：相机锚点按"可见区域中心"补偿（顶部块/底部块高度实时实测）。
// M1.10 的地图首页与 M1.7 的定位三关门调用时序保持原样未动。
class MainActivity : Activity() {

    private var searcher: TipSearcher? = null
    private var tips: List<TipItem> = emptyList()
    private var history: List<HistoryEntry> = emptyList()
    private var historyMode = false
    private var picked: SavedPoi? = null
    private var ignoreTextChange = false
    private var pendingAction: (() -> Unit)? = null
    private var pendingFix: Pair<Double, Double>? = null
    private var locating = false
    private var locateClient: AMapLocationClient? = null
    private var locateTimeout: Runnable? = null
    private var pendingCruise = false
    private var tripStarted = false
    private var focusPinnedTop = false
    private var appliedLang: String? = null
    private var chipDistancePainted = false
    private val waypoints = ArrayList<SavedPoi>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mapView: MapView? = null
    private var aMap: AMap? = null
    private var lastLocation: Location? = null
    private var cameraLocateClient: AMapLocationClient? = null
    private var destinationMarker: Marker? = null

    private lateinit var rootFrame: FrameLayout
    private lateinit var keywordField: EditText
    private lateinit var tipAdapter: ArrayAdapter<String>
    private lateinit var sectionLabel: TextView
    private lateinit var placesScroll: HorizontalScrollView
    private lateinit var placesRow: LinearLayout
    private lateinit var tipsList: ListView
    private lateinit var searchBlock: LinearLayout
    private lateinit var topArea: LinearLayout
    private lateinit var bottomArea: LinearLayout
    private lateinit var controlBlock: LinearLayout
    private lateinit var startButton: Button
    private lateinit var prefRow: LinearLayout
    private lateinit var waypointScroll: HorizontalScrollView
    private lateinit var waypointRow: LinearLayout
    private lateinit var clearButton: ImageButton
    private lateinit var floatRow: LinearLayout
    private lateinit var locateBtn: ImageButton
    private lateinit var settingsBtn: ImageButton

    override fun attachBaseContext(base: Context) {
        // M1.14 B3：语言在 base 上生效（无 appcompat）
        super.attachBaseContext(base.localized())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootFrame = findViewById(R.id.main_root)

        val map = findViewById<MapView>(R.id.map_view)
        mapView = map
        map.onCreate(savedInstanceState)
        aMap = map.map
        setUpMap()

        searcher = TipSearcher(this)
        keywordField = findViewById(R.id.destination)
        sectionLabel = findViewById(R.id.section_label)
        placesScroll = findViewById(R.id.places_scroll)
        placesRow = findViewById(R.id.places_row)
        searchBlock = findViewById(R.id.search_block)
        topArea = findViewById(R.id.top_area)
        bottomArea = findViewById(R.id.bottom_area)
        controlBlock = findViewById(R.id.control_block)
        startButton = findViewById(R.id.start)
        prefRow = findViewById(R.id.pref_row)
        waypointScroll = findViewById(R.id.waypoint_scroll)
        waypointRow = findViewById(R.id.waypoint_row)
        clearButton = findViewById(R.id.clear_btn)
        floatRow = findViewById(R.id.float_row)
        locateBtn = findViewById(R.id.locate_btn)
        settingsBtn = findViewById(R.id.settings_btn)
        tipsList = findViewById(R.id.tips)
        tipAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        tipsList.adapter = tipAdapter
        tipsList.setOnItemClickListener { _, _, position, _ ->
            if (historyMode) chooseHistory(position) else chooseTip(position)
        }

        keywordField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                if (ignoreTextChange) return
                val keyword = text?.toString()?.trim().orEmpty()
                if (keyword.isEmpty()) {
                    // 清空即回到未聚焦/聚焦空态：目的地作废（默认拍板 18 的反向条件）
                    dropDestination()
                    if (keywordField.isFocused) showHistory() else collapseSuggestions()
                    return
                }
                // 手动改动已选目的地文字 → 所选目的地不再可信，作废待重选
                if (picked != null) dropDestination()
                searcher?.request(keyword, DEBOUNCE_MS) { result, code -> receiveTips(result, code) }
            }
        })

        // M1.12 C2（R23，v1.4.1 真机 bug）：列表**只由用户主动点搜索框触发**。
        // 根因（截图 7 实证）：导航结束返回时 MainActivity 被重建，输入框是窗口里第一个可聚焦视图，
        // 系统自动把焦点发给它（截图光标就在空框内）→ 旧的"聚焦即弹列表"把这次系统聚焦当成了用户点击，
        // 「最近去过」占住下半屏。故焦点变化只负责收起，弹列表的唯一入口=点击（用户手指动作）。
        keywordField.setOnFocusChangeListener { _, hasFocus ->
            // M1.14 A1/A2：聚焦期间整块贴顶（推荐列表不被键盘遮），失焦恢复用户档位；
            // 弹列表的唯一入口仍是"用户点击"（M1.12 C2 口径不变）。
            if (hasFocus) {
                pinSearchBlockToTop()
            } else {
                restoreSearchPosition()
                if (picked == null) collapseSuggestions()
            }
        }
        keywordField.setOnClickListener {
            pinSearchBlockToTop()
            showHistory()
        }

        clearButton.setOnClickListener {
            setFieldText("")
            dropDestination()
            collapseSuggestions()
            keywordField.requestFocus()
            showHistory()
        }
        startButton.setOnClickListener { startPicked() }
        settingsBtn.setOnClickListener { openSettings(null, null) }
        locateBtn.setOnClickListener { locateTapped() }

        paintPlaces()
        paintPrefRow()
        paintClearButton()
        // M1.12 C2 附带修复：Activity 被系统重建时恢复"选中态"（目的地文本+控制块+途经点），
        // 否则导航结束返回后目的地丢失只剩空框（截图 7 的另一个症状）
        restoreSelection(savedInstanceState)
        paintWaypoints()

        // R21：块高会随档别、目的地有无、键盘弹出（adjustResize 压缩根布局）而变 → 高度一变就重设锚点
        rootFrame.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applyVisibleAnchor()
        }
        // 未聚焦态=默认态：启动时不让输入框抢焦点
        keywordField.clearFocus()

        pendingCruise = intent.getBooleanExtra(EXTRA_START_CRUISE, false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_CRUISE, false)) pendingCruise = true
    }

    // ---- M1.10 B：首页地图（蓝点/昼夜/指南针/定位按钮）----

    private fun setUpMap() {
        val map = aMap ?: return
        map.uiSettings?.isZoomControlsEnabled = false
        map.setOnMyLocationChangeListener(object : AMap.OnMyLocationChangeListener {
            override fun onMyLocationChange(location: Location?) {
                lastLocation = location
                // M1.13 B1：首次拿到定位即重绘一次家/公司 chip（追加直线距离）
                if (location != null && !chipDistancePainted) {
                    chipDistancePainted = true
                    runOnUiThread { paintPlaces() }
                }
            }
        })
    }

    // 蓝点层需要定位权限；未授权时先不显示，三关门流程授权回来后 onResume 会再开。
    private fun enableMyLocationIfPermitted() {
        val map = aMap ?: return
        if (!hasLocationPermission()) return
        if (!map.isMyLocationEnabled) {
            val style = MyLocationStyle()
                .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
                .interval(1000L)
            map.setMyLocationStyle(style)
            map.isMyLocationEnabled = true
        }
    }

    // B5：主页地图与导航页同受昼夜设置影响（普通地图用 MAP_TYPE，导航页走 MapStyle）。
    private fun applyMainDayNight() {
        val map = aMap ?: return
        val night = when (LocalStore.dayNightMode(this)) {
            LocalStore.DAY_NIGHT_NIGHT -> true
            LocalStore.DAY_NIGHT_DAY -> false
            else -> isSystemNight()
        }
        map.setMapType(if (night) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL)
    }

    private fun isSystemNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // B2 搜索框位置两档：顶部（默认）/底部，onResume 即时重排（设置页改完回来即生效）。
    // M1.14 A1：聚焦期间 focusPinnedTop=true → 无论用户档位一律把整块（搜索框+chip 行+列表）搬到最顶，
    // 推荐列表就能在软键盘上方完整展开（adjustResize 下键盘只压地图不压块）；
    // A2：失焦/清除后恢复档位；A3：仍用 removeView/addView 整块搬移，不要求动画。
    private fun applySearchPosition() {
        val toBottom = LocalStore.searchPosition(this) == LocalStore.SEARCH_BOTTOM && !focusPinnedTop
        val parent = searchBlock.parent as? ViewGroup
        if (toBottom && parent !== bottomArea) {
            parent?.removeView(searchBlock)
            bottomArea.addView(searchBlock, 0)
        } else if (!toBottom && parent !== topArea) {
            parent?.removeView(searchBlock)
            topArea.addView(searchBlock)
        }
        // 定位按钮始终悬在浮动面板之上，面板高度变化（底部档/横滚显隐）后重新让位。
        rootFrame.post {
            floatRow.translationY = -(bottomArea.height + dp(10)).toFloat()
            applyVisibleAnchor()
        }
    }

    private fun pinSearchBlockToTop() {
        if (focusPinnedTop) return
        focusPinnedTop = true
        applySearchPosition()
    }

    private fun restoreSearchPosition() {
        if (!focusPinnedTop) return
        focusPinnedTop = false
        applySearchPosition()
    }

    // R21 定位居中补偿：把地图锚点设为"扣除顶部块与底部块后的可见区正中"，
    // 此后任何 moveCamera(newLatLng(点)) 都把该点落在可见中心（不再压在被底部块遮住的物理中心）。
    // 顶部/底部块高度一律实时取 view.height，四种组合的算式见 work\构建留痕-M1.11.txt 第五节。
    private fun applyVisibleAnchor() {
        val map = aMap ?: return
        val height = rootFrame.height
        if (height <= 0) return
        val centerY = visibleCenterY()
        map.setPointToCenter(rootFrame.width / 2, centerY)
    }

    private fun visibleCenterY(): Int {
        val height = rootFrame.height
        val top = visibleTopInset()
        val bottom = visibleBottomInset()
        val free = height - top - bottom
        if (free <= 0) return height / 2
        return (top + free / 2).coerceIn(dp(40), height - dp(40))
    }

    // 顶部块=搜索块在顶部档时的整块高度；底部档时顶部无遮挡。
    private fun visibleTopInset(): Int = if (searchBlock.parent === topArea) topArea.height else 0

    // 底部块=底部区整块高度（含控制块；底部档时还含搜索块）。右下两个 40dp 小钮贴在右侧不遮中心，不计入。
    private fun visibleBottomInset(): Int = bottomArea.height

    // 锚点变了以后，把当前关注点（已选目的地，否则用户位置）重新落到可见中心。
    private fun repinAfterAnchorChange() {
        rootFrame.post {
            applyVisibleAnchor()
            val poi = picked
            val latitude = poi?.latitude ?: lastLocation?.latitude ?: return@post
            val longitude = poi?.longitude ?: lastLocation?.longitude ?: return@post
            aMap?.moveCamera(CameraUpdateFactory.newLatLng(LatLng(latitude, longitude)))
        }
    }

    // B3 定位按钮：回当前位置并把蓝点切回"定位+朝向跟随"。
    private fun locateTapped() {
        val map = aMap ?: return
        applyVisibleAnchor()
        val loc = lastLocation ?: map.myLocation
        if (loc != null) {
            pinCameraTo(map, loc.latitude, loc.longitude)
            return
        }
        // 还没拿到过位置：单次定位一次再回（独立小助手，不触碰三关门链路）。
        if (cameraLocateClient != null) return
        toast(getString(R.string.tip_locating))
        val client = AMapLocationClient(applicationContext)
        cameraLocateClient = client
        val option = AMapLocationClientOption().apply {
            isOnceLocation = true
            isOnceLocationLatest = true
            isNeedAddress = false
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        }
        client.setLocationOption(option)
        val timeout = Runnable {
            cameraLocateClient?.let {
                it.stopLocation()
                it.onDestroy()
            }
            cameraLocateClient = null
        }
        mainHandler.postDelayed(timeout, CAMERA_LOCATE_TIMEOUT_MS)
        client.setLocationListener { result ->
            mainHandler.post {
                mainHandler.removeCallbacks(timeout)
                cameraLocateClient?.let {
                    it.stopLocation()
                    it.onDestroy()
                }
                cameraLocateClient = null
                if (result.errorCode == AMAP_LOCATE_SUCCESS) {
                    lastLocation = Location("navlite").apply {
                        latitude = result.latitude
                        longitude = result.longitude
                    }
                    aMap?.let { pinCameraTo(it, result.latitude, result.longitude) }
                }
            }
        }
        client.startLocation()
    }

    private fun pinCameraTo(map: AMap, latitude: Double, longitude: Double) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), HOME_ZOOM))
        // 视图级 setMyLocationType 已 @Deprecated：用样式重设达到"切回定位+朝向跟随"（javap 确认 setMyLocationStyle 未弃用）。
        map.setMyLocationStyle(rotateFollowStyle())
    }

    private fun rotateFollowStyle(): MyLocationStyle =
        MyLocationStyle()
            .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
            .interval(1000L)

    // M1.10 C11 手动巡航：入口按默认拍板 19 收进设置页，设置页只负责把"要巡航"带回主页，
    // 真正发起仍走本方法→三关门→NaviActivity 巡航模式（链路一行未改）。
    private fun startCruise() {
        if (!requireKey()) return
        withLocation { launchCruise() }
    }

    private fun launchCruise() {
        val fix = pendingFix
        pendingFix = null
        tripStarted = true
        val intent = Intent(this, NaviActivity::class.java)
            .putExtra(NaviActivity.EXTRA_AIMLESS, true)
        if (fix != null) {
            intent.putExtra(NaviActivity.EXTRA_START_LATITUDE, fix.first)
                .putExtra(NaviActivity.EXTRA_START_LONGITUDE, fix.second)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // M1.14 B3：设置页可能刚切过语言 → 主页重建一次让新语言生效（重建后 appliedLang 与存储一致，不再循环）
        val lang = LocalStore.appLang(this)
        if (appliedLang != null && appliedLang != lang) {
            appliedLang = lang
            recreate()
            return
        }
        appliedLang = lang
        mapView?.onResume()
        applyMainDayNight()
        applySearchPosition()
        enableMyLocationIfPermitted()
        aMap?.uiSettings?.isCompassEnabled = LocalStore.isCompassOn(this)
        // 设置页可能改过位置/历史/偏好，回来即重画。
        paintPlaces()
        paintPrefRow()
        paintClearButton()
        // M1.12 C2（R23）：从导航页/设置页/巡航返回主页一律收起列表——目的地文本与控制块（选中态）保留，
        // 只有用户再点搜索框才出列表。
        collapseSuggestions()
        // M1.12 B4：途经点仅本次会话有效——只在"跑完一趟行程回来"时清空（刚被系统重建并恢复选中态时不清）
        if (tripStarted) {
            tripStarted = false
            if (waypoints.isNotEmpty()) waypoints.clear()
        }
        paintWaypoints()
        if (pendingCruise) {
            pendingCruise = false
            // post 到 onResume（含三关门续跑）之后再发起，避免同帧两次进入定位流程
            rootFrame.post { startCruise() }
        }
        // 从系统"位置信息"设置页返回后自动续跑（开启定位后回来即能规划路线）。
        if (pendingAction == null || !hasLocationPermission()) return
        if (LocationGate.isServiceOn(this)) locateAndRun()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
        saveSelection(outState)
    }

    override fun onDestroy() {
        searcher?.cancel()
        searcher = null
        locateTimeout?.let { mainHandler.removeCallbacks(it) }
        locateTimeout = null
        locateClient?.let {
            it.stopLocation()
            it.onDestroy()
        }
        locateClient = null
        cameraLocateClient?.let {
            it.stopLocation()
            it.onDestroy()
        }
        cameraLocateClient = null
        destinationMarker = null
        mapView?.onDestroy()
        mapView = null
        aMap = null
        super.onDestroy()
    }

    // ---- R20 三态 ----

    private fun receiveTips(result: List<TipItem>, code: Int) {
        if (result.isEmpty()) {
            showHistory()
            if (code != AMapException.CODE_AMAP_SUCCESS) toast(failMessage(code))
            return
        }
        historyMode = false
        sectionLabel.visibility = View.GONE
        tips = result
        takeTips(result.map { PoiPicker.display(it) })
    }

    // M1.6 B2：聚焦且联想为空时显示「最近去过」前 8 条（数据与开关逻辑未动）
    private fun showHistory() {
        tips = emptyList()
        if (!LocalStore.isHistoryOn(this)) {
            history = emptyList()
            historyMode = false
            sectionLabel.visibility = View.GONE
            takeTips(emptyList())
            return
        }
        history = LocalStore.recentHistory(this)
        if (history.isEmpty()) {
            historyMode = false
            sectionLabel.visibility = View.GONE
            takeTips(emptyList())
            return
        }
        historyMode = true
        sectionLabel.visibility = View.VISIBLE
        takeTips(history.map { entry ->
            if (entry.address.isBlank()) entry.name else "${entry.name}  ${entry.address}"
        })
    }

    private fun collapseSuggestions() {
        tips = emptyList()
        history = emptyList()
        historyMode = false
        sectionLabel.visibility = View.GONE
        takeTips(emptyList())
    }

    private fun chooseHistory(position: Int) {
        val entry = history.getOrNull(position) ?: return
        enterSelected(SavedPoi(entry.name, entry.address, entry.latitude, entry.longitude))
    }

    private fun chooseTip(position: Int) {
        val item = tips.getOrNull(position) ?: return
        enterSelected(PoiPicker.toPoi(item))
    }

    // 选中态：填入搜索框 + 收起列表 + 地图飞过去打点 + 底部滑出「开始导航」（R20③）
    private fun enterSelected(poi: SavedPoi) {
        picked = poi
        setFieldText(poi.name)
        collapseSuggestions()
        keywordField.clearFocus()
        showMarker(poi)
        val map = aMap
        if (map != null) {
            applyVisibleAnchor()
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(poi.latitude, poi.longitude), HOME_ZOOM)
            )
        }
        paintControlBlock()
        paintPlaces()
    }

    // 回到无目的地态：目的地作废、marker 撤掉、控制块收回（0dp 不占位）
    private fun dropDestination() {
        if (picked == null && destinationMarker == null && controlBlock.visibility == View.GONE) return
        picked = null
        removeMarker()
        paintControlBlock()
        paintPlaces()
    }

    private fun showMarker(poi: SavedPoi) {
        val map = aMap ?: return
        destinationMarker?.destroy()
        destinationMarker = map.addMarker(
            MarkerOptions().position(LatLng(poi.latitude, poi.longitude)).title(poi.name)
        )
    }

    private fun removeMarker() {
        destinationMarker?.destroy()
        destinationMarker = null
    }

    private fun paintControlBlock() {
        val poi = picked
        if (poi == null) {
            controlBlock.visibility = View.GONE
        } else {
            controlBlock.visibility = View.VISIBLE
            startButton.text = getString(R.string.start_navi_to, poi.name)
            controlBlock.alpha = 0f
            controlBlock.animate().alpha(1f).setDuration(160L).start()
        }
        paintClearButton()
        paintWaypoints()
        repinAfterAnchorChange()
    }

    private fun paintClearButton() {
        val hasContent = keywordField.text?.isNotEmpty() == true || picked != null
        clearButton.visibility = if (hasContent) View.VISIBLE else View.GONE
    }

    private fun setFieldText(text: String) {
        ignoreTextChange = true
        keywordField.setText(text)
        ignoreTextChange = false
    }

    private fun failMessage(code: Int): String =
        if (!AmapKeyState.hasKey) getString(R.string.tip_no_key)
        else getString(R.string.tip_search_failed, code)

    private fun takeTips(lines: List<String>) {
        tipAdapter.clear()
        tipAdapter.addAll(lines)
        // 地图首页：列表空时整块收起，把地图还给手势。
        tipsList.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
    }

    // 唯一导航入口：必须先有已选目的地（核心原则：无目的地时不存在开始导航）
    private fun startPicked() {
        if (!requireKey()) return
        val poi = picked
        if (poi == null) {
            toast(getString(R.string.tip_input_keyword))
            return
        }
        withLocation { launchNavi(poi) }
    }

    // M1.11 R20②：家/公司/自定义位置统一收成搜索框下方一行小号 chip；
    // 单击=选中目的地（默认拍板 18，不再直接发起导航）；长按=跳设置页对应项编辑。
    private fun paintPlaces() {
        placesRow.removeAllViews()
        addSlotChip(LocalStore.SLOT_HOME, getString(R.string.shortcut_home))
        addSlotChip(LocalStore.SLOT_COMPANY, getString(R.string.shortcut_company))
        LocalStore.places(this).forEach { place ->
            val poi = SavedPoi(place.name, place.address, place.latitude, place.longitude)
            addChip(place.name, isCurrent(poi), onTap = { enterSelected(poi) }, onLong = {
                openSettings(place.id, null)
            })
        }
        paintClearButton()
    }

    // M1.13 B1（默认拍板 21，对标高德首页「回家 59.9公里」）：家/公司 chip 追加直线距离；
    // 未设置该项、或还没有定位时不显示距离；自定义位置 chip 不加（点多冗余）。
    private fun addSlotChip(slot: String, label: String) {
        val poi = LocalStore.savedPoi(this, slot)
        val distance = distanceSuffix(poi)
        val chipLabel = if (distance == null) label else "$label $distance"
        addChip(chipLabel, poi != null && isCurrent(poi), onTap = {
            if (poi == null) {
                // 未设置时单击=引导设置（现逻辑保留：先给提示文案）
                toast(getString(slotHint(slot)))
            } else {
                enterSelected(poi)
            }
        }, onLong = {
            openSettings(null, slot)
        })
    }

    private fun addChip(label: String, selected: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
        val horizontal = dp(12)
        val chip = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(if (selected) Color.WHITE else getColor(R.color.accent))
            setBackgroundResource(if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip)
            setPadding(horizontal, 0, horizontal, 0)
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onTap() }
            setOnLongClickListener {
                onLong()
                true
            }
        }
        placesRow.addView(chip)
    }

    // M1.12 B1/B2：途经点行（仅选中态出现）——「＋ 途经点」复用主页面同款搜索选点弹窗（PoiPicker），
    // 每个途经点渲染成「名称 ×」，点一下即删除；B4：只活在本次会话（内存+实例状态），
    // 不进 SharedPreferences、不写历史，返回主页即清空。
    private fun paintWaypoints() {
        waypointRow.removeAllViews()
        if (picked == null) {
            waypointScroll.visibility = View.GONE
            return
        }
        waypointScroll.visibility = View.VISIBLE
        addWaypointChip(getString(R.string.waypoint_add), highlighted = false) {
            if (waypoints.size >= WAYPOINT_LIMIT) {
                toast(getString(R.string.waypoint_limit))
                return@addWaypointChip
            }
            if (!requireKey()) return@addWaypointChip
            PoiPicker.show(this, getString(R.string.waypoint_pick_title)) { poi ->
                waypoints.add(poi)
                paintWaypoints()
                toast(getString(R.string.waypoint_added, poi.name))
            }
        }
        waypoints.forEachIndexed { index, poi ->
            addWaypointChip(getString(R.string.waypoint_remove, poi.name), highlighted = true) {
                if (index >= waypoints.size) return@addWaypointChip
                val removed = waypoints.removeAt(index)
                paintWaypoints()
                toast(getString(R.string.waypoint_removed, removed.name))
            }
        }
    }

    private fun addWaypointChip(label: String, highlighted: Boolean, onTap: () -> Unit) {
        val horizontal = dp(10)
        val chip = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(if (highlighted) Color.WHITE else getColor(R.color.accent))
            setBackgroundResource(if (highlighted) R.drawable.bg_chip_on else R.drawable.bg_chip)
            setPadding(horizontal, 0, horizontal, 0)
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onTap() }
        }
        waypointRow.addView(chip)
    }

    private fun saveSelection(outState: Bundle) {
        val chosen = picked
        if (chosen != null) {
            outState.putString(STATE_PICKED_NAME, chosen.name)
            outState.putString(STATE_PICKED_ADDRESS, chosen.address)
            outState.putDouble(STATE_PICKED_LATITUDE, chosen.latitude)
            outState.putDouble(STATE_PICKED_LONGITUDE, chosen.longitude)
        }
        if (waypoints.isNotEmpty()) {
            outState.putDoubleArray(STATE_WAYPOINT_LATITUDES, waypoints.map { it.latitude }.toDoubleArray())
            outState.putDoubleArray(STATE_WAYPOINT_LONGITUDES, waypoints.map { it.longitude }.toDoubleArray())
            outState.putStringArray(STATE_WAYPOINT_NAMES, waypoints.map { it.name }.toTypedArray())
        }
    }

    private fun restoreSelection(state: Bundle?) {
        if (state == null) return
        val name = state.getString(STATE_PICKED_NAME)
        if (!name.isNullOrEmpty()) {
            val poi = SavedPoi(
                name = name,
                address = state.getString(STATE_PICKED_ADDRESS).orEmpty(),
                latitude = state.getDouble(STATE_PICKED_LATITUDE),
                longitude = state.getDouble(STATE_PICKED_LONGITUDE)
            )
            picked = poi
            setFieldText(name)
            showMarker(poi)
            paintControlBlock()
        }
        val latitudes = state.getDoubleArray(STATE_WAYPOINT_LATITUDES) ?: doubleArrayOf()
        val longitudes = state.getDoubleArray(STATE_WAYPOINT_LONGITUDES) ?: doubleArrayOf()
        val names = state.getStringArray(STATE_WAYPOINT_NAMES) ?: emptyArray()
        latitudes.indices.forEach { index ->
            val latitude = latitudes[index]
            val longitude = longitudes.getOrElse(index) { return@forEach }
            waypoints.add(SavedPoi(names.getOrElse(index) { "" }, "", latitude, longitude))
        }
    }

    // 直线距离（AMapUtils.calculateLineDistance 返回米）：四舍五入后 <1000 米显示「N 米」，
    // 否则显示「N.N 公里」（一位小数）；拿不到定位或结果非法则返回 null 不显示。
    // 注意：直线距离≠驾车路程，属参考值（对标清单 B4 已登记该口径）。
    private fun distanceSuffix(poi: SavedPoi?): String? {
        if (poi == null) return null
        val location = lastLocation ?: aMap?.myLocation ?: return null
        val meters = AMapUtils.calculateLineDistance(
            LatLng(location.latitude, location.longitude),
            LatLng(poi.latitude, poi.longitude)
        )
        if (meters.isNaN() || meters < 0f) return null
        val rounded = Math.round(meters)
        return if (rounded < 1000) {
            getString(R.string.chip_distance_meters, rounded)
        } else {
            getString(R.string.chip_distance_km, rounded / 1000.0)
        }
    }

    private fun isCurrent(poi: SavedPoi): Boolean {
        val current = picked ?: return false
        return current.latitude == poi.latitude && current.longitude == poi.longitude
    }

    // 偏好快捷行（与预览态同款五档）：仅目的地存在时随控制块出现；
    // 本机存好后由「开始导航」→预览态首次算路采用（预览态内切换即重算=现逻辑未动）。
    private fun paintPrefRow() {
        prefRow.removeAllViews()
        val names = resources.getStringArray(R.array.route_strategy_names)
        val current = LocalStore.routeStrategy(this)
        names.forEachIndexed { index, name ->
            val horizontal = dp(10)
            val chip = Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 13f
                setTextColor(if (index == current) Color.WHITE else getColor(R.color.accent))
                setBackgroundResource(if (index == current) R.drawable.bg_chip_on else R.drawable.bg_chip)
                setPadding(horizontal, 0, horizontal, 0)
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    if (LocalStore.routeStrategy(this@MainActivity) == index) return@setOnClickListener
                    LocalStore.setRouteStrategy(this@MainActivity, index)
                    paintPrefRow()
                }
            }
            prefRow.addView(chip)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openSettings(editPlaceId: String?, editSlot: String?) {
        val intent = Intent(this, SettingsActivity::class.java)
        if (editPlaceId != null) intent.putExtra(SettingsActivity.EXTRA_EDIT_PLACE, editPlaceId)
        if (editSlot != null) intent.putExtra(SettingsActivity.EXTRA_EDIT_SLOT, editSlot)
        startActivity(intent)
    }

    private fun requireKey(): Boolean {
        if (AmapKeyState.hasKey) return true
        toast(getString(R.string.tip_no_key))
        openSettings(null, null)
        return false
    }

    // M1.7 导航前置链路（真机反馈）：权限 → 系统定位服务 → 真实拿到一次定位，三关全过才允许算路。
    private fun withLocation(action: () -> Unit) {
        pendingAction = action
        when {
            !hasLocationPermission() -> requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION
            )
            !LocationGate.isServiceOn(this) -> showLocationServiceDialog()
            else -> locateAndRun()
        }
    }

    // 安卓 12+ 权限分"精确位置/大致范围"：两项任一授予即可用（只给大致时精度略降但导航可用）。
    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun showLocationServiceDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.location_service_title)
            .setMessage(R.string.location_service_msg)
            .setPositiveButton(R.string.location_go_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {
                    // 个别 ROM 没有该设置页时兜底提示手动找开关
                }
                toast(getString(R.string.location_after_settings))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun locateAndRun() {
        if (locating) return
        val action = pendingAction ?: return
        locating = true
        toast(getString(R.string.tip_locating))

        val timeout = Runnable {
            if (!locating) return@Runnable
            locating = false
            locateClient?.let {
                it.stopLocation()
                it.onDestroy()
            }
            locateClient = null
            // M1.14 C2（默认拍板 22）：高精度定位超时 → 先降级一次纯网络定位，仍拿不到才提示
            if (!startNetworkLocationFallback()) toast(getString(R.string.tip_locate_timeout))
        }
        locateTimeout = timeout
        mainHandler.postDelayed(timeout, LOCATE_TIMEOUT_MS)

        val client = AMapLocationClient(applicationContext)
        locateClient = client
        val option = AMapLocationClientOption().apply {
            isOnceLocation = true
            isOnceLocationLatest = true
            isNeedAddress = false
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        }
        client.setLocationOption(option)
        client.setLocationListener { result ->
            mainHandler.post {
                if (!locating) return@post
                locating = false
                mainHandler.removeCallbacks(timeout)
                locateClient?.let {
                    it.stopLocation()
                    it.onDestroy()
                }
                locateClient = null
                if (result.errorCode == AMAP_LOCATE_SUCCESS) {
                    // 拿到真实位置才放行导航，并把该点显式作为算路起点（不再依赖"空起点=我的位置"）
                    pendingFix = result.latitude to result.longitude
                    pendingAction = null
                    action()
                } else if (result.errorCode == AMAP_LOCATE_SERVICE_OFF_1 ||
                    result.errorCode == AMAP_LOCATE_SERVICE_OFF_2
                ) {
                    showLocationServiceDialog()
                } else if (!startNetworkLocationFallback()) {
                    // M1.14 C2：高精度定位失败先降级网络定位，两级都不成才报这条
                    toast(getString(R.string.tip_locate_failed, result.errorCode))
                }
            }
        }
        client.startLocation()
    }

    // M1.14 C2（默认拍板 22）：室内/弱星况下高精度单次定位失败或 12 秒超时后，追加一次
    // Battery_Saving（纯网络+基站定位，室内 WiFi 成功率高）重试。
    // 铁律：三关门时序（权限→系统服务→定位）一行未动——本函数只从"高精度定位失败/超时"分支内部调用，
    // 首次定位成功的路径完全不经过这里；降级成功与首次成功走完全相同的后续路径（pendingFix + action()）。
    private fun startNetworkLocationFallback(): Boolean {
        val action = pendingAction ?: return false
        locating = true
        toast(getString(R.string.tip_locate_network_fallback))
        val client = AMapLocationClient(applicationContext)
        locateClient = client
        val option = AMapLocationClientOption().apply {
            isOnceLocation = true
            isOnceLocationLatest = true
            isNeedAddress = false
            locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
        }
        client.setLocationOption(option)
        val timeout = Runnable {
            if (!locating) return@Runnable
            locating = false
            locateClient?.let {
                it.stopLocation()
                it.onDestroy()
            }
            locateClient = null
            toast(getString(R.string.tip_locate_timeout))
        }
        locateTimeout = timeout
        mainHandler.postDelayed(timeout, FALLBACK_LOCATE_TIMEOUT_MS)
        client.setLocationListener { result ->
            mainHandler.post {
                if (!locating) return@post
                locating = false
                mainHandler.removeCallbacks(timeout)
                locateClient?.let {
                    it.stopLocation()
                    it.onDestroy()
                }
                locateClient = null
                if (result.errorCode == AMAP_LOCATE_SUCCESS && result.latitude != 0.0) {
                    // 与首次成功同一条后续：显式起点 + 放行原动作
                    pendingFix = result.latitude to result.longitude
                    pendingAction = null
                    action()
                } else {
                    toast(getString(R.string.tip_locate_failed_indoor, result.errorCode))
                }
            }
        }
        client.startLocation()
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            if (LocationGate.isServiceOn(this)) locateAndRun() else showLocationServiceDialog()
        } else {
            pendingAction = null
            toast(getString(R.string.tip_need_location))
        }
    }

    private fun launchNavi(poi: SavedPoi) {
        val fix = pendingFix
        pendingFix = null
        tripStarted = true
        val intent = Intent(this, NaviActivity::class.java)
            .putExtra(NaviActivity.EXTRA_LATITUDE, poi.latitude)
            .putExtra(NaviActivity.EXTRA_LONGITUDE, poi.longitude)
            .putExtra(NaviActivity.EXTRA_NAME, poi.name)
            .putExtra(NaviActivity.EXTRA_ADDRESS, poi.address)
        if (fix != null) {
            intent.putExtra(NaviActivity.EXTRA_START_LATITUDE, fix.first)
                .putExtra(NaviActivity.EXTRA_START_LONGITUDE, fix.second)
        }
        // M1.12 B5：途经点交导航页参与算路（拆数组 extra 实现从简，不引入 Parcelable）
        if (waypoints.isNotEmpty()) {
            intent.putExtra(NaviActivity.EXTRA_WAYPOINT_LATITUDES, waypoints.map { it.latitude }.toDoubleArray())
                .putExtra(NaviActivity.EXTRA_WAYPOINT_LONGITUDES, waypoints.map { it.longitude }.toDoubleArray())
                .putExtra(NaviActivity.EXTRA_WAYPOINT_NAMES, waypoints.map { it.name }.toTypedArray())
        }
        startActivity(intent)
    }

    private fun slotHint(slot: String) =
        if (slot == LocalStore.SLOT_HOME) R.string.tip_no_home else R.string.tip_no_company

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
        private const val REQUEST_LOCATION = 1001
        private const val LOCATE_TIMEOUT_MS = 12000L
        // M1.14 C2：降级为纯网络定位的那一次重试（卡面口径约 3~5 秒，取 5 秒上限）
        private const val FALLBACK_LOCATE_TIMEOUT_MS = 5000L
        private const val CAMERA_LOCATE_TIMEOUT_MS = 10000L
        private const val AMAP_LOCATE_SUCCESS = 0

        // 巡航入口收进设置页（默认拍板 19）：设置页只回传该标记，仍由主页走三关门
        const val EXTRA_START_CRUISE = "start_cruise"
        // 高德定位错误码：2=服务未启动（网络/基站均不可用）、10=定位服务没有开启
        private const val AMAP_LOCATE_SERVICE_OFF_1 = 2
        private const val AMAP_LOCATE_SERVICE_OFF_2 = 10
        private const val HOME_ZOOM = 16f

        // M1.12 B2：途经点上限（默认拍板 20）
        private const val WAYPOINT_LIMIT = 5
        // M1.12 C2：Activity 重建时恢复选中态（仅内存级实例状态，不写 SharedPreferences）
        private const val STATE_PICKED_NAME = "picked.name"
        private const val STATE_PICKED_ADDRESS = "picked.address"
        private const val STATE_PICKED_LATITUDE = "picked.lat"
        private const val STATE_PICKED_LONGITUDE = "picked.lng"
        private const val STATE_WAYPOINT_LATITUDES = "waypoints.lat"
        private const val STATE_WAYPOINT_LONGITUDES = "waypoints.lng"
        private const val STATE_WAYPOINT_NAMES = "waypoints.name"
    }
}
