package com.xmmtr.navlite

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.AMapException
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery

// 设置页：粘贴高德 Key → 存本机 → MapsInitializer.setApiKey 运行时注入。
// 「保存并测试」用 SDK 的 Inputtips 发一次真实搜索验证 Key（v1.1.1 方案A）：
// 与地图/导航同一 Key 通道，回调码 1000=有效；不再用 restapi.amap.com（该 REST 通道要求
// "Web服务"类型 Key，与 Android 平台 Key 不符，必报 10009 USERKEY_PLAT_NOMATCH）。
// M1.6 追加：我的位置（家/公司 + 自定义位置 ≤10）与历史记录开关/清空，全部仅存本机。
class SettingsActivity : Activity() {

    private lateinit var keyField: EditText
    private lateinit var testButton: Button
    private lateinit var keyStatus: TextView

    private lateinit var homeValue: TextView
    private lateinit var homeEdit: Button
    private lateinit var companyValue: TextView
    private lateinit var companyEdit: Button
    private lateinit var placesContainer: LinearLayout
    private lateinit var placeAdd: Button
    private lateinit var historySwitch: Switch
    private lateinit var historyCount: TextView
    private lateinit var dayNightValue: TextView
    private lateinit var strategyValue: TextView
    private lateinit var orientationValue: TextView
    private lateinit var broadcastValue: TextView
    private lateinit var searchValue: TextView
    private lateinit var langValue: TextView

    override fun attachBaseContext(base: Context) {
        // M1.14 B3：语言在 base 上生效（无 appcompat）
        super.attachBaseContext(base.localized())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        keyField = findViewById(R.id.key_input)
        testButton = findViewById(R.id.key_test)
        keyStatus = findViewById(R.id.key_status)

        findViewById<TextView>(R.id.settings_version).setText(R.string.about_version)

        keyField.setText(LocalStore.amapKey(this))
        paintKeyStatus()

        val simulate = findViewById<Switch>(R.id.simulate_switch)
        simulate.isChecked = LocalStore.isSimulate(this)
        simulate.setOnCheckedChangeListener { _, checked -> LocalStore.setSimulate(this, checked) }

        val muteDefault = findViewById<Switch>(R.id.mute_switch)
        muteDefault.isChecked = LocalStore.isMuted(this)
        muteDefault.setOnCheckedChangeListener { _, checked -> LocalStore.setMuted(this, checked) }

        testButton.setOnClickListener { saveAndTest() }

        setUpPlaces()
        setUpHistory()
        setUpGeneralAndHome()
        setUpCruiseAndAbout()

        intent.getStringExtra(EXTRA_EDIT_PLACE)?.let { id ->
            LocalStore.findPlace(this, id)?.let { openPlaceActions(it) }
        }
        // M1.11：主页面 chip 长按=跳设置页对应项编辑（家/公司）
        when (intent.getStringExtra(EXTRA_EDIT_SLOT)) {
            LocalStore.SLOT_HOME -> pickSlot(LocalStore.SLOT_HOME)
            LocalStore.SLOT_COMPANY -> pickSlot(LocalStore.SLOT_COMPANY)
        }
    }

