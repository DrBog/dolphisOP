package dev.autotapper.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import dev.autotapper.core.Recipe
import dev.autotapper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private var pendingProbe = false
    private var haveRecipe = false

    private val projectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            log("screen capture was declined")
            return@registerForActivityResult
        }
        val i = Intent(this, TapperService::class.java).apply {
            action = TapperService.ACTION_START
            putExtra(TapperService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(TapperService.EXTRA_RESULT_DATA, result.data)
            putExtra(TapperService.EXTRA_RECIPE, selectedRecipe())
            putExtra(TapperService.EXTRA_LOOPS, loops())
            putExtra(TapperService.EXTRA_PROBE, pendingProbe)
        }
        ui.log.text = ""
        startForegroundService(i)
        setRunning(true)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                TapperService.ACTION_LOG ->
                    log(i.getStringExtra(TapperService.EXTRA_LINE) ?: return)
                TapperService.ACTION_STATE -> {
                    val loop = i.getIntExtra(TapperService.EXTRA_LOOP, 0)
                    val total = i.getIntExtra(TapperService.EXTRA_LOOPS, 0)
                    val step = i.getStringExtra(TapperService.EXTRA_STEP) ?: ""
                    ui.status.text = "loop $loop/$total — $step"
                }
                TapperService.ACTION_DONE -> {
                    ui.status.text = i.getStringExtra(TapperService.EXTRA_LINE) ?: "finished"
                    setRunning(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        val recipes = Recipe.listAvailable(this)
        haveRecipe = recipes.isNotEmpty()
        ui.recipe.text = recipes.firstOrNull() ?: "no recipes bundled"

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7
            )
        }

        ui.accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        ui.probeBtn.setOnClickListener { launch(probe = true) }
        ui.startBtn.setOnClickListener { launch(probe = false) }
        ui.stopBtn.setOnClickListener {
            startService(Intent(this, TapperService::class.java).setAction(TapperService.ACTION_STOP))
            setRunning(false)
        }
        setRunning(false)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(TapperService.ACTION_LOG)
            addAction(TapperService.ACTION_STATE)
            addAction(TapperService.ACTION_DONE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, f)
        }
        refreshAccessibilityState()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(receiver) }
    }

    private fun refreshAccessibilityState() {
        val on = isAccessibilityEnabled()
        ui.accessibilityState.text = if (on)
            "Tap injection: enabled"
        else
            "Tap injection: OFF — enable “Autotapper” under Accessibility, or it can see but not tap"
        ui.accessibilityBtn.isEnabled = !on
    }

    /**
     * Read the enabled-services setting directly. TapService.instance is only set
     * once the system has bound the service, which has not happened yet the first
     * time the user comes back from Settings.
     */
    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = ComponentName(this, TapService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        for (part in splitter) {
            val c = ComponentName.unflattenFromString(part) ?: continue
            if (c == target) return true
        }
        return false
    }

    private fun selectedRecipe(): String = ui.recipe.text.toString()

    private fun loops(): Int = ui.loops.text.toString().trim().toIntOrNull()?.coerceIn(1, 100000) ?: 1

    private fun launch(probe: Boolean) {
        if (!haveRecipe) { log("no recipe bundled in assets/recipes"); return }
        if (!isAccessibilityEnabled() && !probe) {
            log("enable the accessibility service first — it cannot tap without it")
            return
        }
        pendingProbe = probe
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projectionRequest.launch(mgr.createScreenCaptureIntent())
    }

    private fun setRunning(running: Boolean) {
        ui.startBtn.isEnabled = !running
        ui.probeBtn.isEnabled = !running
        ui.stopBtn.isEnabled = running
        if (!running && ui.status.text.isNullOrBlank()) ui.status.text = "idle"
    }

    private fun log(line: String) {
        ui.log.append(line + "\n")
        ui.logScroll.post { ui.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
