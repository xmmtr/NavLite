package com.xmmtr.navlite

import android.content.Context

// 运行时 Key 状态：App 启动时从本机偏好读入，设置页保存后即时刷新。
// 高德 SDK 侧的注入在 NavLiteApp 里完成（MapsInitializer.setApiKey，必须在任何地图渲染前）。
object AmapKeyState {
    var current: String = ""
        private set

    val hasKey: Boolean
        get() = current.isNotEmpty()

    fun refresh(context: Context) {
        current = LocalStore.amapKey(context)
    }

    fun update(key: String) {
        current = key.trim()
    }
}
