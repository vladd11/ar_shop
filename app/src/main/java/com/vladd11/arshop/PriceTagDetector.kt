package com.vladd11.arshop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Message
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.java.TfLite
import com.google.protobuf.ByteString
import com.vladd11.TestOuterClass
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.gms.vision.TfLiteVision
import org.tensorflow.lite.task.gms.vision.detector.ObjectDetector
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

class PriceTagDetector(context: Context) {
    companion object {
        private const val MAX_RESULTS = 5
        private const val TAG = "PriceTagDetector"

        private fun filterCoordinates(box: RectF, bitmap: Bitmap): Array<Int> {
            val x0 = filterCoordinate(box.left, bitmap.width)
            val y0 = filterCoordinate(box.top, bitmap.height)
            val x1 = filterCoordinate(box.right, bitmap.width)
            val y1 = filterCoordinate(box.bottom, bitmap.height)

            return arrayOf(x0, y0, x1, y1)
        }

        private fun filterCoordinate(coordinate: Float, maxCoordinate: Int): Int {
            if (coordinate < 0) {
                return 0
            } else if (coordinate > maxCoordinate) {
                return maxCoordinate
            }

            return coordinate.roundToInt()
        }
    }

    private val objectDetectorTask: Task<ObjectDetector>
    private var useGPU: Boolean = true

    init {
        objectDetectorTask = TfLiteVision.initialize(
            context,
            TfLiteInitializationOptions.builder().setEnableGpuDelegateSupport(true).build()
        ).continueWithTask {
            if (it.exception != null) {
                useGPU = false
                return@continueWithTask TfLite.initialize(context)
            } else {
                return@continueWithTask Tasks.forResult<Void>(null)
            }
        }.continueWith {
            val builder = ObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(MAX_RESULTS)
            val options =
                if (useGPU) {
                    builder.setBaseOptions(BaseOptions.builder().useGpu().build()).build()
                } else builder.build()

            return@continueWith ObjectDetector.createFromFileAndOptions(
                context,
                "model.tflite",
                options
            )
        }
    }

    fun detect(bitmap: Bitmap) {
        Log.d(TAG, "detect")
        if (objectDetectorTask.isComplete) {
            val results = objectDetectorTask.result.detect(TensorImage.fromBitmap(bitmap))
            val bitmaps = mutableListOf<Bitmap>()

            results.forEach { result ->
                Log.d(TAG, "${result.categories[0].label} - ${result.categories[0].score}")
                if (result.categories[0].score > 0.5f) {
                    val box = filterCoordinates(result.boundingBox, bitmap)
                    bitmaps.add(
                        Bitmap.createBitmap(
                            bitmap,
                            box[0],
                            box[1],
                            abs(box[2] - box[0]),
                            abs(box[3] - box[1])
                        )
                    )
                }
            }

            thread {
                val url = URL("http://[fe80::1151:da13:da7d:d23a]:3000/")
                with(url.openConnection() as HttpURLConnection) {
                    setRequestProperty("Content-Type", "image/jpeg")
                    doOutput = true
                    requestMethod = "POST"

                    val builder = TestOuterClass.Test.newBuilder()
                    bitmaps.forEach { img ->
                        val bytes = ByteString.newOutput()
                        img.compress(Bitmap.CompressFormat.PNG, 90, bytes)
                        builder.addImages(bytes.toByteString())
                    }
                    builder.build().writeDelimitedTo(outputStream)
                    outputStream.close()

                    Log.d(TAG, "sent to server with $responseCode code")
                }
            }
        }
    }

    fun close() {
        objectDetectorTask.result?.close()
    }
}