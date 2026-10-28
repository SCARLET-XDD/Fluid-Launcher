package com.fluid.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.*
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

data class AppInfo(val label: String, val component: ComponentName, val icon: android.graphics.drawable.Drawable)

class MainActivity : Activity() {

    private lateinit var home: FrameLayout
    private lateinit var drawer: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var clockBox: LinearLayout
    private lateinit var search: EditText
    private lateinit var adapter: AppAdapter
    private val prefs by lazy { getSharedPreferences("fluid", MODE_PRIVATE) }
    private var allApps = listOf<AppInfo>()
    private var screenH = 0f
    private var spring: SpringAnimation? = null
    private var vt: VelocityTracker? = null
    private var startY = 0f
    private var startTy = 0f

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = loadApps()
    }

    private fun dp(v: Int) = v * resources.displayMetrics.density
    private fun dpi(v: Int) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        screenH = resources.displayMetrics.heightPixels.toFloat()

        val root = FrameLayout(this)
        home = FrameLayout(this)
        root.addView(home, -1, -1)

        // --- Clock + date (One UI style: big, light, left aligned) ---
        clockBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockBox.addView(TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            textSize = 72f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, 0x55000000)
        })
        clockBox.addView(TextClock(this).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"
            textSize = 18f; setTextColor(0xEEFFFFFF.toInt())
            setShadowLayer(6f, 0f, 2f, 0x55000000)
        })
        home.addView(clockBox, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(32); topMargin = dpi(56) })

        // --- Dock ---
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(32); setColor(0x40FFFFFF) }
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
        }
        home.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dpi(16); rightMargin = dpi(16); bottomMargin = dpi(20)
        })
        home.setOnTouchListener { _, e -> handleDrag(e) }

        // --- App drawer ---
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF2121214.toInt())
                cornerRadii = floatArrayOf(dp(32), dp(32), dp(32), dp(32), 0f, 0f, 0f, 0f)
            }
            translationY = screenH
        }
        val handle = FrameLayout(this).apply {
            addView(View(context).apply {
                background = GradientDrawable().apply { cornerRadius = dp(3); setColor(0x66FFFFFF) }
            }, FrameLayout.LayoutParams(dpi(40), dpi(5), Gravity.CENTER))
            setOnTouchListener { _, e -> handleDrag(e) }
        }
        drawer.addView(handle, LinearLayout.LayoutParams(-1, dpi(28)))

        search = EditText(this).apply {
            hint = "Search apps"; setHintTextColor(0x88FFFFFF.toInt()); setTextColor(Color.WHITE)
            textSize = 16f; isSingleLine = true
            setPadding(dpi(20), 0, dpi(20), 0)
            background = GradientDrawable().apply { cornerRadius = dp(26); setColor(0x22FFFFFF) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = filter(s.toString())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        drawer.addView(search, LinearLayout.LayoutParams(-1, dpi(52)).apply {
            leftMargin = dpi(20); rightMargin = dpi(20); bottomMargin = dpi(8)
        })

        adapter = AppAdapter()
        val list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, 4)
            this.adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(dpi(8), dpi(8), dpi(8), dpi(24))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        drawer.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (clockBox.layoutParams as FrameLayout.LayoutParams).topMargin = b.top + dpi(32)
            (dock.layoutParams as FrameLayout.LayoutParams).bottomMargin = b.bottom + dpi(16)
            drawer.setPadding(0, b.top, 0, b.bottom)
            insets
        }

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        loadApps()
    }

    override fun onDestroy() { super.onDestroy(); unregisterReceiver(pkgReceiver) }

    // ---------- Apps ----------
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { AppInfo(it.loadLabel(pm).toString(),
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadIcon(pm)) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { allApps = apps; filter(search.text.toString()); buildDock() }
        }.start()
    }

    private fun filter(q: String) {
        adapter.submit(if (q.isBlank()) allApps else allApps.filter { it.label.contains(q, true) })
    }

    private fun pinned(): List<AppInfo> {
        val saved = prefs.getString("dock", null)
        if (saved != null) {
            return saved.split("|").filter { it.isNotEmpty() }.mapNotNull { f ->
                allApps.firstOrNull { it.component.flattenToString() == f }
            }
        }
        val defaults = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")),
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        )
        return defaults.mapNotNull { i ->
            val pkg = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            allApps.firstOrNull { it.component.packageName == pkg }
        }.distinct()
    }

    private fun togglePin(app: AppInfo) {
        val cur = pinned().map { it.component.flattenToString() }.toMutableList()
        val f = app.component.flattenToString()
        if (!cur.remove(f)) { if (cur.size < 5) cur.add(f) else return }
        prefs.edit().putString("dock", cur.joinToString("|")).apply()
        buildDock()
    }

    private fun buildDock() {
        dock.removeAllViews()
        for (a in pinned()) {
            val v = iconView(a, 56, false)
            dock.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    // ---------- Icon view with One UI squircle + spring press ----------
    private fun iconView(app: AppInfo, size: Int, label: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpi(8), 0, dpi(8))
        }
        val img = ImageView(this).apply {
            setImageDrawable(app.icon)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, v.width * 0.32f)
            }
            clipToOutline = true
            elevation = dp(2)
        }
        box.addView(img, LinearLayout.LayoutParams(dpi(size), dpi(size)))
        if (label) box.addView(TextView(this).apply {
            text = app.label; setTextColor(Color.WHITE); textSize = 12f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6); leftMargin = dpi(2); rightMargin = dpi(2) })

        box.setOnClickListener { launch(img, app) }
        box.setOnLongClickListener { togglePin(app); box.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); true }
        box.setOnTouchListener { v, e ->
            val t = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> 0.86f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
                else -> null
            }
            if (t != null) for (p in listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y)) {
                SpringAnimation(v, p, t).apply {
                    this.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                    this.spring.dampingRatio = 0.55f
                }.start()
            }
            false
        }
        return box
    }

    // ---------- Launch: icon expands into the app window ----------
    private fun launch(icon: View, app: AppInfo) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = ActivityOptions.makeClipRevealAnimation(icon, 0, 0, icon.width, icon.height)
        try { startActivity(i, opts.toBundle()) } catch (_: Exception) {}
    }

    // ---------- Drawer: finger-following, spring-settled ----------
    private fun handleDrag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain(); vt!!.addMovement(e)
                startY = e.rawY; startTy = drawer.translationY; spring?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                setDrawer((startTy + e.rawY - startY).coerceIn(0f, screenH))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val v = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                val open = if (abs(v) > 700) v < 0 else drawer.translationY < screenH / 2
                animateDrawer(open, v)
            }
        }
        return true
    }

    private fun setDrawer(ty: Float) {
        drawer.translationY = ty
        val p = 1f - ty / screenH          // 0 closed .. 1 open
        home.alpha = 1f - p
        home.scaleX = 1f - 0.06f * p; home.scaleY = home.scaleX
    }

    private fun animateDrawer(open: Boolean, velocity: Float) {
        if (!open) hideKeyboard()
        spring = SpringAnimation(drawer, DynamicAnimation.TRANSLATION_Y, if (open) 0f else screenH).apply {
            this.spring.stiffness = 450f
            this.spring.dampingRatio = 0.82f
            setStartVelocity(velocity)
            addUpdateListener { _, v, _ -> setDrawer(v.coerceIn(0f, screenH)) }
            start()
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (drawer.translationY < screenH) { search.setText(""); animateDrawer(false, 0f) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (drawer.translationY < screenH) animateDrawer(false, 0f) }

    // ---------- Adapter ----------
    inner class AppAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items = listOf<AppInfo>()
        fun submit(l: List<AppInfo>) { items = l; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            object : RecyclerView.ViewHolder(FrameLayout(p.context)) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val f = h.itemView as FrameLayout
            f.removeAllViews(); f.addView(iconView(items[i], 60, true))
        }
    }
}
