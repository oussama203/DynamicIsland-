package com.oussama.island

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.*
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

/** Dynamic Island overlay service - developer: oussama */
class IslandService : Service() {

    companion object {
        @Volatile var instance: IslandService? = null
        const val ACTION_TIMER = "com.oussama.island.TIMER"
        const val EXTRA_SECONDS = "seconds"
    }

    private lateinit var wm: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var box: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("island", MODE_PRIVATE) }

    private var callView: View? = null
    private var callKey: String? = null
    private var temp: View? = null
    private var timerEnd = 0L
    private var timerText: TextView? = null
    private var controller: MediaController? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        root = FrameLayout(this).apply { addView(box); visibility = View.GONE }
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        wm.addView(root, lp)
        styleNow()
        registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        setupMedia()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TIMER) {
            val s = intent.getIntExtra(EXTRA_SECONDS, 0)
            if (s > 0) {
                timerEnd = System.currentTimeMillis() + s * 1000L
                h.removeCallbacks(tick)
                doRefresh()
                h.post(tick)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        h.removeCallbacksAndMessages(null)
        try { unregisterReceiver(power) } catch (_: Exception) {}
        controller?.unregisterCallback(mcb)
        try { sessionListener?.let { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(it) } } catch (_: Exception) {}
        try { wm.removeView(root) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("island", "Dynamic Island", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "island")
            .setContentTitle("Dynamic Island يعمل")
            .setContentText("by oussama")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    // ---------- Style ----------
    fun applyStyle() { h.post { styleNow() } }

    private fun styleNow() {
        val color = prefs.getInt("color", Color.BLACK)
        val hdp = prefs.getInt("height", 44)
        box.background = GradientDrawable().apply { setColor(color); cornerRadius = dp(hdp / 2).toFloat() }
        box.minimumWidth = dp(prefs.getInt("width", 160))
        box.minimumHeight = dp(hdp)
        lp.y = dp(prefs.getInt("top", 12))
        if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
    }

    // ---------- State machine ----------
    fun refresh() { h.post { doRefresh() } }

    private fun doRefresh() {
        val v: View? = when {
            callView != null -> callView
            temp != null -> temp
            timerEnd > System.currentTimeMillis() -> buildTimer()
            isPlaying() -> buildMedia()
            else -> null
        }
        if (v == null) { root.visibility = View.GONE; return }
        box.removeAllViews()
        box.addView(v)
        if (root.visibility != View.VISIBLE) {
            root.visibility = View.VISIBLE
            box.scaleX = 0.5f; box.alpha = 0f
            box.animate().scaleX(1f).alpha(1f).setDuration(220).start()
        }
    }

    private val clearTemp = Runnable { temp = null; doRefresh() }
    private fun setTemp(v: View, ms: Long) {
        temp = v
        h.removeCallbacks(clearTemp)
        h.postDelayed(clearTemp, ms)
        doRefresh()
    }

    // ---------- Notifications ----------
    fun showNotification(title: String, text: String, icon: Drawable?, tap: PendingIntent?) {
        h.post {
            val r = row()
            if (icon != null) r.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(title, 13f, bold = true))
            if (text.isNotEmpty()) col.addView(tv(text, 12f, Color.LTGRAY))
            r.addView(col)
            r.setOnClickListener {
                try { tap?.send() } catch (_: Exception) {}
                h.removeCallbacks(clearTemp); temp = null; doRefresh()
            }
            setTemp(r, 5000)
        }
    }

    // ---------- Calls ----------
    fun showCall(key: String, title: String, text: String, actions: List<Notification.Action>) {
        h.post {
            callKey = key
            val r = row()
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(title, 14f, bold = true))
            if (text.isNotEmpty()) col.addView(tv(text, 12f, Color.LTGRAY))
            r.addView(col)
            actions.take(2).forEach { a ->
                val t = a.title.toString()
                val bad = listOf("decline", "reject", "hang", "end", "رفض", "إنهاء", "انهاء")
                    .any { t.contains(it, ignoreCase = true) }
                r.addView(
                    pill(t, if (bad) 0xFFFF453A.toInt() else 0xFF30D158.toInt()) {
                        try { a.actionIntent?.send() } catch (_: Exception) {}
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
                )
            }
            callView = r
            doRefresh()
        }
    }

    fun callEnded(key: String) {
        h.post {
            if (key == callKey) { callKey = null; callView = null; doRefresh() }
        }
    }

    // ---------- Timer ----------
    private fun fmt(s: Long) = "%02d:%02d".format(s / 60, s % 60)

    private fun buildTimer(): View {
        val r = row()
        r.addView(tv("⏱ ", 15f))
        timerText = tv(fmt((timerEnd - System.currentTimeMillis()) / 1000), 15f, bold = true)
        r.addView(timerText)
        return r
    }

    private val tick = object : Runnable {
        override fun run() {
            if (timerEnd == 0L) return
            val left = (timerEnd - System.currentTimeMillis()) / 1000
            if (left <= 0) {
                timerEnd = 0
                try {
                    getSystemService(Vibrator::class.java)
                        .vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (_: Exception) {}
                setTemp(row().apply { addView(tv("⏱ انتهى المؤقت", 14f, bold = true)) }, 5000)
                return
            }
            timerText?.text = fmt(left)
            h.postDelayed(this, 1000)
        }
    }

    // ---------- Battery / charging ----------
    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!prefs.getBoolean("battery", true)) return
            val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = (b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0) * 100 /
                    (b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100)
            val msg = if (i.action == Intent.ACTION_POWER_CONNECTED) "⚡ يتم الشحن • $lvl%" else "🔋 تم فصل الشاحن • $lvl%"
            setTemp(row().apply { addView(tv(msg, 14f, bold = true)) }, 3000)
        }
    }

    // ---------- Music ----------
    private val mcb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { refresh() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { refresh() }
    }

    fun setupMedia() {
        h.post {
            try {
                val msm = getSystemService(MediaSessionManager::class.java)
                val cn = ComponentName(this, NotifListener::class.java)
                sessionListener?.let { msm.removeOnActiveSessionsChangedListener(it) }
                sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
                msm.addOnActiveSessionsChangedListener(sessionListener!!, cn)
                pick(msm.getActiveSessions(cn))
            } catch (_: SecurityException) { /* notification access not granted yet */ }
        }
    }

    private fun pick(list: List<MediaController>?) {
        controller?.unregisterCallback(mcb)
        controller = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        controller?.registerCallback(mcb)
        refresh()
    }

    private fun isPlaying() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun buildMedia(): View {
        val c = controller!!
        val m = c.metadata
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "..."
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val r = row()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("🎵 $title", 13f, bold = true))
        if (!artist.isNullOrEmpty()) col.addView(tv(artist, 11f, Color.LTGRAY))
        r.addView(col)
        fun btn(s: String, f: () -> Unit) = tv(s, 20f).apply {
            setPadding(dp(8), 0, dp(8), 0); setOnClickListener { f() }
        }
        r.addView(btn("⏮") { c.transportControls.skipToPrevious() })
        r.addView(btn("⏸") { c.transportControls.pause() })
        r.addView(btn("⏭") { c.transportControls.skipToNext() })
        return r
    }

    // ---------- View helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun tv(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(200)
    }

    private fun pill(label: String, color: Int, click: () -> Unit) = tv(label, 13f, bold = true).apply {
        gravity = Gravity.CENTER
        maxWidth = dp(90)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat() }
        setOnClickListener { click() }
    }
}
