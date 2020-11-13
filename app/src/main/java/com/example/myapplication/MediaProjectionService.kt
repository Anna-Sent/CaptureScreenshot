package com.example.myapplication

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder


class MediaProjectionService : Service() {

    private var mResultCode = 0
    private var mResultData: Intent? = null
    private var mScreenWidth: Int = 0
    private var mScreenHeight: Int = 0
    private var mScreenDensity: Int = 0
    private lateinit var mMediaProjection: MediaProjection
    private lateinit var mVirtualDisplay: VirtualDisplay

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()

        mResultCode = intent!!.getIntExtra("code", -1);
        mResultData = intent.getParcelableExtra("data");
        mScreenWidth = intent.getIntExtra("width", 720);
        mScreenHeight = intent.getIntExtra("height", 1280);


//        if (mResultData != null) {
//            val mMediaProjectionManager =
//                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
//            mMediaProjection =
//                mMediaProjectionManager.getMediaProjection(mResultCode, mResultData!!)
//            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
//                "ScreenCapture",
//                mScreenWidth,
//                mScreenHeight,
//                300,
//                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
//                null,
//                null,
//                null
//            )
//        }

        return START_REDELIVER_INTENT
    }

    private fun startForeground() {
        val activityIntent = Intent(this, MainActivity::class.java)
        activityIntent.action = "stop"
        val contentIntent =
            PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "001"
            val channelName = "myChannel"
            val channel =
                NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE)
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            val notification: Notification = Notification.Builder(applicationContext, channelId)
                .setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentTitle("Cancel")
                .setContentIntent(contentIntent)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    11,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(11, notification)
            }
        } else {
            startForeground(11, Notification())
        }
    }
}
