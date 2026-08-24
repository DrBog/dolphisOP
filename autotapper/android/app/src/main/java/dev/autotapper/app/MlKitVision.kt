package dev.autotapper.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.autotapper.core.Gray
import dev.autotapper.core.TextVision
import dev.autotapper.core.Word
import java.util.concurrent.TimeUnit

/**
 * On-device text recognition. The Latin model is bundled in the APK, so this
 * works offline, costs nothing per call, and no screenshot leaves the phone.
 */
class MlKitVision : TextVision {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Reused across calls: a fresh IntArray plus an ARGB_8888 Bitmap at this size
    // is ~19MB a time.
    private var px: IntArray? = null
    private var bmp: Bitmap? = null

    override fun read(frame: Gray): List<Word>? = try {
        var buf = px
        if (buf == null || buf.size != frame.w * frame.h) {
            buf = IntArray(frame.w * frame.h); px = buf
        }
        for (i in buf.indices) {
            val v = frame.px[i].toInt().coerceIn(0, 255)
            buf[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        var image = bmp
        if (image == null || image.width != frame.w || image.height != frame.h) {
            image?.recycle()
            image = Bitmap.createBitmap(frame.w, frame.h, Bitmap.Config.ARGB_8888)
            bmp = image
        }
        image.setPixels(buf, 0, frame.w, 0, 0, frame.w, frame.h)
        val text = Tasks.await(
            recognizer.process(InputImage.fromBitmap(image, 0)), 8, TimeUnit.SECONDS
        )
        val out = ArrayList<Word>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    out.add(Word(el.text, b.centerX(), b.centerY(), b.width(), b.height()))
                }
            }
        }
        out
    } catch (t: Throwable) {
        null
    }
}
