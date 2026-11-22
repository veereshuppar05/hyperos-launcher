package com.veeresh.hyperlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var grid: GridLayout
    private lateinit var search: EditText
    private var showingApps = false
    private val columns = 5
    private val prefs by lazy { getSharedPreferences("hyperlauncher", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun pinned(): MutableSet<String> = prefs.getStringSet("pinned", emptySet())?.toMutableSet() ?: mutableSetOf()
    private fun folderMap(): MutableMap<String, MutableSet<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        (prefs.getString("folders", "") ?: "").split("\\n").filter { it.contains("|") }.forEach { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2) result[parts[0]] = parts[1].split(",").filter { it.isNotBlank() }.toMutableSet()
        }
        return result
    }
    private fun saveFolders(f: Map<String, Set<String>>) {
        prefs.edit().putString("folders", f.entries.joinToString("\\n") { it.key + "|" + it.value.joinToString(",") }).apply()
    }
    private fun dock(): MutableList<String> = (prefs.getString("dock", "") ?: "").split(",").filter { it.isNotBlank() }.toMutableList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 20, 27)
        window.navigationBarColor = Color.rgb(18, 20, 27)
        buildUi()
        render()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = 1
            setPadding(dp(16), dp(20), dp(16), dp(10))
            setBackgroundColor(Color.rgb(18, 20, 27))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "HyperLauncher"
            textSize = 26f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(this).apply {
            text = "⋮"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setOnClickListener { settings() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(48)))

        search = EditText(this).apply {
            hint = "Search apps"; setHintTextColor(0xff9ba3b2.toInt()); setTextColor(Color.WHITE)
            setSingleLine(true); setPadding(dp(16), 0, dp(16), 0); background = bg(0xff272b36.toInt(), 24)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })

        val tabs = LinearLayout(this).apply { orientation = 0; setPadding(0, dp(10), 0, dp(8)) }
        tabs.addView(tab("Home") { showingApps = false; search.setText(""); render() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        tabs.addView(tab("Apps") { showingApps = true; render() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(tabs)

        val scroll = ScrollView(this)
        grid = GridLayout(this).apply { columnCount = columns }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val dockBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER; orientation = 0; background = bg(0xff242833.toInt(), 24)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        root.addView(dockBar, LinearLayout.LayoutParams(-1, dp(60)))
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) showingApps = true
                render()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        updateDock(dockBar)
    }

    private fun tab(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.WHITE)
        background = bg(0xff303642.toInt(), 20); setOnClickListener { action() }
    }

    private fun apps(): List<ResolveInfo> = packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        .filter { it.activityInfo.packageName != packageName }
        .distinctBy { it.activityInfo.packageName }
        .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

    private fun render() {
        if (!::grid.isInitialized) return
        grid.removeAllViews()
        val all = apps()
        val query = search.text.toString()
        val folderMembers = folderMap().values.flatten().toSet()
        val home = pinned().filter { it !in folderMembers }
        val chosen = if (showingApps || query.isNotEmpty()) all.filter { it.loadLabel(packageManager).toString().contains(query, true) }
                     else all.filter { it.activityInfo.packageName in home }
        if (!showingApps && query.isEmpty()) folderMap().forEach { (name, packages) -> folderTile(name, packages, all) }
        val width = (resources.displayMetrics.widthPixels - dp(32)) / columns
        chosen.forEach { appTile(it, width) }
        if (chosen.isEmpty() && !showingApps && query.isEmpty() && folderMap().isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "Home screen is empty. Open Apps and long-press an app to pin it."
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(Color.LTGRAY)
            }, GridLayout.LayoutParams().apply {
                width = resources.displayMetrics.widthPixels - dp(32)
                height = dp(150)
            })
        }
    }

    private fun appTile(info: ResolveInfo, width: Int) {
        val pkg = info.activityInfo.packageName
        val label = info.loadLabel(packageManager).toString()
        val tile = LinearLayout(this).apply {
            orientation = 1; gravity = Gravity.CENTER; setPadding(dp(2), dp(8), dp(2), dp(8))
            setOnClickListener { launch(pkg) }; setOnLongClickListener { appMenu(pkg, label); true }
        }
        val icon = TextView(this).apply {
            gravity = Gravity.CENTER; background = bg(0xff2f3544.toInt(), 18)
            val drawable = info.loadIcon(packageManager); drawable.setBounds(0, 0, dp(48), dp(48))
            setCompoundDrawables(null, drawable, null, null)
        }
        tile.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))
        tile.addView(TextView(this).apply {
            text = label; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); maxLines = 2
        }, LinearLayout.LayoutParams(-1, dp(34)))
        grid.addView(tile, GridLayout.LayoutParams().apply { this.width = width; height = dp(102) })
    }

    private fun folderTile(name: String, pkgs: Set<String>, all: List<ResolveInfo>) {
        val width = (resources.displayMetrics.widthPixels - dp(32)) / columns
        val tile = LinearLayout(this).apply {
            orientation = 1; gravity = Gravity.CENTER; setPadding(dp(2), dp(8), dp(2), dp(8))
            setOnClickListener { openFolder(name, pkgs, all) }; setOnLongClickListener { folderMenu(name); true }
        }
        tile.addView(TextView(this).apply {
            text = "▦"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = bg(0xff394255.toInt(), 18)
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        val folderLabel = TextView(this)
        folderLabel.text = name
        folderLabel.textSize = 10.5f
        folderLabel.gravity = Gravity.CENTER
        folderLabel.setTextColor(Color.WHITE)
        tile.addView(folderLabel, LinearLayout.LayoutParams(-1, dp(34)))
        grid.addView(tile, GridLayout.LayoutParams().apply { this.width = width; height = dp(102) })
    }

    private fun appMenu(pkg: String, label: String) {
        val isPinned = pkg in pinned()
        val options = mutableListOf("Open", if (isPinned) "Unpin from Home" else "Pin to Home", "App info", "Uninstall")
        if (isPinned) options.add("Create folder")
        options.add(if (pkg in dock()) "Remove from Dock" else "Add to Dock")
        AlertDialog.Builder(this).setTitle(label).setItems(options.toTypedArray()) { _, index ->
            when (options[index]) {
                "Open" -> launch(pkg)
                "Pin to Home", "Unpin from Home" -> {
                    val set = pinned(); if (isPinned) set.remove(pkg) else set.add(pkg)
                    prefs.edit().putStringSet("pinned", set).apply(); render()
                }
                "App info" -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                "Uninstall" -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                "Create folder" -> createFolder(pkg)
                "Add to Dock" -> { val list = dock(); if (pkg !in list) list.add(pkg); prefs.edit().putString("dock", list.take(5).joinToString(",")).apply(); refreshDock() }
                "Remove from Dock" -> { val list = dock(); list.remove(pkg); prefs.edit().putString("dock", list.joinToString(",")).apply(); refreshDock() }
            }
        }.show()
    }

    private fun createFolder(pkg: String) {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("Create folder").setView(input).setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim().ifEmpty { "Folder" }
            val folders = folderMap(); folders[name] = (folders[name] ?: mutableSetOf()).toMutableSet().apply { add(pkg) }
            saveFolders(folders); render()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun openFolder(name: String, pkgs: Set<String>, all: List<ResolveInfo>) {
        val box = GridLayout(this).apply { columnCount = 3 }
        all.filter { it.activityInfo.packageName in pkgs }.forEach { info ->
            val pkg = info.activityInfo.packageName
            box.addView(TextView(this).apply {
                text = info.loadLabel(packageManager).toString(); gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setOnClickListener { launch(pkg) }
            }, GridLayout.LayoutParams().apply { width = dp(100); height = dp(70) })
        }
        AlertDialog.Builder(this).setTitle(name).setView(box).setPositiveButton("Close", null).show()
    }

    private fun folderMenu(name: String) {
        AlertDialog.Builder(this).setTitle(name).setItems(arrayOf("Rename", "Delete")) { _, index ->
            val folders = folderMap()
            if (index == 0) {
                val input = EditText(this).apply { setText(name) }
                AlertDialog.Builder(this).setTitle("Rename folder").setView(input).setPositiveButton("Save") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty()) { folders[newName] = folders.remove(name) ?: mutableSetOf(); saveFolders(folders); render() }
                }.setNegativeButton("Cancel", null).show()
            } else { folders.remove(name); saveFolders(folders); render() }
        }.show()
    }

    private fun settings() {
        AlertDialog.Builder(this).setTitle("Launcher settings").setItems(arrayOf("Customize dock", "Clear home pins")) { _, index ->
            if (index == 0) dockPicker() else { prefs.edit().putStringSet("pinned", emptySet()).apply(); render() }
        }.show()
    }

    private fun dockPicker() {
        val all = apps(); val current = dock().toSet()
        val labels = all.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        val checked = BooleanArray(all.size) { all[it].activityInfo.packageName in current }
        val selected = current.toMutableSet()
        AlertDialog.Builder(this).setTitle("Select up to 5 dock apps").setMultiChoiceItems(labels, checked) { _, i, isChecked ->
            val pkg = all[i].activityInfo.packageName
            if (isChecked) { if (selected.size < 5) selected.add(pkg) } else selected.remove(pkg)
        }.setPositiveButton("Save") { _, _ ->
            prefs.edit().putString("dock", selected.take(5).joinToString(",")).apply(); refreshDock()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun refreshDock() {
        val parent = grid.parent?.parent as? LinearLayout ?: return
        parent.removeViewAt(parent.childCount - 1)
        val bar = LinearLayout(this).apply {
            orientation = 0; gravity = Gravity.CENTER; background = bg(0xff242833.toInt(), 24)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(60)))
        updateDock(bar)
    }

    private fun updateDock(bar: LinearLayout) {
        val list = dock()
        if (list.isEmpty()) {
            val dockHint = TextView(this)
            dockHint.text = "Dock • Customize from ⋮"
            dockHint.gravity = Gravity.CENTER
            dockHint.textSize = 12f
            dockHint.setTextColor(0xffcdd3df.toInt())
            bar.addView(dockHint, LinearLayout.LayoutParams(-1, dp(44)))
            return
        }
        list.take(5).forEach { pkg ->
            val info = apps().firstOrNull { it.activityInfo.packageName == pkg }
            if (info != null) bar.addView(TextView(this).apply {
                gravity = Gravity.CENTER
                val drawable = info.loadIcon(packageManager); drawable.setBounds(0, 0, dp(34), dp(34))
                setCompoundDrawables(null, drawable, null, null)
                setOnClickListener { launch(pkg) }
                setOnLongClickListener { appMenu(pkg, info.loadLabel(packageManager).toString()); true }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } ?: Toast.makeText(this, "App unavailable", Toast.LENGTH_SHORT).show()
    }
}
