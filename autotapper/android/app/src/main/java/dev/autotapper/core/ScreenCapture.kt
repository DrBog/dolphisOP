package dev.autotapper.core

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection

/**
 * Screen capture via MediaProjection.
 *
 * Frames are converted straight from the RGBA buffer to greyscale - no Bitmap is
 * allocated per frame, which matters when this runs for hours.
 */
class ScreenCapture(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
) {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var rowBuf: ByteArray? = null
    private var last: Gray? = null
    private var frameBuf: Gray? = null
    private var scaledBuf: Gray? = null

    fun start() {
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
        display = projection.createVirtualDisplay(
            "autotapper",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null,
        )
    }

    /**
     * Latest frame as greyscale, scaled to the recipe's reference resolution.
     *
     * MediaProjection only produces a buffer when the screen content changes, so
     * acquireLatestImage() legitimately returns null on a still screen. Returning
     * the last good frame there is what keeps a gate waiting on a static screen
     * from starving and timing out.
     */
    fun grab(refW: Int, refH: Int): Gray? {
        val img: Image = reader?.acquireLatestImage() ?: return last
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val w = img.width
            val h = img.height
            // Refill a buffer we already own. Allocating a fresh w*h FloatArray per
            // frame was ~9.6MB a time, tens of MB/s sustained, straight into the
            // large-object heap while the system is also mirroring the display.
            var frame = frameBuf
            if (frame == null || frame.w != w || frame.h != h) {
                frame = Gray(w, h, FloatArray(w * h)); frameBuf = frame
            }
            val out = frame.px
            var row = rowBuf
            if (row == null || row.size < rowStride) { row = ByteArray(rowStride); rowBuf = row }

            var o = 0
            for (y in 0 until h) {
                buf.position(y * rowStride)
                val n = minOf(rowStride, buf.remaining())
                buf.get(row, 0, n)
                var i = 0
                for (x in 0 until w) {
                    val r = row[i].toInt() and 0xFF
                    val g = row[i + 1].toInt() and 0xFF
                    val b = row[i + 2].toInt() and 0xFF
                    out[o++] = Gray.R_W * r + Gray.G_W * g + Gray.B_W * b
                    i += pixStride
                }
            }
            val scaled = if (w == refW && h == refH) {
                frame
            } else {
                var sc = scaledBuf
                if (sc == null || sc.w != refW || sc.h != refH) {
                    sc = Gray(refW, refH, FloatArray(refW * refH)); scaledBuf = sc
                }
                frame.resizeInto(sc)
                sc
            }
            last = scaled
            return scaled
        } finally {
            img.close()
        }
    }

    fun stop() {
        display?.release(); display = null
        reader?.close(); reader = null
        last = null
        frameBuf = null
        scaledBuf = null
    }
}
