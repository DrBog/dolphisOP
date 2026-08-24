package dev.autotapper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.autotapper.R
import dev.autotapper.core.Actuator
import dev.autotapper.core.Engine
import dev.autotapper.core.EngineListener
import dev.autotapper.core.Gate
import dev.autotapper.core.Gray
import dev.autotapper.core.MatchInfo
import dev.autotapper.core.Recipe
import dev.autotapper.core.ScreenCapture

/**
 * Owns the MediaProjection and runs the loop on a worker thread.
 *
 * It has to be a foreground service: from Android 10 a MediaProjection may only
 * be held by one, and from Android 14 the service must already be running with
 * type mediaProjection before the projection is created.
 */
class TapperService : Service(), EngineListener {

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var engine: Engine? = null
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    private var scaleX = 1f
    private var scaleY = 1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_START -> startLoop(intent)
        }
        return START_NOT_STICKY
    }

    private fun startLoop(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val recipeName = intent.getStringExtra(EXTRA_RECIPE) ?: return
        val loops = intent.getIntExtra(EXTRA_LOOPS, 1)
        val probeOnly = intent.getBooleanExtra(EXTRA_PROBE, false)
        val leadInMs = intent.getLongExtra(EXTRA_DELAY, 6000L)

        transcript.clear()
        previewPath = null
        startForegroundWithNotification()

        if (data == null) { emit("no screen-capture permission handed to the service"); stopEverything(); return }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data)
        if (proj == null) { emit("could not obtain MediaProjection"); stopEverything(); return }
        projection = proj
        // Required from API 34: a callback must be registered before creating the display.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopEverything() } }
        }, main)

        val metrics = displayMetrics()
        val cap = ScreenCapture(proj, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        cap.start()
        capture = cap

        val recipe = try {
            Recipe.load(this, recipeName)
        } catch (e: Exception) {
            emit("recipe '$recipeName' failed to load: ${e.message}"); stopEverything(); return
        }

        scaleX = metrics.widthPixels.toFloat() / recipe.refW
        scaleY = metrics.heightPixels.toFloat() / recipe.refH

        val actuator = object : Actuator {
            override fun capture(refW: Int, refH: Int): Gray? = cap.grab(refW, refH)
            override fun tap(refX: Int, refY: Int) {
                val svc = TapService.instance
                if (svc == null) { emit("accessibility service is not enabled - cannot tap"); return }
                svc.tap(refX * scaleX, refY * scaleY)
            }
        }

        val eng = Engine(recipe, actuator, this)
        engine = eng

        worker = Thread {
            try {
                // The capture consent dialog can only be answered from this app,
                // so at this instant WE are the foreground app - capturing now
                // photographs our own UI, not the game. Count down first and let
                // the user get back to the game.
                countdown(leadInMs)
                Thread.sleep(400)   // let the virtual display settle
                runLoop(eng, recipe, probeOnly, loops)
            } catch (ie: InterruptedException) {
                // Stop was pressed - unwind quietly.
            } catch (t: Throwable) {
                emit("error: ${t.javaClass.simpleName}: ${t.message}")
                onFinished("stopped on error", false)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun runLoop(eng: Engine, recipe: Recipe, probeOnly: Boolean, loops: Int) {
            if (probeOnly) {
                emit("probe: ${recipe.name} (threshold ${recipe.threshold}, min contrast ${recipe.minContrast})")
                emit("  foreground app: ${TapService.foregroundPackage ?: "unknown"}")
                val result = eng.probe()
                if (result == null) {
                    emit("  no frame captured - try again")
                } else {
                    val (frame, rows) = result
                    for ((gate, m) in rows) report(gate, m)
                    if (rows.all { it.second.contrast < 4f })
                        emit("  every region is flat - this is almost certainly not the game")
                    savePreview(frame, rows)?.let {
                        previewPath = it
                        sendBroadcast(Intent(ACTION_PREVIEW).setPackage(packageName)
                            .putExtra(EXTRA_LINE, it))
                    }
                }
                onFinished("probe complete", true)
            } else {
                eng.run(loops)
            }
    }

    /** Count down in the notification so the user can switch apps. */
    private fun countdown(totalMs: Long) {
        var left = totalMs
        while (left > 0) {
            val secs = (left + 999) / 1000
            notify("Switch to the game - capturing in ${secs}s")
            emit("  capturing in ${secs}s - switch to the game now")
            Thread.sleep(minOf(1000L, left))
            left -= 1000L
        }
        notify("Autotapper running")
    }

    /**
     * Render what the capture actually saw, with each gate's search region and
     * any match drawn on it. A picture of the wrong screen is worth more than a
     * column of zeroes.
     */
    private fun savePreview(frame: Gray, rows: List<Pair<Gate, MatchInfo>>): String? = try {
        val px = IntArray(frame.w * frame.h)
        for (i in px.indices) {
            val v = frame.px[i].toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bmp = Bitmap.createBitmap(px, frame.w, frame.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.GRAY }
        val hit = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.GREEN }
        val label = Paint().apply { color = Color.YELLOW; textSize = 34f; isAntiAlias = true }
        for ((gate, m) in rows) {
            canvas.drawRect(gate.roi[0].toFloat(), gate.roi[1].toFloat(),
                gate.roi[2].toFloat(), gate.roi[3].toFloat(), box)
            canvas.drawText("${gate.name} ${"%.2f".format(m.score)}",
                gate.roi[0].toFloat() + 6f, gate.roi[1].toFloat() - 8f, label)
            if (m.found) {
                canvas.drawRect(m.x.toFloat(), m.y.toFloat(),
                    (m.x + gate.template.w).toFloat(), (m.y + gate.template.h).toFloat(), hit)
            }
        }
        val out = java.io.File(cacheDir, "probe.png")
        java.io.FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        bmp.recycle()
        out.absolutePath
    } catch (t: Throwable) {
        emit("  preview failed: ${t.message}")
        null
    }

    private fun notify(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun report(gate: Gate, m: MatchInfo) {
        val mark = if (m.found) "YES" else if (m.score >= 0.82f) "low-contrast" else "no"
        val pt = gate.tapPoint(m.x, m.y)
        val where = pt?.let { "-> (${(it.first * scaleX).toInt()},${(it.second * scaleY).toInt()})" } ?: "(no tap)"
        emit("  ${gate.name.padEnd(16)} ${"%.3f".format(m.score)}  contrast ${"%.1f".format(m.contrast)}  $mark $where")
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return m
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Autotapper", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TapperService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = buildNotification("Tap Stop to end the loop")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TapperService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Autotapper")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_tapper)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun stopEverything() {
        engine?.stop()
        worker?.interrupt()
        capture?.stop(); capture = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun emit(line: String) {
        // Buffer as well as broadcast. The activity minimises itself so the game
        // can come forward, which unregisters its receiver - without a transcript
        // to replay, everything logged while it was away would be lost.
        transcript.add(line)
        sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_LINE, line))
    }

    override fun onLog(line: String) = emit(line)

    override fun onState(loop: Int, totalLoops: Int, step: String) {
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_LOOP, loop).putExtra(EXTRA_LOOPS, totalLoops).putExtra(EXTRA_STEP, step)
        )
    }

    override fun onFinished(reason: String, ok: Boolean) {
        emit(if (ok) "  $reason" else "  $reason")
        sendBroadcast(Intent(ACTION_DONE).setPackage(packageName).putExtra(EXTRA_LINE, reason))
        main.post { stopEverything() }
    }

    override fun onDestroy() {
        engine?.stop()
        capture?.stop()
        projection?.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.autotapper.START"
        const val ACTION_STOP = "dev.autotapper.STOP"
        const val ACTION_LOG = "dev.autotapper.LOG"
        const val ACTION_STATE = "dev.autotapper.STATE"
        const val ACTION_DONE = "dev.autotapper.DONE"
        const val ACTION_PREVIEW = "dev.autotapper.PREVIEW"
        const val EXTRA_DELAY = "delayMs"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_RECIPE = "recipe"
        const val EXTRA_LOOPS = "loops"
        const val EXTRA_PROBE = "probe"
        const val EXTRA_LINE = "line"
        const val EXTRA_LOOP = "loop"
        const val EXTRA_STEP = "step"
        /** Replayed by the activity when it comes back to the foreground. */
        val transcript: MutableList<String> =
            java.util.Collections.synchronizedList(ArrayList<String>())

        @Volatile
        var previewPath: String? = null

        private const val CHANNEL = "autotapper"
        private const val NOTIF_ID = 42
    }
}
