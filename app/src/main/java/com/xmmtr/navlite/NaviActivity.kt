package com.xmmtr.navlite

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapException
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.TTSPlayListener
import com.amap.api.navi.enums.AimLessMode
import com.amap.api.navi.enums.AMapNaviViewShowMode
import com.amap.api.navi.enums.BroadcastMode
import com.amap.api.navi.enums.MapStyle
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.navi.view.RouteOverLay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// M1.8（R15）两态：进入=预览态（算路成功只全览确认，不 startNavi、不播报）；
// 点「开始导航」大按钮才 startNavi 进导航态（偏航重算不回预览态）。
// M1.9（R16/R18）：昼夜/路况应用 + 预览态多路线切换与算路偏好五档。
// M1.10（R19/R16）：A1=所有算路调用共用同一 resolvedStart（首次解析后固定，重算绝不换起点，
//   无显式起点时自行单次定位，定位失败即中止——绝不发起 SDK 自选起点的算路，封死"默认位置→跨城返程样路线"）；
//   A2=地图载入即把相机钉到用户位置，预览取景走路线 bounds 显式 moveCamera（不再依赖 SDK 全览自动取景），
//   算路失败/无路线时相机钉回用户位置；C=§2.8 本期档十二项设置应用 + 手动巡航（startAimlessMode）。
class NaviActivity : Activity() {

    private var naviView: AMapNaviView? = null
    private var navi: AMapNavi? = null
    private var naviStarted = false
    private var aimless = false
    private var resolvedStart: NaviLatLng? = null
    private val wayPoints = ArrayList<NaviLatLng>()
    private var waypointNames: List<String> = emptyList()
    private val routeOverlays = ArrayList<RouteOverLay>()
    // M1.13 A1：换返程=内存里对调起终点对，再走同一算路入口。
    // endPoint 非空即"当前终点已被换向改写"（返程时=原定位点），为空则沿用 Intent 传入的目的地。
    private var endPoint: NaviLatLng? = null
    private var reversed = false
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
    private var lastText = ""
    private var lastTextAt = 0L
    private var routeIds: List<Int> = emptyList()
    private var routeIndex = 0
    private var locating = false
    private var locateClient: AMapLocationClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var previewBar: LinearLayout
    private lateinit var previewStart: Button
    private lateinit var routePrev: Button
    private lateinit var routeNext: Button
    private lateinit var routeIndicator: TextView
    private lateinit var routePref: Button
    private lateinit var routeReverse: Button
    private lateinit var routeTag: TextView
    private lateinit var startLabel: TextView
    private lateinit var endLabel: TextView
    private lateinit var viaRow: LinearLayout
    private lateinit var viaLabel: TextView

    private val announcer by lazy { (application as NavLiteApp).announcer }

    private val ttsPlayListener = object : TTSPlayListener {
        override fun onPlayStart(text: String?) {
            text?.let { speakOnce(it) }
        }

        override fun onPlayEnd(text: String?) = Unit
    }

    private val naviListener = object : SimpleNaviListener() {
        override fun onGetNavigationText(type: Int, text: String?) {
            text?.let { speakOnce(it) }
        }

        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
            // naviStarted=true 说明是导航中的偏航/手动重算：不回预览态，交回 SDK 继续引导。
            if (!naviStarted) showPreview()
        }

