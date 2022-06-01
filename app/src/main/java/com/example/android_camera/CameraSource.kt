package com.example.android_camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.renderscript.Toolkit
import com.google.android.renderscript.YuvFormat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraSource(
    private val context: Context,
    private val listener: CameraSourceListener) {
    companion object {
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 480
        private const val TAG = "Camera Source"
    }

    /** Frame count that have been processed so far in an one second interval to calculate FPS. */
    private var fpsTimer: Timer? = null
    private var framesCount = 0
    private var framesPerSecond = 0

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** Readers used as buffers for camera still shots */
    private var imageReader: ImageReader? = null

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private var session: CameraCaptureSession? = null

    /** [HandlerThread] where all buffer reading operations run */
    private var imageReaderThread: HandlerThread? = null
    /** [Handler] corresponding to [imageReaderThread] */
    private var imageReaderHandler: Handler? = null
    /** The [CameraDevice] that will be opened in this fragment */
    private var camera: CameraDevice? = null
    private var cameraId: String = ""
    private val cameraFacing = CameraCharacteristics.LENS_FACING_FRONT

    /** open the camera */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(manager: CameraManager, cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera)

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (cont.isActive) cont.resumeWithException(Exception("Camera error"))
                }
            }, imageReaderHandler)
        }

    suspend fun initCamera() {
        Log.d("TAG", "initCamera: ")
        imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
        imageReaderHandler = Handler(imageReaderThread!!.looper)
        fpsTimer = Timer()
        fpsTimer?.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    framesPerSecond = framesCount
                    listener.onFPSListener(framesPerSecond)
                    framesCount = 0
                }
            }, 0, 1000)

        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (cameraDirection != null && cameraDirection == cameraFacing) {
                this.cameraId = cameraId
                break
            }
        }

        camera = openCamera(cameraManager, cameraId)
        imageReader = ImageReader.newInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()

            if (image != null) {
                val imageBA = imageToByteArray(image)
                var imageBitmap = Toolkit.yuvToRgbBitmap(imageBA, image.cropRect.width(), image.cropRect.height(), YuvFormat.NV21)

                // Create rotated version for portrait display
                val transMatrix = Matrix()
                if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT)
                    transMatrix.postScale(-1f, 1f)
                transMatrix.postRotate(90.0f)

                imageBitmap = Bitmap.createBitmap(
                    imageBitmap, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    transMatrix, false
                )
                listener.processImage(imageBitmap)
                framesCount++
                image.close()
            }

        }, imageReaderHandler)

        imageReader?.surface?.let { surface ->
            val sessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)),
                ContextCompat.getMainExecutor(context),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        val cameraRequest = camera?.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                        )?.apply {
                            addTarget(surface)
                        }

                        cameraRequest?.build()?.let { session?.setRepeatingRequest(it, null, null) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.d(TAG, "onConfigureFailed")
                    }
                })
            camera?.createCaptureSession(sessionConfiguration)
        }
    }

    fun closeCamera() {
        session?.close()
        session = null
        camera?.close()
        camera = null
        imageReader?.close()
        imageReader = null
        stopImageReaderThread()
        fpsTimer?.cancel()
        fpsTimer = null
        framesPerSecond = 0
        framesCount = 0
    }

    private fun imageToByteArray(image: Image): ByteArray {
        assert(image.format == ImageFormat.YUV_420_888)
        val pixelCount: Int = image.cropRect.width() * image.cropRect.height()
        val imageCrop = image.cropRect
        val imagePlanes = image.planes
        val rowData = ByteArray(imagePlanes.first().rowStride)
        val outputBuffer = ByteArray((image.cropRect.width() * image.cropRect.height() * 1.5).toInt())
        imagePlanes.forEachIndexed { planeIndex, plane ->
            // How many values are read in input for each output value written
            // Only the Y plane has a value for every pixel, U and V have half the resolution i.e.
            //
            // Y Plane            U Plane    V Plane
            // ===============    =======    =======
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            val outputStride: Int

            // The index in the output buffer the next value will be written at
            // For Y it's zero, for U and V we start at the end of Y and interleave them i.e.
            //
            // First chunk        Second chunk
            // ===============    ===============
            // Y Y Y Y Y Y Y Y    U V U V U V U V
            // Y Y Y Y Y Y Y Y    U V U V U V U V
            // Y Y Y Y Y Y Y Y    U V U V U V U V
            // Y Y Y Y Y Y Y Y    U V U V U V U V
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            var outputOffset: Int

            when (planeIndex) {
                0 -> {
                    outputStride = 1
                    outputOffset = 0
                }
                1 -> {
                    outputStride = 2
                    outputOffset = pixelCount + 1
                }
                2 -> {
                    outputStride = 2
                    outputOffset = pixelCount
                }
                else -> {
                    // Image contains more than 3 planes, something strange is going on
                    return@forEachIndexed
                }
            }

            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // We have to divide the width and height by two if it's not the Y plane
            val planeCrop = if (planeIndex == 0) {
                imageCrop
            } else {
                Rect(
                    imageCrop.left / 2,
                    imageCrop.top / 2,
                    imageCrop.right / 2,
                    imageCrop.bottom / 2
                )
            }

            val planeWidth = planeCrop.width()
            val planeHeight = planeCrop.height()

            buffer.position(rowStride * planeCrop.top + pixelStride * planeCrop.left)
            for (row in 0 until planeHeight) {
                val length: Int
                if (pixelStride == 1 && outputStride == 1) {
                    // When there is a single stride value for pixel and output, we can just copy
                    // the entire row in a single step
                    length = planeWidth
                    buffer.get(outputBuffer, outputOffset, length)
                    outputOffset += length
                } else {
                    // When either pixel or output have a stride > 1 we must copy pixel by pixel
                    length = (planeWidth - 1) * pixelStride + 1
                    buffer.get(rowData, 0, length)
                    for (col in 0 until planeWidth) {
                        outputBuffer[outputOffset] = rowData[col * pixelStride]
                        outputOffset += outputStride
                    }
                }

                if (row < planeHeight - 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
            }
        }
        return outputBuffer
    }

    private fun stopImageReaderThread() {
        imageReaderThread?.quitSafely()
        try {
            imageReaderThread?.join()
            imageReaderThread = null
            imageReaderHandler = null
        } catch (e: InterruptedException) {
            Log.d(TAG, e.message.toString())
        }
    }

    interface CameraSourceListener {
        fun processImage(image: Bitmap)
        fun onFPSListener(fps: Int)
    }
}