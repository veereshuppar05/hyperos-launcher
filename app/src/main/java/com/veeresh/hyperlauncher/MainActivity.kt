package com.veeresh.hyperlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var search: EditText
    private lateinit var apps: List<android.content.pm.ResolveInfo>
    private val columns = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 20, 27)
        window.navigationBarColor = Color.rgb(18, 20, 27)
        window.decorView.systemUiVisibility = 0
        buildUi()
        loadApps("")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(10))
            setBackgroundColor(Color.rgb(18, 20, 27))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Hello"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val subtitle = TextView(this).apply {
            text = "  HyperLauncher"
            textSize = 13f
            setTextColor(Color.rgb(160, 170, 188))
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(title)
        header.addView(subtitle)
        root.addView(header, LinearLayout.LayoutParams(-1, dp(48)))

        search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(Color.rgb(155, 163, 178))
            setTextColor(Color.WHITE)
            textSize = 15f
            singleLine = true
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Color.rgb(39, 43, 54), 24)
        }
        val searchParams = LinearLayout.LayoutParams(-1, dp(48))
        searchParams.topMargin = dp(18)
        root.addView(search, searchParams)

        val section = TextView(this).apply {
            text = "ALL APPS"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(145, 155, 173))
        }
        val sectionParams = LinearLayout.LayoutParams(-1, dp(24))
        sectionParams.topMargin = dp(18)
        root.addView(section, sectionParams)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        grid = GridLayout(this).apply {
            columnCount = columns
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(0, dp(8), 0, dp(90))
        }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(36, 40, 51), 26)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val dockText = TextView(this).apply {
            text = "Swipe/search to find your apps"
            textSize = 12f
            setTextColor(Color.rgb(205, 211, 223))
            gravity = Gravity.CENTER
        }
        dock.addView(dockText, LinearLayout.LayoutParams(-1, dp(42)))
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadApps(query: String) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = packageManager
        apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val filtered = apps.filter {
            it.loadLabel(pm).toString().contains(query, ignoreCase = true)
        }
        grid.removeAllViews()
        val width = (resources.displayMetrics.widthPixels - dp(32)) / columns

        filtered.forEach { info ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(8), dp(2), dp(8))
                isClickable = true
                isFocusable = true
                background = rounded(Color.TRANSPARENT, 16)
                setOnClickListener { launch(info.activityInfo.packageName) }
                setOnLongClickListener {
                    showAppMenu(info.activityInfo.packageName, info.loadLabel(pm).toString())
                    true
                }
            }

            val icon = TextView(this).apply {
                gravity = Gravity.CENTER
                text = ""
                background = rounded(Color.rgb(47, 53, 68), 20)
                setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
                val drawable = info.loadIcon(pm)
                drawable.setBounds(0, 0, dp(48), dp(48))
                setCompoundDrawables(null, drawable, null, null)
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))

            val label = TextView(this).apply {
                text = info.loadLabel(pm).toString()
                textSize = 10.5f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            tile.addView(label, LinearLayout.LayoutParams(-1, dp(34)))

            val params = GridLayout.LayoutParams().apply {
                width = width
                height = dp(102)
                setMargins(0, 0, 0, dp(2))
            }
            grid.addView(tile, params)
        }
    }

    private fun launch(packageName: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) startActivity(launch)
        else Toast.makeText(this, "App cannot be opened", Toast.LENGTH_SHORT).show()
    }

    private fun showAppMenu(packageName: String, label: String) {
        val options = arrayOf("Open", "App info", "Uninstall")
        android.app.AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launch(packageName)
                    1 -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                    2 -> {
                        val uninstall = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                        startActivity(uninstall)
                    }
                }
            }.show()
    }
}
