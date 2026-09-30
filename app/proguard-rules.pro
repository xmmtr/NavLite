# 高德 SDK（导航/地图/定位/搜索合包）内部反射与 JNI 回调需要保留，误删会在运行期崩。
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-keep class com.amap.apis.utils.core.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
