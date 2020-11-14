package com.example.myapplication

import android.annotation.TargetApi
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

class OverlayStarterActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSION = 1
        private const val REQUEST_MEDIA_PROJECTION = 2
    }

    lateinit var mService: MediaProjectionService
    var mBound = false

    val checkMedia
        get() = intent.getBooleanExtra("checkMedia", false)
                || intent.action == "android.intent.action.MAIN"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startService(Intent(this, MediaProjectionService::class.java))
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MediaProjectionService::class.java)
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (mBound) {
            unbindService(mConnection)
            mBound = false
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun requestOverlayPermission() {
        startActivityForResult(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${applicationContext.packageName}")
            ),
            REQUEST_PERMISSION
        )
    }

    private fun checkOverlay(service: MediaProjectionService) {
        if (!service.hasOverlay) {
            val hasPermission = service.tryToDrawOverlay()
            if (!hasPermission) {
                requestOverlayPermission()
            } else {
                checkMediaProjection()
            }
        } else {
            checkMediaProjection()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PERMISSION) {
            mService.tryToDrawOverlay()

            checkMediaProjection()
        } else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            startService(
                Intent(this, MediaProjectionService::class.java)
                    .setAction("init")
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
            )
            finish()
        }
    }

    private fun checkMediaProjection() {
        if (checkMedia) {
            val mMediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(
                mMediaProjectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
            )
        } else {
            finish()
        }
    }

    private val mConnection: ServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(
            className: ComponentName,
            service: IBinder
        ) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as MediaProjectionService.LocalBinder
            mService = binder.service
            mBound = true

            checkOverlay(mService)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }
}
