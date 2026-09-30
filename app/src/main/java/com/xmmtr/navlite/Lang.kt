package com.xmmtr.navlite

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

// M1.14 R25②/B3：切换 App 语言。工程只有高德四合一一行依赖、没有 appcompat，
// 所以用全版本通用做法：在 Activity.attachBaseContext 里把 base 包一层指定 Locale 的配置，
// 语言变化后 recreate() 即整页生效（不引 AppCompatDelegate）。
fun Context.localized(): Context {
    val language = if (LocalStore.appLang(this) == LocalStore.LANG_EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
    Locale.setDefault(language)
    val config = Configuration(resources.configuration)
    config.setLocale(language)
    return createConfigurationContext(config)
}
