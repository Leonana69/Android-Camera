package com.example.android_camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var previewSV: SurfaceView? = null
    private var cameraSource: CameraSource? = null
    private var psv = PreviewSurfaceView()

    private val job = SupervisorJob()
    private val mCoroutineScope = CoroutineScope(Dispatchers.IO + job)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewSV = findViewById(R.id.sv_preview)
        requestPermission()
    }

    override fun onStart() {
        super.onStart()
        cameraSource = CameraSource(this, object: CameraSource.CameraSourceListener {
            override fun processImage(image: Bitmap) {
                psv.setPreviewSurfaceView(image)
            }
            override fun onFPSListener(fps: Int) {}
        })
        mCoroutineScope.launch {
            cameraSource?.initCamera()
        }
    }

    /** request permission */
    private fun requestPermission() {
        /** request camera permission */
        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (!isGranted) {
                    Toast.makeText(this, "Request camera permission failed", Toast.LENGTH_SHORT).show()
                }
            }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    inner class PreviewSurfaceView {
        private var left: Int = 0
        private var top: Int = 0
        private var right: Int = 0
        private var bottom: Int = 0
        private var defaultImageWidth: Int = 0
        private var defaultImageHeight: Int = 0
        fun setPreviewSurfaceView(image: Bitmap) {
            val holder = previewSV?.holder
            val surfaceCanvas = holder?.lockCanvas()
            surfaceCanvas?.let { canvas ->
                if (defaultImageWidth != image.width || defaultImageHeight != image.height) {
                    defaultImageWidth = image.width
                    defaultImageHeight = image.height
                    val screenWidth: Int
                    val screenHeight: Int

                    if (canvas.height > canvas.width) {
                        val ratio = image.height.toFloat() / image.width
                        screenWidth = canvas.width
                        left = 0
                        screenHeight = (canvas.width * ratio).toInt()
                        top = (canvas.height - screenHeight) / 2
                    } else {
                        val ratio = image.width.toFloat() / image.height
                        screenHeight = canvas.height
                        top = 0
                        screenWidth = (canvas.height * ratio).toInt()
                        left = (canvas.width - screenWidth) / 2
                    }
                    right = left + screenWidth
                    bottom = top + screenHeight
                }

                canvas.drawBitmap(
                    image, Rect(0, 0, image.width, image.height),
                    Rect(left, top, right, bottom), null)
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}