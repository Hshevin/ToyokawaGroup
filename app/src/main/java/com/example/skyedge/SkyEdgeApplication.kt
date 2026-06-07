package com.example.skyedge

import android.app.Application

class SkyEdgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            val initializer = Class.forName("com.amap.api.maps.MapsInitializer")
            val updatePrivacyShow = initializer.getMethod(
                "updatePrivacyShow",
                android.content.Context::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            val updatePrivacyAgree = initializer.getMethod(
                "updatePrivacyAgree",
                android.content.Context::class.java,
                Boolean::class.javaPrimitiveType,
            )
            updatePrivacyShow.invoke(null, this, true, true)
            updatePrivacyAgree.invoke(null, this, true)
        }
    }
}