        override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
            // M1.10 A2：算路失败相机钉回用户位置，绝不留在 SDK 默认北京。
            pinCameraToStart()
            val code = result?.errorCode ?: -1
            val detail = result?.errorDescription.orEmpty()
            if (detail.isBlank()) {
                toast(getString(R.string.navi_failed, code))
            } else {
                toast("$detail（错误码 $code）")
            }
        }

        override fun onReCalculateRouteForYaw() {
            toast(getString(R.string.navi_yaw))
        }

        override fun onArriveDestination() {
            toast(getString(R.string.navi_arrived))
        }
    }

    private val viewListener = object : AMapNaviViewListener {
        override fun onNaviSetting() = Unit
        override fun onNaviMapMode(mode: Int) = Unit

        // 高德接口仍要求实现这三个回调，但官方已标记过时；空实现即可。
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onNaviTurnClick() = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onNextRoadClick() = Unit

        override fun onScanViewButtonClick() = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLockMap(locked: Boolean) = Unit

        override fun onNaviViewLoaded() = Unit
        override fun onMapTypeChanged(type: Int) = Unit
        override fun onNaviViewShowMode(mode: Int) = Unit
        override fun onNaviCancel() {
            exitNavi()
        }

        override fun onNaviBackClick(): Boolean {
            exitNavi()
            return true
        }
    }

    override fun attachBaseContext(base: Context) {
        // M1.14 B3：语言在 base 上生效（无 appcompat）
        super.attachBaseContext(base.localized())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navi)

        aimless = intent.getBooleanExtra(EXTRA_AIMLESS, false)
        readWayPoints()

        previewBar = findViewById(R.id.preview_bar)
        previewStart = findViewById(R.id.preview_start)
        previewStart.isEnabled = false
        previewStart.setOnClickListener { beginNavigation() }
        findViewById<TextView>(R.id.preview_title).text = destinationLabel()
        findViewById<TextView>(R.id.preview_address).text = destinationAddress()
        findViewById<TextView>(R.id.preview_summary).text = getString(R.string.navi_preview_time_pending)

        routePrev = findViewById(R.id.route_prev)
        routeNext = findViewById(R.id.route_next)
        routeIndicator = findViewById(R.id.route_indicator)
        routePref = findViewById(R.id.route_pref)
        routeReverse = findViewById(R.id.route_reverse)
        routeTag = findViewById(R.id.route_tag)
        startLabel = findViewById(R.id.preview_start_label)
        endLabel = findViewById(R.id.preview_end_label)
        viaRow = findViewById(R.id.preview_via_row)
        viaLabel = findViewById(R.id.preview_via_label)
        routePrev.setOnClickListener { switchRoute(-1) }
        routeNext.setOnClickListener { switchRoute(1) }
        routePref.text = getString(R.string.route_pref_label, strategyName(LocalStore.routeStrategy(this)))
        routePref.setOnClickListener { showStrategyPicker() }
        // M1.13 A1：一键对调起终点（对标高德「⇅」），再点换回
        routeReverse.setOnClickListener { toggleRouteDirection() }

        if (aimless) {
            // 巡航模式：无目的地无预览，覆盖层整层隐藏，也不记历史。
            previewBar.visibility = View.GONE
            previewStart.visibility = View.GONE
        }

        val view = findViewById<AMapNaviView>(R.id.navi_view)
        naviView = view
        view.onCreate(savedInstanceState)
        view.setAMapNaviViewListener(viewListener)
        // M1.10 A2：地图就绪第一时间把相机钉到用户位置（此后各分支不再出现默认北京）。
        view.setOnMapLoadedListener(object : AMap.OnMapLoadedListener {
            override fun onMapLoaded() {
                pinCameraToStart()
            }
        })
        applyNaviViewPrefs()

        if (!AmapKeyState.hasKey) {
            toast(getString(R.string.tip_no_key))
        }
        // M1.14 C1（R25③）：引擎初始化之前先做网络预检。截图 8 的「导航初始化失败，请检查网络后重试」
        // 是 SDK 引擎初始化联网失败弹的**原生**弹窗（全工程搜不到这条字符串，App 自己从没弹过），
        // 断网时用户只会先看到它。预检通过才走原流程；无网先弹自家中文框（重试=重查网络后继续，返回=退出本页）。
        if (isNetworkOnline()) {
            initEngineAndStart()
        } else {
            showOfflineDialog()
        }
    }

    private fun initEngineAndStart() {
        val instance = try {
            AMapNavi.getInstance(this)
        } catch (e: AMapException) {
            toast(getString(R.string.tip_no_key))
            null
        }
        if (instance == null) {
            finish()
            return
        }
        navi = instance
        instance.addAMapNaviListener(naviListener)
        instance.addTTSPlayListener(ttsPlayListener)
        // 方案B（本期）：两个参数分别是「用内置语音」「用 SDK 托管的系统 TTS」，都关掉 → 播报文本交给我们自己的 TtsAnnouncer。
        // 方案A（M3 拿到高德语音授权后切换）：instance.setUseInnerVoice(true, false) 并摘掉 ttsPlayListener 与 announcer 接线。
        instance.setUseInnerVoice(false, false)
        applyBroadcastMode()

        if (aimless) {
            // M1.10 C11 手动巡航：无目的巡航（电子眼+特殊路段通报），退出按钮走 exitNavi 停巡航。
            instance.startAimlessMode(AimLessMode.CAMERA_AND_SPECIALROAD_DETECTED)
            toast(getString(R.string.cruise_started))
        } else {
            recordHistory()
            calculateRoute()
        }
    }

    // 同步快查当前网络（ConnectivityManager + NET_CAPABILITY_VALIDATED）：不给有网路径增加可感延迟
    private fun isNetworkOnline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun showOfflineDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.net_offline_title)
            .setMessage(R.string.net_offline_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.net_offline_retry) { _, _ ->
                if (isNetworkOnline()) {
                    initEngineAndStart()
                } else {
                    showOfflineDialog()
                }
            }
            .setNegativeButton(R.string.net_offline_exit) { _, _ -> finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        naviView?.onResume()
        // 通用设置每次进入/回到前台时应用（"自动"档要跟住系统深色模式的变化）。
        applyNaviViewPrefs()
        applyBroadcastMode()
    }

    override fun onPause() {
        super.onPause()
        naviView?.onPause()
    }

    override fun onDestroy() {
        val instance = navi
        if (instance != null) {
            instance.removeAMapNaviListener(naviListener)
            instance.removeTTSPlayListener(ttsPlayListener)
            if (aimless) instance.stopAimlessMode()
        }
        locateClient?.let {
            it.stopLocation()
            it.onDestroy()
        }
        locateClient = null
        clearPreviewRoutes()
        naviView?.onDestroy()
        naviView = null
        navi = null
        AMapNavi.destroy()
        announcer.stop()
        super.onDestroy()
    }

    // ---- M1.10 A1：起点解析一次、全程一致 ----

    // 首次算路前解析一次起点：优先 M1.7 显式起点（EXTRA），缺失时自行单次定位；
    // 之后所有重算（偏好切换）都用同一个 resolvedStart，与首次算路完全一致。
    private fun resolveStartThenCalculate() {
        // M1.13 A1：已换返程时，重算（切偏好等）必须以"当前这对起终点"为准——起点=原目的地坐标，
        // 否则会拿回原定位点当起点，与已换向的终点重合。仍是显式起点，M1.10 的"绝不交 SDK 自选起点"不变。
        if (reversed) {
            val destination = NaviLatLng().apply {
                latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
                longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
            }
            return doCalculate(arrayListOf(destination))
        }
        resolvedStart?.let { return doCalculate(arrayListOf(it)) }
        val startLat = intent.getDoubleExtra(EXTRA_START_LATITUDE, Double.NaN)
        val startLng = intent.getDoubleExtra(EXTRA_START_LONGITUDE, Double.NaN)
        if (!startLat.isNaN() && !startLng.isNaN()) {
            resolvedStart = NaviLatLng().apply {
                latitude = startLat
                longitude = startLng
            }
            pinCameraToStart()
            return doCalculate(arrayListOf(resolvedStart!!))
        }
        // M1.10 A1 根因封堵：不再允许"空起点交 SDK 自定位"（自定位失败/取到陈旧缓存时
        // 会锚定默认位置，真机表现为跨城超长返程样路线）。自行定位拿不到就中止，绝不算路。
        locateThenCalculate()
    }

    private fun locateThenCalculate() {
        if (locating) return
        locating = true
        toast(getString(R.string.tip_locating))
        val client = AMapLocationClient(applicationContext)
        locateClient = client
        val option = AMapLocationClientOption().apply {
            isOnceLocation = true
            isOnceLocationLatest = true
            isNeedAddress = false
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        }
        client.setLocationOption(option)
        val timeout = Runnable {
            if (!locating) return@Runnable
            locating = false
            locateClient = null
            client.stopLocation()
            client.onDestroy()
            // M1.14 C2：高精度定位超时 → 先降级一次纯网络定位（室内 WiFi 成功率高），仍拿不到才退出
            if (!startNetworkLocationFallback()) {
                toast(getString(R.string.tip_location_unresolved))
                finish()
            }
        }
        mainHandler.postDelayed(timeout, LOCATE_TIMEOUT_MS)
        client.setLocationListener { result ->
            mainHandler.post {
                if (!locating) return@post
                locating = false
                mainHandler.removeCallbacks(timeout)
                locateClient = null
                client.stopLocation()
                client.onDestroy()
                if (result.errorCode == AMAP_LOCATE_SUCCESS && result.latitude != 0.0) {
                    resolvedStart = NaviLatLng().apply {
                        latitude = result.latitude
                        longitude = result.longitude
                    }
                    pinCameraToStart()
                    doCalculate(arrayListOf(resolvedStart!!))
                } else if (!startNetworkLocationFallback()) {
                    // 定位失败宁可退出也不算路：杜绝起点错乱产生的跨城超长路线。
                    // M1.14 C2：这里先降级一次纯网络定位，两级都不成才退出。
                    toast(getString(R.string.tip_location_unresolved))
                    finish()
                }
            }
        }
        client.startLocation()
    }

    // M1.14 C2（默认拍板 22）：本页"起点解析"的高精度单次定位失败/12 秒超时后，追加一次
    // Battery_Saving（纯网络+基站定位）重试；成功即与首次成功走完全相同的路径
    // （resolvedStart 固定 + 钉相机 + doCalculate），只在失败分支内部触发，不动三关门时序。
    private fun startNetworkLocationFallback(): Boolean {
        if (resolvedStart != null) return false
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
            locateClient = null
            client.stopLocation()
            client.onDestroy()
            toast(getString(R.string.tip_location_unresolved))
            finish()
        }
        mainHandler.postDelayed(timeout, FALLBACK_LOCATE_TIMEOUT_MS)
        client.setLocationListener { result ->
            mainHandler.post {
                if (!locating) return@post
                locating = false
                mainHandler.removeCallbacks(timeout)
                locateClient = null
                client.stopLocation()
                client.onDestroy()
                if (result.errorCode == AMAP_LOCATE_SUCCESS && result.latitude != 0.0) {
                    resolvedStart = NaviLatLng().apply {
                        latitude = result.latitude
                        longitude = result.longitude
                    }
                    pinCameraToStart()
                    doCalculate(arrayListOf(resolvedStart!!))
                } else {
                    toast(getString(R.string.tip_locate_failed_indoor, result.errorCode))
                    finish()
                }
            }
        }
        client.startLocation()
        return true
    }

    // M1.12 B5：主页面把途经点拆成三条数组 extra 传进来（不引入 Parcelable，实现从简）
    private fun readWayPoints() {
        val latitudes = intent.getDoubleArrayExtra(EXTRA_WAYPOINT_LATITUDES) ?: doubleArrayOf()
        val longitudes = intent.getDoubleArrayExtra(EXTRA_WAYPOINT_LONGITUDES) ?: doubleArrayOf()
        waypointNames = (intent.getStringArrayExtra(EXTRA_WAYPOINT_NAMES) ?: emptyArray()).toList()
        latitudes.indices.forEach { index ->
            val latitude = latitudes[index]
            val longitude = longitudes.getOrElse(index) { return@forEach }
            wayPoints.add(NaviLatLng().apply {
                this.latitude = latitude
                this.longitude = longitude
            })
        }
    }

    private fun doCalculate(start: ArrayList<NaviLatLng>) {
        val instance = navi ?: return
        // M1.13 A1：换返程后当前终点=原定位点（endPoint 被改写）；未换向时仍是 Intent 传入的目的地。
        val target = endPoint ?: NaviLatLng().apply {
            latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
            longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
        }
        // M1.9 R18：第三参按本机保存的算路偏好传入（设置页/预览态切换后均立即生效）。
        // A1 保证：start 恒为已解析出的显式起点（正向=resolvedStart；换返程=原目的地坐标），
        // 绝不交 SDK 自选起点，重算与首次完全一致。
        // M1.12 B3：改用四参重载 calculateDriveRoute(start, wayPoints, end, strategy)（javap 实锤非弃用）；
        // 无途经点时传空列表，与三参版语义一致（真机若有异常，回退三参只改这一行）。
        val planned = instance.calculateDriveRoute(
            start,
            wayPoints,
            arrayListOf(target),
            strategyConstant(LocalStore.routeStrategy(this))
        )
        if (!planned) {
            toast(getString(R.string.tip_location_unresolved))
        } else {
            toast(getString(R.string.navi_calculating))
        }
    }

    private fun calculateRoute() {
        resolveStartThenCalculate()
    }

    // ---- M1.10 A2：相机钉用户位置 + 预览按路线 bounds 取景 ----

    private fun pinCameraToStart() {
        val start = resolvedStart ?: return
        pinCameraTo(start)
    }

    // M1.13 A1：换返程后相机要跟着新的起点走（同一取景口径，参数化复用）
    private fun pinCameraTo(point: NaviLatLng) {
        naviView?.map?.moveCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), PREVIEW_ZOOM)
        )
    }

    // 预览取景不再依赖 SDK 全览自动取景（真机 v1.3.0 实证其会落空回默认北京）：
    // 显式把相机框到当前所选路线的 bounds，起点/终点/整条路线必然入画。
    private fun frameRouteOverview() {
        val bounds = navi?.naviPath?.boundsForPath
        if (bounds == null) {
            pinCameraToStart()
            return
        }
        naviView?.map?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, ROUTE_PADDING_PX))
    }

    // ---- M1.12 A1：预览态路线绘制（v1.4.1 真机截图 6 实证：相机已按 bounds 取景、信息条数值正确，
    // 但图面既无路线也无起终点扎点——AMapNaviView 的路线覆盖物属于"导航会话"，只切
    // SHOW_MODE_DISPLAY_OVERVIEW 不会触发首次绘制；startNavi 之后 SDK 才自绘。）
    // 卡面排查顺序照做：①options.setAutoDrawRoute(true)（进导航后仍由 SDK 自绘，见 applyNaviViewPrefs）；
    // ②预览态用 SDK 内置 RouteOverLay 显式绘制：选中路线全不透明+高 zindex，其余路线半透明弱显示（不自造线型）。
    private fun drawPreviewRoutes() {
        val map = naviView?.map ?: return
        clearPreviewRoutes()
        val paths = navi?.naviPaths ?: return
        val selectedId = navi?.naviPath?.pathid ?: -1L
        paths.forEach { (id, path) ->
            val overlay = RouteOverLay(map, path, this)
            if (id.toLong() == selectedId) {
                overlay.setZindex(SELECTED_ROUTE_ZINDEX)
            } else {
                overlay.setTransparency(PASSIVE_ROUTE_ALPHA)
                overlay.setZindex(PASSIVE_ROUTE_ZINDEX)
            }
            overlay.addToMap()
            routeOverlays.add(overlay)
        }
    }

    private fun clearPreviewRoutes() {
        routeOverlays.forEach { it.destroy() }
        routeOverlays.clear()
    }

    // M1.13 A1：一键对调起终点（对标高德路线页「⇅」）。只组装起终点参数后调用同一算路入口，
    // 不改算路函数本身；途经点保持不变仍参与算路；刷新走现有 showPreview→绘制/取景/摘要链路。
    private fun toggleRouteDirection() {
        if (naviStarted || aimless) return
        val located = resolvedStart ?: return
        val destination = NaviLatLng().apply {
            latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
            longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
        }
        if (!reversed) {
            reversed = true
            // 返程：起点=原目的地（坐标），终点=原定位点（名称「我的位置」，无地址→坐标兜底）
            endPoint = located
        } else {
            reversed = false
            endPoint = null
        }
        val newStart = if (reversed) destination else located
        routeReverse.setText(if (reversed) R.string.route_reverse_back else R.string.route_reverse)
        paintDirectionLabels()
        pinCameraTo(newStart)
        doCalculate(arrayListOf(newStart))
    }

    // 当前这对起终点的名称：正向=（我的位置 → 目的地）；返程=（目的地 → 我的位置）
    private fun originName(): String = getString(R.string.preview_start_my_location)

    private fun chosenDestinationName(): String {
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
        return if (name.isNotEmpty()) name else getString(R.string.navi_preview_unnamed)
    }

    private fun currentStartName(): String = if (reversed) chosenDestinationName() else originName()

    private fun currentEndName(): String = if (reversed) originName() else chosenDestinationName()

    // M1.12 A2 + M1.13 A1/A5：起终点行（绿点/红点由布局给色标）+ 途经点行（蓝点）+ 信息条标题随之换向
    private fun paintDirectionLabels() {
        startLabel.text = currentStartName()
        endLabel.text = currentEndName()
        findViewById<TextView>(R.id.preview_title).text = currentEndName()
        findViewById<TextView>(R.id.preview_address).text = currentEndAddress()
        val names = waypointNames.filter { it.isNotBlank() }
        if (names.isEmpty()) {
            viaRow.visibility = View.GONE
        } else {
            viaRow.visibility = View.VISIBLE
            viaLabel.text = getString(R.string.preview_via_summary, names.size, names.joinToString("、"))
        }
    }

    // ---- M1.10 C：§2.8 本期档设置应用（导航页）----

    private fun applyNaviViewPrefs() {
        val view = naviView ?: return
        // ① 车头朝上/北向上（视图级方法未弃用）
        view.setNaviMode(
            if (LocalStore.naviOrientation(this) == LocalStore.ORIENT_NORTH_UP) {
                AMapNaviView.NORTH_UP_MODE
            } else {
                AMapNaviView.CAR_UP_MODE
            }
        )
        // ② 手势进入 3D（导航视图底图的 UiSettings）
        view.map?.uiSettings?.setTiltGesturesEnabled(LocalStore.isTiltGestureOn(this))
        // ⑨ 指南针（导航页走 options；主页地图在 MainActivity 用 UiSettings）
        editViewOptions { options ->
            // M1.14 B1（R25②）：关闭 SDK 内置设置菜单——截图 8 地图旁的「EN」+齿轮就是它（本 App 从未配置、
            // 默认开启）。目标=导航页图面只保留自家控件；其余内置悬浮钮的开关已 javap 定位，真机若仍残留逐个关
            // （清单见 work\构建留痕-M1.14.txt 第五节）。
            options.setSettingMenuEnabled(false)
            // M1.12 A1：显式打开 SDK 自动绘制路线（导航态由 SDK 自绘；预览态另用 RouteOverLay 手绘）
            options.setAutoDrawRoute(true)
            // ② 3D 视角：开=30° 俯角+自动缩放；关=0° 正俯视（缩放交回 autoChangeZoom 托管）
            val tilt3d = LocalStore.isTilt3dOn(this)
            options.setTilt(if (tilt3d) NAVI_TILT_3D else NAVI_TILT_2D)
            options.setAutoChangeZoom(tilt3d)
            // ③ 路况显示（options 层官方替代，v1.3.0 起）
            options.setTrafficLine(LocalStore.isTrafficLineOn(this))
            // ⑤ 导航屏幕常亮（该 API 在 10.0.800 已 @Deprecated 且无等价替代，按卡面使用+压制，见待拍板 #13）
            @Suppress("DEPRECATION")
            options.setScreenAlwaysBright(LocalStore.isScreenBrightOn(this))
            // ⑥ 车道信息
            options.setLaneInfoShow(LocalStore.isLaneInfoOn(this))
            // ⑦ 路口放大图（实景路口 + 模型路口）
            val cross = LocalStore.isCrossShowOn(this)
            options.setRealCrossDisplayShow(cross)
            options.setModeCrossDisplayShow(cross)
            // ⑧ 电子眼气泡与提醒（两 API 均 @Deprecated 无替代，按卡面使用+压制，见待拍板 #13）
            @Suppress("DEPRECATION")
            options.setCameraBubbleShow(LocalStore.isCameraBubbleOn(this))
            @Suppress("DEPRECATION")
            options.setCameraInfoUpdateEnabled(LocalStore.isCameraBubbleOn(this))
            options.setCompassEnabled(LocalStore.isCompassOn(this))
            // ④ 昼夜模式（v1.3.0 起，整页生效）
            val night = when (LocalStore.dayNightMode(this)) {
                LocalStore.DAY_NIGHT_NIGHT -> true
                LocalStore.DAY_NIGHT_DAY -> false
                else -> isSystemNight()
            }
            // 卡面提示经 getMap() 设 MAP_TYPE_NIGHT：实测该常量存在可用，但只黑底图、SDK 导航 UI 仍为日间；
            // 且 AMapNaviViewOptions.setNaviNight/setAutoNaviViewNightMode 在 10.0.800 已 @Deprecated。
            // 非弃用的官方整页昼夜开关 = setMapStyle(MapStyle.NIGHT/DAY)（待拍板 #12）。
            options.setMapStyle(if (night) MapStyle.NIGHT else MapStyle.DAY, null)
        }
    }

    private fun applyBroadcastMode() {
        // ⑩ 播报详细/简洁：卡面原判"SDK 无直接开关需自实现"，javap 实查 10.0.800 存在官方开关
        // AMapNavi.setBroadcastMode(BroadcastMode.DETAIL/CONCISE) 且未弃用——直接用官方能力（更稳）。
        navi?.setBroadcastMode(
            if (LocalStore.broadcastMode(this) == LocalStore.BROADCAST_CONCISE) {
                BroadcastMode.CONCISE
            } else {
                BroadcastMode.DETAIL
            }
        )
    }

    private fun isSystemNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun editViewOptions(edit: (AMapNaviViewOptions) -> Unit) {
        val view = naviView ?: return
        val options = view.viewOptions ?: return
        edit(options)
        view.setViewOptions(options)
    }

    // ---- M1.9 R18 多路线选择与算路偏好（仅预览态；导航态按钮随覆盖层隐藏且此处再设 naviStarted 双保险）----

    private fun refreshRouteBar() {
        // getNaviPaths() 的 HashMap 键序不稳定，按 pathId 升序固定顺序；重算成功后 SDK 默认选中第一条（推荐）。
        val paths = navi?.naviPaths
        routeIds = paths?.keys?.map { it.toInt() }?.sorted().orEmpty()
        val currentId = navi?.naviPath?.pathid?.toInt()
        routeIndex = currentId?.let { routeIds.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        paintRouteBar()
    }

    private fun paintRouteBar() {
        val count = routeIds.size
        val label = selectedRouteLabel()
        routeIndicator.text = if (label.isNullOrBlank()) {
            getString(R.string.route_indicator, routeIndex + 1, count)
        } else {
            getString(R.string.route_indicator_label, routeIndex + 1, count, label)
        }
        routePrev.isEnabled = count > 1 && routeIndex > 0
        routeNext.isEnabled = count > 1 && routeIndex < count - 1
        paintRouteTag()
    }

    // M1.13 A4：方案卡标签——≥2 条路线时，allTime 最小者标「时间最短」、allLength 最小者标「距离最短」；
    // 同一条两者都最小时只显示「时间最短」；单条不显示。数据在算路成功后本地比较，不依赖 SDK getLabels() 黑盒。
    private fun paintRouteTag() {
        val paths = navi?.naviPaths
        val currentId = routeIds.getOrNull(routeIndex)
        val current = if (paths == null || currentId == null) null else paths[currentId]
        if (paths == null || current == null || routeIds.size < 2) {
            routeTag.visibility = View.GONE
            return
        }
        val times = routeIds.map { paths[it]?.allTime ?: Int.MAX_VALUE }
        val lengths = routeIds.map { paths[it]?.allLength ?: Int.MAX_VALUE }
        val minTime = times.minOrNull() ?: Int.MAX_VALUE
        val minDistance = lengths.minOrNull() ?: Int.MAX_VALUE
        val timeShortest = minTime > 0 && current.allTime == minTime
        val distanceShortest = minDistance > 0 && current.allLength == minDistance
        routeTag.setText(
            if (timeShortest) R.string.route_tag_time_shortest
            else R.string.route_tag_distance_shortest
        )
        routeTag.visibility = if (timeShortest || distanceShortest) View.VISIBLE else View.GONE
    }

    private fun selectedRouteLabel(): String? {
        val id = routeIds.getOrNull(routeIndex) ?: return null
        return navi?.naviPaths?.get(id)?.labels
    }

    private fun switchRoute(delta: Int) {
        if (naviStarted || aimless) return
        val target = routeIndex + delta
        if (target !in routeIds.indices) return
        val switched = navi?.selectRouteId(routeIds[target]) ?: false
        if (!switched) return
        routeIndex = target
        paintRouteBar()
        // 全览图跟随所选路线重画 + 显式按新路线 bounds 取景；信息条取所选路线的距离/时长。
        naviView?.setShowMode(AMapNaviViewShowMode.SHOW_MODE_DISPLAY_OVERVIEW)
        frameRouteOverview()
        // M1.12 A1：切换路线后重画——选中路线高亮、其余路线继续弱显示
        drawPreviewRoutes()
        naviView?.setRouteMarkerVisible(true, false, false, true, true)
        findViewById<TextView>(R.id.preview_summary).text = routeSummary()
    }

    private fun showStrategyPicker() {
        val current = LocalStore.routeStrategy(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.route_pref_title)
            .setSingleChoiceItems(
                resources.getStringArray(R.array.route_strategy_names),
                current
            ) { dialog, which ->
                dialog.dismiss()
                if (which != current) applyStrategy(which)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun applyStrategy(index: Int) {
        LocalStore.setRouteStrategy(this, index)
        routePref.text = getString(R.string.route_pref_label, strategyName(index))
        if (naviStarted || aimless) return
        // 偏好切换后预览态立即按新策略重算（A1：起点=resolvedStart 与首次一致）；
        // 成功回调 onCalculateRouteSuccess 会再次 showPreview 刷新路线与信息条。
        toast(getString(R.string.route_pref_changed))
        calculateRoute()
    }

    private fun strategyName(index: Int): String {
        val names = resources.getStringArray(R.array.route_strategy_names)
        return names.getOrElse(index) { names.first() }
    }

    // 存储下标（LocalStore 0~4）→ PathPlanningStrategy 多路线族常量（javap 10.0.800 实锤）
    // 10.0.800 中"时间短"仅 DRIVING_MULTIPLE_SHORTEST_TIME_DISTANCE 一个常量且被标 @Deprecated，
    // 常量族内无非弃用等价替代，按卡面点名使用并局部压制警告（同待拍板 #11）。
    @Suppress("DEPRECATION")
    private fun strategyConstant(index: Int): Int = when (index) {
        LocalStore.STRATEGY_SHORTEST_TIME -> PathPlanningStrategy.DRIVING_MULTIPLE_SHORTEST_TIME_DISTANCE
        LocalStore.STRATEGY_AVOID_CONGESTION -> PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_CONGESTION
        LocalStore.STRATEGY_AVOID_HIGHSPEED -> PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_HIGHSPEED
        LocalStore.STRATEGY_PRIORITY_HIGHSPEED -> PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_PRIORITY_HIGHSPEED
        else -> PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT
    }

    private fun showPreview() {
        // 任务卡点名的 displayOverview() 在 10.0.800 已标 @Deprecated（会顶破 G1「0 警」门槛）；
        // 官方替代品即 SDK 全览按钮内部所调的 setShowMode(SHOW_MODE_DISPLAY_OVERVIEW)，语义一致。
        naviView?.setShowMode(AMapNaviViewShowMode.SHOW_MODE_DISPLAY_OVERVIEW)
        // M1.10 A2：显式按路线 bounds 取景（SDK 全览模式切换不再自动可靠取景，真机 v1.3.0 实证）。
        frameRouteOverview()
        // M1.12 A1/A2：预览态显式绘制选中路线（其余弱显示）+ 起终途点扎点
        drawPreviewRoutes()
        naviView?.setRouteMarkerVisible(true, false, false, true, true)
        paintDirectionLabels()
        findViewById<TextView>(R.id.preview_summary).text = routeSummary()
        refreshRouteBar()
        routePref.text = getString(R.string.route_pref_label, strategyName(LocalStore.routeStrategy(this)))
        previewStart.isEnabled = true
    }

    private fun beginNavigation() {
        val instance = navi ?: return
        if (naviStarted || aimless) return
        val type = if (LocalStore.isSimulate(this)) NaviType.EMULATOR else NaviType.GPS
        naviStarted = instance.startNavi(type)
        previewBar.visibility = View.GONE
        previewStart.visibility = View.GONE
        // M1.12 A1：进导航态即撤掉预览态手绘的路线覆盖物，交回 SDK 自绘（autoDrawRoute=true）
        clearPreviewRoutes()
        // 全览后进导航态要把相机交回跟随模式（= 任务卡点名的 recoverLockMode()，同样已标过时，用官方替代）。
        naviView?.setShowMode(AMapNaviViewShowMode.SHOW_MODE_LOCK_CAR)
    }

    // M1.13 A2/A3：摘要追加红绿灯数、过路费与预计到达时刻。
    // 到达时刻=本机时钟 + 所选路线 allTime（秒）换算，纯本地计算零网络；切换路线后 routeSummary 同一处刷新。
    private fun routeSummary(): String {
        val path = navi?.naviPath ?: return getString(R.string.navi_preview_time_pending)
        val meters = path.allLength
        val seconds = path.allTime
        if (meters <= 0) return getString(R.string.navi_preview_time_pending)
        val distance = if (meters >= 1000) {
            String.format(Locale.CHINA, "%.1f 公里", meters / 1000.0)
        } else {
            getString(R.string.navi_preview_meters, meters)
        }
        val minutes = if (seconds <= 0) 1 else (seconds + 59) / 60
        val parts = arrayListOf(getString(R.string.navi_preview_summary, distance, minutes))
        val lights = path.trafficLightCount
        if (lights >= 0) parts.add(getString(R.string.navi_preview_lights, lights))
        val toll = path.tollCost
        if (toll > 0) parts.add(getString(R.string.navi_preview_toll, toll))
        if (seconds > 0) {
            val arrival = Date(System.currentTimeMillis() + seconds * 1000L)
            parts.add(getString(R.string.navi_preview_arrive, clockFormat.format(arrival)))
        }
        return parts.joinToString(" · ")
    }

    private fun destinationLabel(): String {
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
        if (name.isNotEmpty()) return name
        return getString(R.string.navi_preview_unnamed)
    }

    // M1.13 A1：换返程后信息条地址=新终点地址；返程终点是定位点（无地址）→ 坐标兜底，与既有口径一致
    private fun currentEndAddress(): String {
        if (!reversed) return destinationAddress()
        val located = resolvedStart ?: return ""
        return String.format(Locale.CHINA, "%.6f, %.6f", located.latitude, located.longitude)
    }

    private fun destinationAddress(): String {
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty().trim()
        if (address.isNotEmpty()) return address
        val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
        val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
        return String.format(Locale.CHINA, "%.6f, %.6f", latitude, longitude)
    }

    // M1.6 B1：进入导航页（导航已发起）即记一条历史；关闭记录时 addHistory 内部直接返回。
    private fun recordHistory() {
        if (aimless) return
        // M1.13 A1：返程（终点=「我的位置」）不写历史——历史只收真实去过的目的地。
        // 本方法只在 onCreate 调一次（那时 reversed 必为 false），此判断把"换返程后不记"的口径显式钉住，
        // 防止将来在换向/重算处复用本方法时把「我的位置」记进历史。
        if (reversed || currentEndName() == originName()) return
        val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
        val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
        if (latitude == 0.0 || longitude == 0.0) return
        LocalStore.addHistory(
            this,
            SavedPoi(destinationLabel(), destinationAddress(), latitude, longitude)
        )
    }

    private fun exitNavi() {
        if (aimless) {
            navi?.stopAimlessMode()
        } else {
            navi?.stopNavi()
        }
        announcer.stop()
        finish()
    }

    private fun speakOnce(text: String) {
        // 预览态不播报：只有按下「开始导航」进入导航态后才允许出声（巡航模式放行电子眼通报）。
        if (!naviStarted && !aimless) return
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastTextAt < DUPLICATE_WINDOW_MS) return
        lastText = text
        lastTextAt = now
        announcer.announce(text)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_LATITUDE = "dest_latitude"
        const val EXTRA_LONGITUDE = "dest_longitude"
        const val EXTRA_NAME = "dest_name"
        const val EXTRA_ADDRESS = "dest_address"
        const val EXTRA_START_LATITUDE = "start_latitude"
        const val EXTRA_START_LONGITUDE = "start_longitude"
        const val EXTRA_AIMLESS = "aimless_mode"
        // M1.12 B5：途经点（主页面拆数组 extra 传入，仅本次会话）
        const val EXTRA_WAYPOINT_LATITUDES = "waypoint_latitudes"
        const val EXTRA_WAYPOINT_LONGITUDES = "waypoint_longitudes"
        const val EXTRA_WAYPOINT_NAMES = "waypoint_names"
        private const val DUPLICATE_WINDOW_MS = 3000L
        private const val LOCATE_TIMEOUT_MS = 12000L
        // M1.14 C2：降级为纯网络定位的那一次重试（卡面口径约 3~5 秒，取 5 秒上限）
        private const val FALLBACK_LOCATE_TIMEOUT_MS = 5000L
        private const val AMAP_LOCATE_SUCCESS = 0
        private const val PREVIEW_ZOOM = 16f
        private const val ROUTE_PADDING_PX = 120
        // M1.12 A1：多路线弱显示+选中高亮的透明度与层级（SDK RouteOverLay 自带能力，不自造线型）
        private const val PASSIVE_ROUTE_ALPHA = 0.35f
        private const val SELECTED_ROUTE_ZINDEX = 2
        private const val PASSIVE_ROUTE_ZINDEX = 1
        // ② 3D 视角开=30° 俯角（对齐高德导航默认俯角），关=0° 正俯视
        private const val NAVI_TILT_3D = 30
        private const val NAVI_TILT_2D = 0
    }
}
