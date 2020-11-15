package com.anna.sent.soft

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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anna.sent.soft.MediaProjectionService.LocalBinder

class OverlayStarterActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSION = 1
        private const val REQUEST_MEDIA_PROJECTION = 2
    }

    lateinit var mService: MediaProjectionService
    var mBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startService(Intent(this, MediaProjectionService::class.java))

        if (intent.getBooleanExtra("fromService", false)) {
            val intent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            sendBroadcast(intent)
        }
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

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun startActivity(intent: Intent?) {
        super.startActivity(intent)
        overridePendingTransition(0, 0)
    }

    override fun startActivityForResult(intent: Intent?, requestCode: Int) {
        super.startActivityForResult(intent, requestCode)
        overridePendingTransition(0, 0)
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
                finish()
            }
        } else {
            if (service.hasMediaProjection) {
                Toast.makeText(this, "Tap Capture to take screenshot", Toast.LENGTH_LONG).show()
                finish()
            } else {
                checkMediaProjection()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PERMISSION) {
            mService.drawOverlay()
            finish()
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
        val mMediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mMediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    private val mConnection: ServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(
            className: ComponentName,
            service: IBinder
        ) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as LocalBinder
            mService = binder.service
            mBound = true

            checkOverlay(mService)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }
}
