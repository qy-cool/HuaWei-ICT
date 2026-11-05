package cloide.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * cloide-shell 应用入口，启用 Hilt 依赖注入。
 */
@HiltAndroidApp
class CloideApp : Application()
