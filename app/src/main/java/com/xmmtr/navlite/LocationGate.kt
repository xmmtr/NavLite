package com.xmmtr.navlite

import android.content.Context
import android.location.LocationManager

// 导航前置检查：系统定位服务（总开关）是否开启。
// 注意与"App 定位权限"区分——权限弹窗之外，安卓还有一个系统级"位置信息"总开关，
// 关掉它时任何 App 都拿不到位置（真机实测踩到：权限给了但算路仍失败）。
object LocationGate {

    fun isServiceOn(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)
    }
}
