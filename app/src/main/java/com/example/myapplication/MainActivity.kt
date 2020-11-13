package com.example.myapplication

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat.RGBA_8888
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.databinding.OverlayBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity(), View.OnClickListener {

    companion object {
        private const val REQUEST_PERMISSION = 1
        private const val REQUEST_MEDIA_PROJECTION = 2

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

    private var overlayView: View? = null
    private var mResultCode = 0
    private var mResultData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val binding = ActivityMainBinding.bind(findViewById(R.id.content))
        binding.start.setOnClickListener {
            if (overlayView != null) {
                return@setOnClickListener
            }
            if (!tryToDrawOverlay()) {
                requestOverlayPermission()
            }
        }
        binding.getPermission.setOnClickListener {
            // This initiates a prompt dialog for the user to confirm screen projection.
            val mMediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(
                mMediaProjectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
            )
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PERMISSION) {
            tryToDrawOverlay()
        } else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startService(Intent(this, MediaProjectionService::class.java))
            mResultCode = resultCode
            mResultData = data
        }
    }

    override fun onClick(v: View?) {
        TODO("Not yet implemented")
    }

    private fun tryToDrawOverlay() =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(
                applicationContext
            )
        ) {
            drawOverlay()
            true
        } else {
            false
        }

    private fun drawOverlay() =
        try {
            val windowManager =
                applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = layoutInflater.inflate(R.layout.overlay, null)
            val params = createOverlayParams(
                STATUS_START_POINT.x,
                STATUS_START_POINT.y,
                Gravity.TOP or Gravity.END
            )
            view.layoutParams = params
            windowManager.addView(view, params)
            overlayView = view
            val binding = OverlayBinding.bind(view)
            binding.capture.setOnClickListener {
                setUpVirtualDisplay()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

    private fun setUpVirtualDisplay() {
        try {
            val mMediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mMediaProjection =
                mMediaProjectionManager.getMediaProjection(mResultCode, mResultData!!)
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(metrics)
            val mScreenDensity = metrics.densityDpi
            val mImageReader = ImageReader.newInstance(720, 1024, RGBA_8888, 1)
            mMediaProjection.createVirtualDisplay(
                "ScreenCapture",
                720,
                1024,
                mScreenDensity,
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
