package com.example.skyedge.ui.map

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.TextureMapView
import com.example.skyedge.core.api.MapSessionUiModel

@Composable
fun AMapCompose(
    mapSession: MapSessionUiModel?,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val savedState = remember { Bundle() }
    var mapView by remember { mutableStateOf<TextureMapView?>(null) }
    var overlayManager by remember { mutableStateOf<MapOverlayManager?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureMapView(context).also { view ->
                view.onCreate(savedState)
                val map = view.map
                map.mapType = AMap.MAP_TYPE_SATELLITE
                map.uiSettings.isZoomControlsEnabled = true
                map.uiSettings.isMyLocationButtonEnabled = false
                mapView = view
                overlayManager = MapOverlayManager(map)
            }
        },
        update = {
            overlayManager?.render(mapSession)
        },
    )

    LaunchedEffect(mapSession) {
        overlayManager?.render(mapSession)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                Lifecycle.Event.ON_STOP -> mapView?.onSaveInstanceState(savedState)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            overlayManager?.clear()
            mapView?.onDestroy()
            mapView = null
            overlayManager = null
        }
    }
}
