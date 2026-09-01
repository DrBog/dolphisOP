package dev.autotapper.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import dev.autotapper.core.RecipeRef
import dev.autotapper.core.Recipes
import dev.autotapper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private var pendingProbe = false
    private var loadouts: List<RecipeRef> = emptyList()

    private val prefs by lazy { getSharedPreferences("autotapper", MODE_PRIVATE) }

    private val importPick = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val ref = RecipeStore.import(this, uri, "imported")
            refreshLoadouts(select = ref.name)
            log("imported loadout '${ref.name}'")
        } catch (e: Exception) {
            log("import failed: ${e.message}")
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

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
            putExtra(TapperService.EXTRA_RECIPE, selected()?.name)
            putExtra(TapperService.EXTRA_RECIPE_USER, selected()?.isUser ?: false)
            putExtra(TapperService.EXTRA_LOOPS, loops())
            putExtra(TapperService.EXTRA_PROBE, pendingProbe)
            putExtra(TapperService.EXTRA_DELAY, leadInMs())
        }
        ui.log.text = ""
        ui.preview.visibility = android.view.View.GONE
        startForegroundService(i)
        setRunning(true)
        // The consent dialog can only be answered from here, which leaves us in
        // the foreground - so a capture taken now would photograph this screen.
        // Step aside and let the game come back before the countdown expires.
        moveTaskToBack(true)
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
                TapperService.ACTION_PREVIEW -> {
                    val path = i.getStringExtra(TapperService.EXTRA_LINE) ?: return
                    val bmp = BitmapFactory.decodeFile(path)
                    if (bmp != null) {
                        ui.preview.setImageBitmap(bmp)
                        ui.preview.visibility = android.view.View.VISIBLE
                    }
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

        refreshLoadouts(select = prefs.getString("recipe", null))
        ui.recipe.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                loadouts.getOrNull(pos)?.let {
                    prefs.edit().putString("recipe", it.name).apply()
                    ui.deleteBtn.isEnabled = it.isUser
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        ui.importBtn.setOnClickListener {
            importPick.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        ui.exportBtn.setOnClickListener { exportSelected() }
        ui.deleteBtn.setOnClickListener { deleteSelected() }
        // The log view is a fixed-height ScrollView, and copying a long run's
        // log out of it by scrolling and screenshotting is exactly what a user
        // reported being unable to do when a run stalled. Share the raw text
        // instead - no scrolling, no cropping, works for any length.
        ui.shareLogBtn.setOnClickListener { shareLog() }

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7
            )
        }

        ui.accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // Android 13+ hides the accessibility toggle for sideloaded apps behind
        // "Restricted settings". Nothing in-app can lift that - it is a security
        // gate against exactly this install path - but we can at least land the
        // user on the screen whose overflow menu unlocks it.
        ui.appInfoBtn.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
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
            addAction(TapperService.ACTION_PREVIEW)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, f)
        }
        refreshAccessibilityState()
        replayTranscript()
    }

    /** Re-render anything logged while this activity was in the background. */
    private fun replayTranscript() {
        val lines = synchronized(TapperService.transcript) { TapperService.transcript.toList() }
        if (lines.isEmpty()) return
        ui.log.text = lines.joinToString("\n") + "\n"
        ui.logScroll.post { ui.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        TapperService.previewPath?.let { path ->
            BitmapFactory.decodeFile(path)?.let {
                ui.preview.setImageBitmap(it)
                ui.preview.visibility = android.view.View.VISIBLE
            }
        }
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
        ui.appInfoBtn.isEnabled = !on
        ui.restrictedHint.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
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

    private fun selected(): RecipeRef? = loadouts.getOrNull(ui.recipe.selectedItemPosition)

    private fun refreshLoadouts(select: String? = null) {
        loadouts = Recipes.list(this)
        val labels = loadouts.map { it.label }
        ui.recipe.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val want = loadouts.indexOfFirst { it.name == select }
        if (want >= 0) ui.recipe.setSelection(want)
        val any = loadouts.isNotEmpty()
        ui.startBtn.isEnabled = any
        ui.probeBtn.isEnabled = any
        ui.exportBtn.isEnabled = any
        ui.deleteBtn.isEnabled = selected()?.isUser == true
        if (!any) log("no loadouts - import one with the Import button")
    }

    private fun exportSelected() {
        val ref = selected() ?: return
        try {
            val zip = RecipeStore.export(this, ref)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share ${ref.name}"
                )
            )
        } catch (e: Exception) {
            log("export failed: ${e.message}")
        }
    }

    private fun deleteSelected() {
        val ref = selected() ?: return
        if (!ref.isUser) return   // bundled ones are read-only
        AlertDialog.Builder(this)
            .setTitle("Delete '${ref.name}'?")
            .setMessage("This removes the saved loadout from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                RecipeStore.delete(this, ref)
                refreshLoadouts()
                log("deleted loadout '${ref.name}'")
            }
            .show()
    }

    private fun loops(): Int = ui.loops.text.toString().trim().toIntOrNull()?.coerceIn(1, 100000) ?: 1

    private fun leadInMs(): Long =
        (ui.leadIn.text.toString().trim().toLongOrNull() ?: 6L).coerceIn(0L, 120L) * 1000L

    private fun launch(probe: Boolean) {
        if (selected() == null) { log("no loadout selected"); return }
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

    /** Pulled from the service's own buffer, not the TextView - guaranteed
     *  complete even if the activity was recreated mid-run. */
    private fun shareLog() {
        val lines = synchronized(TapperService.transcript) { TapperService.transcript.toList() }
        val text = lines.ifEmpty { listOf(ui.log.text.toString()) }.joinToString("\n")
        if (text.isBlank()) { Toast.makeText(this, "Nothing to share yet", Toast.LENGTH_SHORT).show(); return }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_SUBJECT, "Autotapper log")
                }, "Share log"
            )
        )
    }
}
