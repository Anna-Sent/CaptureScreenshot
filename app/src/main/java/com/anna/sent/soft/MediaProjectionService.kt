package com.anna.sent.soft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat.RGBA_8888
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import com.anna.sent.soft.R.layout
import com.anna.sent.soft.databinding.OverlayBinding
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class MediaProjectionService : Service() {

    companion object {
        @Suppress("MagicNumber")
        private val STATUS_START_POINT = Point(24, 8)

        private fun createOverlayParams(x: Int, y: Int, gravity: Int) =
            WindowManager.LayoutParams().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("deprecation")
                    type = WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                format = RGBA_8888
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                this.x = x
                this.y = y
                this.gravity = gravity
            }
    }

    private var mResultCode = 0
    private var mResultData: Intent? = null
    private var mScreenWidth: Int = 0
    private var mScreenHeight: Int = 0

    private var overlayView: View? = null
    private var overlayBinding: OverlayBinding? = null

    val hasOverlay get() = overlayView != null
    val hasMediaProjection get() = mResultData != null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()

        if (intent?.action == "init") {
            mResultCode = intent.getIntExtra("code", -1);
            mResultData = intent.getParcelableExtra("data");
            mScreenWidth = intent.getIntExtra("width", 720);
            mScreenHeight = intent.getIntExtra("height", 1280);

            if (mResultData != null) {
                init()
            }
        } else if (intent?.action == "stop") {
            stopSelf()
        }

        if (hasOverlay) {
            val binding = OverlayBinding.bind(overlayView!!)
            binding.capture.text = applicationContext.getString(
                if (hasMediaProjection) R.string.capture else R.string.permission_needed
            )
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()

        val windowManager =
            applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

        val appName = applicationContext.getString(R.string.app_name)
        val notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel =
                    NotificationChannel(appName, appName, NotificationManager.IMPORTANCE_NONE)
                channel.lightColor = Color.BLUE
                channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
                Notification.Builder(applicationContext, appName)
            } else {
                @Suppress("deprecation")
                Notification.Builder(applicationContext)
            }
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_camera)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentTitle(appName)
                .setContentIntent(contentIntent)
                .addAction(
                    run {
                        val stop = applicationContext.getString(R.string.stop)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Notification.Action.Builder(
                                Icon.createWithResource(
                                    applicationContext,
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

    // Binder given to clients
    private val mBinder: IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: MediaProjectionService get() = this@MediaProjectionService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return mBinder
    }

    fun drawOverlay() =
        try {
            val windowManager =
                applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = LayoutInflater.from(applicationContext).inflate(layout.overlay, null)
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
            binding.capture.text = applicationContext.getString(
                if (hasMediaProjection) R.string.capture else R.string.permission_needed
            )
            binding.capture.setOnClickListener {
                if (mResultData == null) {
                    // request permission
                    applicationContext.startActivity(
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
        } catch (throwable: Throwable) {
            showError(R.string.failed_to_draw_overlay, throwable)
        }

    fun tryToDrawOverlay() =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(
                applicationContext
            )
        ) {
            drawOverlay()
            true
        } else {
            false
        }

    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mMediaProjection: MediaProjection? = null
    private var mScreenDensity: Int? = null

    private fun init() {
        mMediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mMediaProjection =
            mMediaProjectionManager!!.getMediaProjection(mResultCode, mResultData!!)
        val metrics = DisplayMetrics()
        (applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(
            metrics
        )
        mScreenDensity = metrics.densityDpi
    }

    private fun setUpVirtualDisplay() {
        overlayView?.isVisible = false
        Handler(Looper.getMainLooper()).post {
            try {
                val mImageReader = ImageReader.newInstance(720, 1024, RGBA_8888, 1)
                mMediaProjection!!.createVirtualDisplay(
                    "CaptureScreen",
                    720,
                    1024,
                    mScreenDensity!!,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(),
                    null,
                    null
                )
                createVirtualDisplay(mImageReader)
            } catch (throwable: Throwable) {
                overlayView?.isVisible = true
                showError(R.string.failed_to_take_screenshot, throwable)
            }
        }
    }

    private fun createVirtualDisplay(mImageReader: ImageReader) {
        mImageReader.setOnImageAvailableListener({ reader ->
            val mWidth = 720
            val mHeight = 1024

            var image: Image? = null
            var fos: FileOutputStream? = null
            var bitmap: Bitmap? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    var planes: Array<Image.Plane?> = arrayOfNulls<Image.Plane>(0)
                    planes = image.getPlanes()
                    val buffer: ByteBuffer = planes[0]!!.getBuffer()
                    val pixelStride: Int = planes[0]!!.getPixelStride()
                    val rowStride: Int = planes[0]!!.getRowStride()
                    val rowPadding: Int = rowStride - pixelStride * mWidth

                    bitmap = Bitmap.createBitmap(
                        mWidth + rowPadding / pixelStride,
                        mHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val df = SimpleDateFormat("yyyyMMdd-HHmmss.sss")
                    val formattedDate: String =
                        df.format(Calendar.getInstance().getTime()).trim()
                    val finalDate = formattedDate//.replace(":", "")
                    val imgName: String = "Screenshot_" + finalDate + ".jpg"
                    val mPath: String =
                        getAppSpecificAlbumStorageDir(
                            applicationContext,
                            "Screnshots"
                        )?.absolutePath + "/" + imgName
                    val imageFile = File(mPath)
                    fos = FileOutputStream(imageFile)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)

                    if (overlayBinding != null) {
                        overlayBinding!!.image.setImageDrawable(Drawable.createFromPath(mPath))
                    }
                }
            } catch (throwable: Throwable) {
                showError(R.string.failed_to_save_screenshot, throwable)
            } finally {
                overlayView?.isVisible = true
                execSafely { fos?.close() }
                execSafely { bitmap?.recycle() }
                execSafely { image?.close() }
                execSafely { mImageReader.close() }
            }
        }, null)
    }

    private fun showError(@StringRes messageId: Int, throwable: Throwable) {
        Toast.makeText(
            applicationContext,
            applicationContext.getString(messageId) + (if (BuildConfig.DEBUG) "\n\n${throwable}" else ""),
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

    private fun getAppSpecificAlbumStorageDir(context: Context, albumName: String): File? {
        // Get the pictures directory that's inside the app-specific directory on
        // external storage.
        val file = File(
            context.getExternalFilesDir(
                Environment.DIRECTORY_PICTURES
            ), albumName
        )
        file.mkdirs()
        return file
    }
}
