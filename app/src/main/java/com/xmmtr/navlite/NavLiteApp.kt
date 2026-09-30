package com.xmmtr.navlite

import android.app.Application
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer

class NavLiteApp : Application() {
    val announcer: TtsAnnouncer by lazy { TtsAnnouncer(this) }

    override fun onCreate() {
        super.onCreate()
        applyAmapPrivacy()
        AmapKeyState.refresh(this)
        val key = AmapKeyState.current
        if (key.isNotEmpty()) {
            // 运行时注入必须发生在任何 MapView/NaviView 渲染之前；
            // 设置页改 Key 后同样会调 setApiKey，若导航仍报 Key 错误则杀进程重开一次即完全生效。
            MapsInitializer.setApiKey(key)
        }
    }

    private fun applyAmapPrivacy() {
        // 高德 2021 起要求隐私双确认；10.0.800 四合一包共享地图核心（隐私只在 MapsInitializer 一处，
        // javap 实查 AMapNavi 无对应静态方法），但定位 SDK 的隐私接口独立挂在 AMapLocationClient，
        // M1.7 起主页面用单次定位取起点，必须先过定位侧双确认否则 AMapLocationClient 拒绝工作。
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
    }
}
