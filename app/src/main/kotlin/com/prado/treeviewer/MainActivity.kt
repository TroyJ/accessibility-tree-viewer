package com.prado.treeviewer

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var overlayStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.statusText).text = "Accessibility Tree Viewer\n${BuildConfig.VERSION_DISPLAY}"

        overlayStatus = findViewById(R.id.overlayStatus)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnAccessibility = findViewById(R.id.btnAccessibility)

        btnOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        overlayStatus.text = if (hasOverlay) {
            "Overlay permission: GRANTED"
        } else {
            "Overlay permission: NOT GRANTED"
        }
        btnOverlay.isEnabled = !hasOverlay

        accessibilityStatus.text = if (hasAccessibility) {
            "Accessibility service: ENABLED"
        } else {
            "Accessibility service: NOT ENABLED"
        }
        btnAccessibility.isEnabled = !hasAccessibility

        if (hasOverlay && hasAccessibility) {
            overlayStatus.text = "Overlay permission: GRANTED"
            accessibilityStatus.text = "Accessibility service: ENABLED\n\nOverlay is active. You can close this app."
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        val myServiceName = "$packageName/.TreeOverlayService"
        return enabledServices.any {
            it.resolveInfo.serviceInfo.let { si ->
                "${si.packageName}/.${si.name.substringAfterLast('.')}" == myServiceName
                    || "${si.packageName}/${si.name}" == "$packageName/com.prado.treeviewer.TreeOverlayService"
            }
        }
    }
}
