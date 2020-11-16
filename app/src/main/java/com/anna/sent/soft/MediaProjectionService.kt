package com.anna.sent.soft

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat.JPEG
import android.graphics.Bitmap.Config
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Audio
import android.provider.MediaStore.Images.Media
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.anna.sent.soft.databinding.OverlayBinding
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*

class MediaProjectionService : Service() {

    companion object {
        @Suppress("MagicNumber")
        private val STATUS_START_POINT = Point(24, 8)

        @Suppress("SameParameterValue")
        private fun createOverlayParams(x: Int, y: Int, gravity: Int) =
            WindowManager.LayoutParams().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("deprecation")
                    type = WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                format = PixelFormat.RGBA_8888
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                this.x = x
                this.y = y
                this.gravity = gravity
            }
    }

    private var mResultCode = 0
    private var mResultData: Intent? = null

    private var overlayView: View? = null
    private var overlayBinding: OverlayBinding? = null

    val hasOverlay get() = overlayView != null
    val hasMediaProjection get() = mResultData != null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()

        if (intent?.action == "init") {
            mResultCode = intent.getIntExtra("code", -1)
            mResultData = intent.getParcelableExtra("data")

            if (mResultData != null) {
                init()
            }
        } else if (intent?.action == "stop") {
            stopSelf()
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }

        if (hasOverlay) {
            val binding = OverlayBinding.bind(overlayView!!)
            binding.capture.text = getString(
                if (hasMediaProjection) R.string.capture else R.string.permission_needed
            )
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()

        overlayView?.let { windowManager.removeView(it) }
    }

    private fun startForeground() {
        val serviceIntent = Intent(this, MediaProjectionService::class.java)
        serviceIntent.action = "stop"
        val stopIntent =
            PendingIntent.getService(this, 0, serviceIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val activityIntent = Intent(this, OverlayStarterActivity::class.java)
            .putExtra("fromService", true)
        val contentIntent =
            PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val appName = getString(R.string.app_name)
        val notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel =
                    NotificationChannel(appName, appName, NotificationManager.IMPORTANCE_NONE)
                channel.lightColor = Color.BLUE
                channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
                Notification.Builder(this, appName)
            } else {
                @Suppress("deprecation")
                Notification.Builder(this)
            }
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_camera)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentTitle(appName)
                .setContentIntent(contentIntent)
                .addAction(
                    run {
                        val stop = getString(R.string.stop)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Notification.Action.Builder(
                                Icon.createWithResource(
                                    this,
                                    R.drawable.ic_stop
                                ),
                                stop,
                                stopIntent
                            )
                        } else {
                            @Suppress("deprecation")
                            Notification.Action.Builder(
                                R.drawable.ic_stop,
                                stop,
                                stopIntent
                            )
                        }.build()
                    }
                )
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
    }

    private val mBinder: IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: MediaProjectionService get() = this@MediaProjectionService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return mBinder
    }

    fun drawOverlay() =
        try {
            @SuppressLint("InflateParams")
            val view = LayoutInflater.from(this).inflate(R.layout.overlay, null)
            val params = createOverlayParams(
                STATUS_START_POINT.x,
                STATUS_START_POINT.y,
                Gravity.TOP or Gravity.END
            )
            view.layoutParams = params
            windowManager.addView(view, params)
            overlayView = view
            val binding = OverlayBinding.bind(view)
            overlayBinding = binding
            binding.capture.text = getString(
                if (hasMediaProjection) R.string.capture else R.string.permission_needed
            )
            binding.capture.setOnClickListener {
                if (mResultData == null) {
                    startActivity(
                        Intent(
                            this,
                            OverlayStarterActivity::class.java
                        )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    setUpVirtualDisplay()
                }
            }
            binding.image.setOnClickListener { v ->
                (v.tag as? Uri)?.let { showPhoto(it) }
            }
        } catch (throwable: Throwable) {
            showError(R.string.failed_to_draw_overlay, throwable)
        }

    fun tryToDrawOverlay() =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            drawOverlay()
            true
        } else {
            false
        }

    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mMediaProjection: MediaProjection? = null

    private fun init() {
        mMediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mMediaProjection =
            mMediaProjectionManager!!.getMediaProjection(mResultCode, mResultData!!)
    }

    @SuppressLint("WrongConstant")
    private fun setUpVirtualDisplay() {
        overlayView?.isVisible = false
        Handler(Looper.getMainLooper()).post {
            try {
                val width = screenWidth
                val height = screenHeight
                val mImageReader =
                    ImageReader.newInstance(
                        width,
                        height,
                        PixelFormat.RGBA_8888,
                        1
                    )
                mMediaProjection!!.createVirtualDisplay(
                    "CaptureScreen",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.surface,
                    null,
                    null
                )
                mImageReader.setOnImageAvailableListener({ reader ->
                    onImageAvailable(reader)
                }, null)
            } catch (throwable: Throwable) {
                overlayView?.isVisible = true
                showError(R.string.failed_to_take_screenshot, throwable)
            }
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        try {
            val bitmap = createBitmap(reader)

            val imageFile = createCompressedImageFile(bitmap)

            val uri = writeImageFileToMediaStore(imageFile)

            if (overlayBinding != null) {
                overlayBinding!!.image.setImageURI(uri)
                overlayBinding!!.image.tag = uri
            }
        } catch (throwable: Throwable) {
            showError(R.string.failed_to_save_screenshot, throwable)
        } finally {
            overlayView?.isVisible = true
            execSafely { reader.close() }
        }
    }

    private fun showPhoto(photoUri: Uri) {
        try {
            startActivity(
                Intent()
                    .setAction(Intent.ACTION_VIEW)
                    .setDataAndType(photoUri, "image/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (throwable: Throwable) {
            showError(R.string.failed_to_view_image, throwable)
        }
    }

    private fun createBitmap(reader: ImageReader): Bitmap {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()

            val width = image.width
            val height = image.height

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height,
                Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        } finally {
            image?.close()
        }
    }

    private fun createCompressedImageFile(bitmap: Bitmap): File {
        try {
            val df = SimpleDateFormat("yyyyMMdd-HHmmss.sss", Locale.US)
            val formattedDate = df.format(System.currentTimeMillis())
            val imgName = "Screenshot_$formattedDate.jpg"
            val imageFile = File(getTmpDir(), imgName)
            FileOutputStream(imageFile).use {
                bitmap.compress(JPEG, 100, it)
            }
            return imageFile
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeImageFileToMediaStore(imageFile: File): Uri {
        try {
            val resolver = contentResolver
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection =
                    Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val details = ContentValues().apply {
                    put(Media.DISPLAY_NAME, imageFile.name)
                    put(Media.RELATIVE_PATH, "Pictures/Screenshots")
                    put(Audio.Media.IS_PENDING, 1)
                }
                resolver.insert(collection, details)!!.apply {
                    resolver.openFileDescriptor(this, "w", null).use { pfd ->
                        Files.copy(Paths.get(imageFile.path), FileOutputStream(pfd?.fileDescriptor))
                    }
                    details.clear()
                    details.put(Audio.Media.IS_PENDING, 0)
                    resolver.update(this, details, null, null)
                }
            } else {
                @Suppress("deprecation")
                Uri.parse(
                    Media.insertImage(
                        resolver,
                        imageFile.path,
                        imageFile.name,
                        imageFile.name
                    )
                )
            }
        } finally {
            imageFile.delete()
        }
    }

    private fun showError(@StringRes messageId: Int, throwable: Throwable) {
        Toast.makeText(
            this,
            getString(messageId) + (if (BuildConfig.DEBUG) "\n\n$throwable" else ""),
            Toast.LENGTH_LONG
        )
            .show()
        throwable.printStackTrace()
    }

    private inline fun execSafely(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
        }
    }

    private fun getTmpDir() = externalCacheDir ?: cacheDir

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val screenWidth by lazy { displayMetrics.widthPixels }

    private val screenHeight by lazy { displayMetrics.heightPixels }

    private val displayMetrics by lazy {
        DisplayMetrics().apply {
            screenDisplay.getRealMetrics(this)
        }
    }

    @Suppress("deprecation")
    private val screenDisplay by lazy { windowManager.defaultDisplay }

    private val densityDpi get() = displayMetrics.densityDpi
}