    // M1.11 默认拍板 19：首页顶部小按钮排取消后，巡航入口与"关于"自检收进设置页。
    // 巡航仍由主页面走 M1.7 三关门→NaviActivity 巡航模式（startAimlessMode/stopAimlessMode 链路未动）。
    private fun setUpCruiseAndAbout() {
        findViewById<Button>(R.id.cruise_button).setOnClickListener {
            if (!AmapKeyState.hasKey) {
                toast(getString(R.string.tip_no_key))
                return@setOnClickListener
            }
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_START_CRUISE, true)
            )
        }
        findViewById<TextView>(R.id.about_key).setText(
            if (AmapKeyState.hasKey) R.string.about_key_ok else R.string.about_key_missing
        )
        findViewById<TextView>(R.id.about_tts).setText(
            if ((application as NavLiteApp).announcer.chineseReady) R.string.about_tts_ok else R.string.about_tts_bad
        )
    }

    private fun setUpPlaces() {
        homeValue = findViewById(R.id.home_value)
        homeEdit = findViewById(R.id.home_edit)
        companyValue = findViewById(R.id.company_value)
        companyEdit = findViewById(R.id.company_edit)
        placesContainer = findViewById(R.id.places_container)
        placeAdd = findViewById(R.id.place_add)

        homeEdit.setOnClickListener { pickSlot(LocalStore.SLOT_HOME) }
        companyEdit.setOnClickListener { pickSlot(LocalStore.SLOT_COMPANY) }
        findViewById<Button>(R.id.home_clear).setOnClickListener {
            LocalStore.clearPoi(this, LocalStore.SLOT_HOME)
            toast(getString(R.string.settings_home_cleared))
            paintPlaces()
        }
        findViewById<Button>(R.id.company_clear).setOnClickListener {
            LocalStore.clearPoi(this, LocalStore.SLOT_COMPANY)
            toast(getString(R.string.settings_company_cleared))
            paintPlaces()
        }
        placeAdd.setOnClickListener {
            if (LocalStore.places(this).size >= LocalStore.PLACES_LIMIT) {
                paintPlaces()
                toast(getString(R.string.settings_place_limit))
                return@setOnClickListener
            }
            PoiPicker.askName(this, getString(R.string.settings_place_name_title), "") { name ->
                pickForPlace(name, null)
            }
        }
        paintPlaces()
    }

    // M1.9/M1.10 通用设置（R16 完整清单 §2.8）与首页设置（R17 搜索框位置）：
    // 全部仅存本机 SharedPreferences；主页/导航页 onResume 即时应用。
    private fun setUpGeneralAndHome() {
        dayNightValue = findViewById(R.id.daynight_value)
        strategyValue = findViewById(R.id.strategy_value)
        orientationValue = findViewById(R.id.orientation_value)
        broadcastValue = findViewById(R.id.broadcast_value)
        searchValue = findViewById(R.id.search_value)
        langValue = findViewById(R.id.lang_value)

        val traffic = findViewById<Switch>(R.id.traffic_switch)
        traffic.isChecked = LocalStore.isTrafficLineOn(this)
        traffic.setOnCheckedChangeListener { _, checked -> LocalStore.setTrafficLineOn(this, checked) }

        val tilt3d = findViewById<Switch>(R.id.tilt3d_switch)
        tilt3d.isChecked = LocalStore.isTilt3dOn(this)
        tilt3d.setOnCheckedChangeListener { _, checked -> LocalStore.setTilt3dOn(this, checked) }

        val tiltGesture = findViewById<Switch>(R.id.tiltgesture_switch)
        tiltGesture.isChecked = LocalStore.isTiltGestureOn(this)
        tiltGesture.setOnCheckedChangeListener { _, checked -> LocalStore.setTiltGestureOn(this, checked) }

        val screenBright = findViewById<Switch>(R.id.screenbright_switch)
        screenBright.isChecked = LocalStore.isScreenBrightOn(this)
        screenBright.setOnCheckedChangeListener { _, checked -> LocalStore.setScreenBrightOn(this, checked) }

        val laneInfo = findViewById<Switch>(R.id.laneinfo_switch)
        laneInfo.isChecked = LocalStore.isLaneInfoOn(this)
        laneInfo.setOnCheckedChangeListener { _, checked -> LocalStore.setLaneInfoOn(this, checked) }

        val crossShow = findViewById<Switch>(R.id.crossshow_switch)
        crossShow.isChecked = LocalStore.isCrossShowOn(this)
        crossShow.setOnCheckedChangeListener { _, checked -> LocalStore.setCrossShowOn(this, checked) }

        val cameraBubble = findViewById<Switch>(R.id.camerabubble_switch)
        cameraBubble.isChecked = LocalStore.isCameraBubbleOn(this)
        cameraBubble.setOnCheckedChangeListener { _, checked -> LocalStore.setCameraBubbleOn(this, checked) }

        val compass = findViewById<Switch>(R.id.compass_switch)
        compass.isChecked = LocalStore.isCompassOn(this)
        compass.setOnCheckedChangeListener { _, checked -> LocalStore.setCompassOn(this, checked) }

        findViewById<View>(R.id.daynight_row).setOnClickListener {
            showChoiceDialog(
                R.string.settings_daynight,
                R.array.day_night_names,
                LocalStore.dayNightMode(this)
            ) { which ->
                LocalStore.setDayNightMode(this, which)
                paintGeneralValues()
            }
        }
        findViewById<View>(R.id.strategy_row).setOnClickListener {
            showChoiceDialog(
                R.string.settings_strategy,
                R.array.route_strategy_names,
                LocalStore.routeStrategy(this)
            ) { which ->
                LocalStore.setRouteStrategy(this, which)
                paintGeneralValues()
            }
        }
        findViewById<View>(R.id.orientation_row).setOnClickListener {
            showChoiceDialog(
                R.string.settings_navi_orientation,
                R.array.orientation_names,
                LocalStore.naviOrientation(this)
            ) { which ->
                LocalStore.setNaviOrientation(this, which)
                paintGeneralValues()
            }
        }
        findViewById<View>(R.id.broadcast_row).setOnClickListener {
            showChoiceDialog(
                R.string.settings_broadcast,
                R.array.broadcast_names,
                LocalStore.broadcastMode(this)
            ) { which ->
                LocalStore.setBroadcastModePref(this, which)
                paintGeneralValues()
            }
        }
        findViewById<View>(R.id.search_row).setOnClickListener {
            showChoiceDialog(
                R.string.settings_search_pos,
                R.array.search_position_names,
                LocalStore.searchPosition(this)
            ) { which ->
                LocalStore.setSearchPosition(this, which)
                paintGeneralValues()
            }
        }
        // M1.14 B2（R25②）：语言=一个按钮点击在 中文↔English 之间循环（不做并排双按钮），
        // 右列显示当前值；B3 生效=createConfigurationContext + recreate()（本页立即重建，主页面 onResume 检测重建）。
        findViewById<View>(R.id.lang_row).setOnClickListener {
            val next = if (LocalStore.appLang(this) == LocalStore.LANG_EN) LocalStore.LANG_ZH else LocalStore.LANG_EN
            LocalStore.setAppLang(this, next)
            paintGeneralValues()
            recreate()
        }
        paintGeneralValues()
    }

    private fun showChoiceDialog(titleRes: Int, namesRes: Int, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(resources.getStringArray(namesRes), checked) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun paintGeneralValues() {
        dayNightValue.text = arrayValue(R.array.day_night_names, LocalStore.dayNightMode(this))
        strategyValue.text = arrayValue(R.array.route_strategy_names, LocalStore.routeStrategy(this))
        orientationValue.text = arrayValue(R.array.orientation_names, LocalStore.naviOrientation(this))
        broadcastValue.text = arrayValue(R.array.broadcast_names, LocalStore.broadcastMode(this))
        searchValue.text = arrayValue(R.array.search_position_names, LocalStore.searchPosition(this))
        langValue.setText(
            if (LocalStore.appLang(this) == LocalStore.LANG_EN) R.string.language_en else R.string.language_zh
        )
    }

    private fun arrayValue(namesRes: Int, index: Int): String {
        val names = resources.getStringArray(namesRes)
        return names.getOrElse(index) { names.first() }
    }

    private fun setUpHistory() {
        historySwitch = findViewById(R.id.history_switch)
        historyCount = findViewById(R.id.history_count)
        historySwitch.isChecked = LocalStore.isHistoryOn(this)
        historySwitch.setOnCheckedChangeListener { _, checked ->
            // 关闭=停止记录 + 主页面隐藏，旧数据保留（R13）
            LocalStore.setHistoryOn(this, checked)
            paintHistory()
        }
        findViewById<Button>(R.id.history_clear).setOnClickListener { confirmClearHistory() }
        paintHistory()
    }

    private fun paintHistory() {
        historyCount.text = if (LocalStore.isHistoryOn(this)) {
            getString(R.string.settings_history_count, LocalStore.historyCount(this))
        } else {
            getString(R.string.settings_history_count, LocalStore.historyCount(this)) + "（已关闭记录）"
        }
    }

    private fun confirmClearHistory() {
        val total = LocalStore.historyCount(this)
        if (total == 0) {
            toast(getString(R.string.settings_clear_history_empty))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_history_title)
            .setMessage(getString(R.string.settings_clear_history_msg, total))
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                LocalStore.clearHistory(this)
                toast(getString(R.string.settings_clear_history_done))
                paintHistory()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun paintPlaces() {
        paintSlotRow(LocalStore.SLOT_HOME, homeValue, homeEdit)
        paintSlotRow(LocalStore.SLOT_COMPANY, companyValue, companyEdit)

        placesContainer.removeAllViews()
        LocalStore.places(this).forEach { place ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_place, placesContainer, false)
            row.findViewById<TextView>(R.id.place_row_label).text =
                if (place.address.isBlank()) place.name else "${place.name}  ${place.address}"
            row.findViewById<Button>(R.id.place_row_edit).setOnClickListener { openPlaceActions(place) }
            row.findViewById<Button>(R.id.place_row_delete).setOnClickListener {
                LocalStore.deletePlace(this, place.id)
                toast(getString(R.string.settings_place_deleted, place.name))
                paintPlaces()
            }
            placesContainer.addView(row)
        }

        val atLimit = LocalStore.places(this).size >= LocalStore.PLACES_LIMIT
        placeAdd.isEnabled = !atLimit
        placeAdd.alpha = if (atLimit) 0.4f else 1f
    }

    private fun paintSlotRow(slot: String, value: TextView, edit: Button) {
        val label = if (slot == LocalStore.SLOT_HOME) getString(R.string.shortcut_home) else getString(R.string.shortcut_company)
        val poi = LocalStore.savedPoi(this, slot)
        value.text = if (poi == null) {
            getString(R.string.settings_poi_none).let { "$label：$it" }
        } else {
            "$label：${poi.name}"
        }
        edit.setText(if (poi == null) R.string.settings_poi_set else R.string.settings_poi_edit)
    }

    private fun pickSlot(slot: String) {
        if (!requireKeyForSearch()) return
        val title = if (slot == LocalStore.SLOT_HOME) {
            getString(R.string.title_set_home)
        } else {
            getString(R.string.title_set_company)
        }
        PoiPicker.show(this, title) { poi ->
            LocalStore.savePoi(this, slot, poi)
            toast(getString(R.string.settings_place_saved, poi.name))
            paintPlaces()
        }
    }

    private fun pickForPlace(name: String, replaceId: String?) {
        if (!requireKeyForSearch()) return
        PoiPicker.show(this, getString(R.string.title_set_place, name)) { poi ->
            if (replaceId == null) {
                LocalStore.addPlace(this, name, poi)
                paintPlaces()
            } else {
                LocalStore.updatePlace(this, replaceId, name, poi)
            }
            toast(getString(R.string.settings_place_saved, name))
            paintPlaces()
        }
    }

    // 自定义位置的「编辑」=改名 / 重新选点 / 删除（R14 可改名、改坐标、删除）
    private fun openPlaceActions(place: SavedPlace) {
        val options = arrayOf(
            getString(R.string.settings_poi_rename),
            getString(R.string.settings_poi_relocate),
            getString(R.string.settings_poi_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(place.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> PoiPicker.askName(
                        this,
                        getString(R.string.settings_place_rename_title),
                        place.name
                    ) { name ->
                        LocalStore.updatePlace(this, place.id, name, null)
                        toast(getString(R.string.settings_place_saved, name))
                        paintPlaces()
                    }

                    1 -> pickForPlace(place.name, place.id)

                    2 -> {
                        LocalStore.deletePlace(this, place.id)
                        toast(getString(R.string.settings_place_deleted, place.name))
                        paintPlaces()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun requireKeyForSearch(): Boolean {
        if (AmapKeyState.hasKey) return true
        toast(getString(R.string.tip_no_key))
        return false
    }

    private fun saveAndTest() {
        val key = keyField.text.toString().trim()
        if (key.isEmpty()) {
            toast(getString(R.string.settings_key_empty))
            return
        }
        if (key.length != KEY_LENGTH) {
            toast(getString(R.string.settings_key_suspect))
        }
        LocalStore.setAmapKey(this, key)
        AmapKeyState.update(key)
        // 本进程内立即生效；若搜索/导航引擎已用旧 Key 初始化，杀掉应用重开一次即可。
        MapsInitializer.setApiKey(key)
        paintKeyStatus()
        toast(getString(R.string.settings_key_saved))
        testButton.isEnabled = false
        keyStatus.text = getString(R.string.settings_testing)

        val probe = Inputtips(
            applicationContext,
            InputtipsQuery(PROBE_KEYWORD, "")
        )
        probe.setInputtipsListener(object : Inputtips.InputtipsListener {
            override fun onGetInputtips(tips: MutableList<com.amap.api.services.help.Tip>?, code: Int) {
                runOnUiThread { finishTest(code) }
            }
        })
        probe.requestInputtipsAsyn()
    }

    // 修复 v1.1 缺陷：测试结果此前起后台线程后未回写界面，按钮永远禁用、状态永远停在"正在测试"。
    private fun finishTest(code: Int) {
        if (isFinishing) return
        testButton.isEnabled = true
        when (code) {
            AMapException.CODE_AMAP_SUCCESS -> {
                keyStatus.text = getString(R.string.settings_key_ok)
                toast(getString(R.string.settings_key_ok_toast))
            }
            AMapException.CODE_AMAP_INVALID_USER_KEY,
            AMapException.CODE_AMAP_USERKEY_PLAT_NOMATCH,
            AMapException.CODE_AMAP_INVALID_USER_SCODE -> {
                keyStatus.text = getString(R.string.settings_key_bad, describe(code))
                toast(getString(R.string.settings_key_bad_toast))
            }
            else -> {
                keyStatus.text = getString(R.string.settings_key_untested, code)
            }
        }
    }

    private fun describe(code: Int): String = when (code) {
        AMapException.CODE_AMAP_INVALID_USER_KEY -> "Key 无效或已删除"
        AMapException.CODE_AMAP_USERKEY_PLAT_NOMATCH -> "Key 平台类型不符（应申请 Android 平台 Key）"
        AMapException.CODE_AMAP_INVALID_USER_SCODE -> "Key 与包名/SHA1 不匹配（需用本项目 APK 签名）"
        else -> "错误码 $code"
    }

    private fun paintKeyStatus() {
        keyStatus.text = getString(
            if (AmapKeyState.hasKey) R.string.settings_key_configured else R.string.settings_key_missing
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_EDIT_PLACE = "edit_place_id"
        const val EXTRA_EDIT_SLOT = "edit_slot"
        private const val KEY_LENGTH = 32
        private const val PROBE_KEYWORD = "测试"
    }
}
