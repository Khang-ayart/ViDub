package com.vidub.app

import android.app.Application
import com.google.mlkit.common.sdkinternal.MlKitContext

class ViDubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // ML Kit thường tự khởi động qua ContentProvider; trên một số máy bước này không chạy
        // nên gọi thủ công để dịch và nhận diện ngôn ngữ không báo "MlKitContext has not been initialized".
        try {
            MlKitContext.initializeIfNeeded(this)
        } catch (t: Throwable) {
            mlkitError = t.message ?: t.javaClass.simpleName
        }
    }

    companion object {
        @Volatile
        var mlkitError: String? = null
    }
}
