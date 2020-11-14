package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PixelFormat.RGBA_8888
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import com.example.myapplication.databinding.OverlayBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    private var mScreenWidth: Int = 0
    private var mScreenHeight: Int = 0

    private var overlayView: View? = null

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
                // setUpVirtualDisplay()
            }
        }

        if (hasOverlay) {
            val binding = OverlayBinding.bind(overlayView!!)
            binding.capture.text = if (hasMediaProjection) "Capture" else "Request permission"
        }

        return START_REDELIVER_INTENT
    }

    private fun startForeground() {
//        val activityIntent = Intent(this, MainActivity::class.java)
//        activityIntent.action = "stop"
//        val contentIntent =
//            PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT)

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
//                .setContentIntent(contentIntent)
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

    // Binder given to clients
    private val mBinder: IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: MediaProjectionService get() = this@MediaProjectionService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return mBinder
    }

    private fun drawOverlay() =
        try {
            val windowManager =
                applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = LayoutInflater.from(applicationContext).inflate(R.layout.overlay, null)
            val params = createOverlayParams(
                STATUS_START_POINT.x,
                STATUS_START_POINT.y,
                Gravity.TOP or Gravity.END
            )
            view.layoutParams = params
            windowManager.addView(view, params)
            overlayView = view
            val binding = OverlayBinding.bind(view)
            binding.capture.text = if (hasMediaProjection) "Capture" else "Request permission"
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
        } catch (e: Exception) {
            e.printStackTrace()
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
        try {
            val mImageReader = ImageReader.newInstance(720, 1024, RGBA_8888, 1)
            mMediaProjection!!.createVirtualDisplay(
                "ScreenCapture",
                720,
                1024,
                mScreenDensity!!,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(),
                null,
                null
            )
            createVirtualDisplay(mImageReader)
        } catch (t: Throwable) {
            Toast.makeText(applicationContext, t.toString(), Toast.LENGTH_LONG).show()
        }
    }

    private fun createVirtualDisplay(mImageReader: ImageReader) {
        mImageReader.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {

            override fun onImageAvailable(reader: ImageReader) {
                val mWidth = 720
                val mHeight = 1024

                var image: Image? = null
                var fos: FileOutputStream? = null
                var bitmap: Bitmap? = null
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        image = reader.acquireLatestImage()
                    }
                    if (image != null) {
                        var planes: Array<Image.Plane?> = arrayOfNulls<Image.Plane>(0)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            planes = image.getPlanes()
                        }
                        val buffer: ByteBuffer = planes[0]!!.getBuffer()
                        val pixelStride: Int = planes[0]!!.getPixelStride()
                        val rowStride: Int = planes[0]!!.getRowStride()
                        val rowPadding: Int = rowStride - pixelStride * mWidth

                        // create bitmap
                        //
                        bitmap = Bitmap.createBitmap(
                            mWidth + rowPadding / pixelStride,
                            mHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        //  bitmap = Bitmap.createBitmap(mImageReader.getWidth() + rowPadding / pixelStride,
                        //    mImageReader.getHeight(), Bitmap.Config.ARGB_8888);
                        bitmap.copyPixelsFromBuffer(buffer)

                        // write bitmap to a file
                        val df = SimpleDateFormat("dd-MM-yyyy_HH:mm:ss.sss")
                        val formattedDate: String =
                            df.format(Calendar.getInstance().getTime()).trim()
                        val finalDate = formattedDate.replace(":", "-")
                        val imgName: String = "img_" + finalDate + ".jpg"
                        val mPath: String =
                            applicationContext.getExternalFilesDir(null)?.absolutePath + "/" + imgName
                        val imageFile = File(mPath)
                        fos = FileOutputStream(imageFile)
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)

                        if (overlayView != null) {
                            val binding = OverlayBinding.bind(overlayView!!)
                            binding.image.setImageDrawable(Drawable.createFromPath(mPath))
                        }

                        // stopProjection()
                    }
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                } finally {
                    if (fos != null) {
                        try {
                            fos.close()
                        } catch (ioe: IOException) {
                            ioe.printStackTrace()
                        }
                    }
                    bitmap?.recycle()
                    if (image != null) {
                        image.close()
                    }
                    mImageReader.close()
                }
            }
        }, null)
    }
}
