package com.xmmtr.navlite

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.services.core.AMapException
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip

data class TipItem(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)

// v1.1.1（方案A）：输入联想改回安卓 SDK 的 Inputtips 通道。
// v1.1 曾改走 restapi.amap.com Web 版接口，真机+官方错误码表证实该通道要求"Web服务"类型 Key，
// 与本 App 分发方案（用户申请 Android 平台 Key）平台不符（错误码 10009 USERKEY_PLAT_NOMATCH）；
// SDK 通道与地图/导航共用 MapsInitializer.setApiKey 注入的 Android Key。
class TipSearcher(context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var debounceTask: Runnable? = null
    private var callback: ((List<TipItem>, Int) -> Unit)? = null

    private val inputtips = Inputtips(context.applicationContext, InputtipsQuery("", "")).apply {
        setInputtipsListener(object : Inputtips.InputtipsListener {
            override fun onGetInputtips(tips: MutableList<Tip>?, code: Int) {
                callback?.invoke(tips?.toList().orEmpty().mapNotNull { it.toItem() }, code)
            }
        })
    }

    fun request(keyword: String, delayMillis: Long, onResult: (List<TipItem>, Int) -> Unit) {
        cancel()
        callback = onResult
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            onResult(emptyList(), AMapException.CODE_AMAP_SUCCESS)
            return
        }
        if (delayMillis <= 0L) {
            fire(trimmed)
            return
        }
        val task = Runnable { fire(trimmed) }
        debounceTask = task
        mainHandler.postDelayed(task, delayMillis)
    }

    private fun fire(keyword: String) {
        inputtips.setQuery(InputtipsQuery(keyword, ""))
        inputtips.requestInputtipsAsyn()
    }

    fun cancel() {
        debounceTask?.let { mainHandler.removeCallbacks(it) }
        debounceTask = null
    }

    private fun Tip.toItem(): TipItem? {
        val point = point ?: return null
        return TipItem(
            name = name?.trim().orEmpty(),
            address = address?.trim().orEmpty(),
            latitude = point.latitude,
            longitude = point.longitude
        )
    }
}
