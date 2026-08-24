package dev.autotapper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
                // Give the virtual display a moment to produce its first frame.
                Thread.sleep(600)
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
                val rows = eng.probe()
                if (rows.isEmpty()) emit("  no frame captured yet - try again")
                for ((gate, m) in rows) report(gate, m)
                onFinished("probe complete", true)
            } else {
                eng.run(loops)
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
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Autotapper running")
            .setContentText("Tap Stop to end the loop")
            .setSmallIcon(R.drawable.ic_stat_tapper)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
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
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_RECIPE = "recipe"
        const val EXTRA_LOOPS = "loops"
        const val EXTRA_PROBE = "probe"
        const val EXTRA_LINE = "line"
        const val EXTRA_LOOP = "loop"
        const val EXTRA_STEP = "step"
        private const val CHANNEL = "autotapper"
        private const val NOTIF_ID = 42
    }
}
