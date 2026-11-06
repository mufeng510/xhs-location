package com.mufeng.xhs.location.ui.map

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import android.widget.FrameLayout
import android.widget.Button
import android.view.Gravity
import android.graphics.Color

/**
 * 地图选点 Activity。
 *
 * 使用 WebView 加载 Leaflet.js + OpenStreetMap 瓦片源（tile.openstreetmap.org），
 * 无需 Mapbox access token，在中国大陆境内也可正常加载地图。
 * 用户在地图上点击可选定位置，点击“确认选择”返回所选经纬度。
 */
class MapActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var selectedLat: Double = 0.0
    private var selectedLon: Double = 0.0
    private var selectedAddress: String = ""

    /** 默认中心点：北京 */
    private val DEFAULT_LAT = 39.9042
    private val DEFAULT_LON = 116.4074

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return false
            }

            override fun onReceivedError(view: WebView, requestCode: Int, description: String, failingUrl: String) {
                super.onReceivedError(view, requestCode, description, failingUrl)
                view.loadDataWithBaseURL(null, errorHtml(description), "text/html", "UTF-8", null)
            }
        }
        webView.webChromeClient = WebChromeClient()

        // 注入 JavaScript 接口，供地图点击回传坐标
        webView.addJavascriptInterface(MapJsInterface(this), "AndroidMap")

        val layout = FrameLayout(this)
        layout.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 底部确认按钮
        val confirmBtn = Button(this)
        confirmBtn.text = "确认选择"
        confirmBtn.setTextColor(Color.WHITE)
        confirmBtn.setBackgroundColor(Color.parseColor("#6200EE"))
        confirmBtn.setOnClickListener {
            val resultIntent = Intent().apply {
                putExtra("latitude", selectedLat)
                putExtra("longitude", selectedLon)
                putExtra("address", selectedAddress)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
        layout.addView(confirmBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 40
        })
        setContentView(layout)

        // 加载内嵌 Leaflet 地图 HTML
        webView.loadDataWithBaseURL(null, mapHtml(), "text/html", "UTF-8", null)
    }

    /** 内嵌 Leaflet + OpenStreetMap 地图 HTML（CSS 内嵌，JS 使用国内 CDN，避免境外资源加载失败） */
    private fun mapHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                /* Leaflet 核心 CSS（精简内嵌） */
                .leaflet-container { width: 100vw; height: 100vh; margin: 0; padding: 0; }
                .leaflet-tile, .leaflet-marker-icon, .leaflet-marker-shadow, .leaflet-tile-shadow, .leaflet-tile-container { position: absolute; z-index: 1; }
                .leaflet-marker-icon, .leaflet-marker-shadow { z-index: 2; }
                .leaflet-container a { color: #0078A8; }
                body { margin: 0; padding: 0; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script src="https://cdn.staticfile.org/leaflet/1.9.4/leaflet.js"></script>
            <script>
                var map = L.map('map').setView([$DEFAULT_LAT, $DEFAULT_LON], 13);
                L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    attribution: '© OpenStreetMap contributors',
                    maxZoom: 19
                }).addTo(map);
                var marker = L.marker([$DEFAULT_LAT, $DEFAULT_LON]).addTo(map);
                map.on('click', function(e) {
                    var lat = e.latlng.lat.toFixed(6);
                    var lon = e.latlng.lng.toFixed(6);
                    marker.setLatLng([lat, lon]).update();
                    map.panTo([lat, lon]);
                    AndroidMap.onMapClick(lat, lon);
                });
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    /** 地图加载错误提示 HTML */
    private fun errorHtml(msg: String): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body { font-family: sans-serif; padding: 40px; text-align: center; color: #333; }
                .error { color: #d32f2f; font-size: 18px; margin-bottom: 16px; }
                .hint { color: #666; font-size: 14px; }
            </style>
        </head>
        <body>
            <div class="error">地图加载失败</div>
            <div class="hint">$msg</div>
            <div class="hint">请检查网络连接后重试</div>
        </body>
        </html>
        """.trimIndent()
    }

    /** JavaScript 接口：接收地图点击坐标 */
    class MapJsInterface(private val activity: MapActivity) {
        @JavascriptInterface
        fun onMapClick(lat: String, lon: String) {
            activity.selectedLat = lat.toDoubleOrNull() ?: 0.0
            activity.selectedLon = lon.toDoubleOrNull() ?: 0.0
            activity.selectedAddress = "($activity.selectedLat, $activity.selectedLon)"
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    companion object {
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ADDRESS = "address"

        fun createIntent(context: android.content.Context): Intent =
            Intent(context, MapActivity::class.java)
    }
}
