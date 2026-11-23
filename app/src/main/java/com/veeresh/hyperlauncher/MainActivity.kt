package com.veeresh.hyperlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
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
    private val columns: Int get() = prefs.getInt("grid_columns", 5)
    private val prefs by lazy { getSharedPreferences("hyperlauncher", MODE_PRIVATE) }
    private val showLabels: Boolean get() = prefs.getBoolean("show_labels", true)
    private val iconSizeDp: Int get() = prefs.getInt("icon_size_dp", 58)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun pinned(): MutableSet<String> = prefs.getStringSet("pinned", emptySet())?.toMutableSet() ?: mutableSetOf()
    private fun folderMap(): MutableMap<String, MutableSet<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        (prefs.getString("folders", "") ?: "").split("\n").filter { it.contains("|") }.forEach { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2) result[parts[0]] = parts[1].split(",").filter { it.isNotBlank() }.toMutableSet()
        }
        return result
    }
    private fun saveFolders(f: Map<String, Set<String>>) {
        prefs.edit().putString("folders", f.entries.joinToString("\n") { it.key + "|" + it.value.joinToString(",") }).apply()
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
            background = wallpaperDrawable() ?: bg(Color.rgb(18, 20, 27), 0)
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
        var touchStartX = 0f
        var touchStartY = 0f
        scroll.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - touchStartX
                    val deltaY = event.y - touchStartY
                    if (kotlin.math.abs(deltaX) > dp(90) &&
                        kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.25f) {
                        if (deltaX < 0 && !showingApps) {
                            showingApps = true
                            search.setText("")
                            render()
                        } else if (deltaX > 0 && showingApps) {
                            showingApps = false
                            search.setText("")
                            render()
                        }
                    }
                }
            }
            false
        }
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
                this.width = resources.displayMetrics.widthPixels - dp(32)
                height = dp(150)
            })
        }
    }

    private fun appTile(info: ResolveInfo, width: Int) {
        val pkg = info.activityInfo.packageName
        val label = info.loadLabel(packageManager).toString()
        val tile = LinearLayout(this).apply {
            orientation = 1; gravity = Gravity.CENTER; setPadding(dp(2), dp(8), dp(2), dp(8))
            setOnClickListener { animateTap(this) { launch(pkg) } }; setOnLongClickListener { appMenu(pkg, label); true }
        }
        val icon = TextView(this).apply {
            gravity = Gravity.CENTER; background = bg(0xff2f3544.toInt(), 18)
            val drawable = info.loadIcon(packageManager); drawable.setBounds(0, 0, dp(iconSizeDp - 10), dp(iconSizeDp - 10))
            setCompoundDrawables(null, drawable, null, null)
        }
        tile.addView(icon, LinearLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp)))
        if (showLabels) tile.addView(TextView(this).apply {
            text = label; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); maxLines = 2
        }, LinearLayout.LayoutParams(-1, dp(34)))
        grid.addView(tile, GridLayout.LayoutParams().apply { this.width = width; height = dp(iconSizeDp + if (showLabels) 44 else 20) })
    }

    private fun folderTile(name: String, pkgs: Set<String>, all: List<ResolveInfo>) {
        val width = (resources.displayMetrics.widthPixels - dp(32)) / columns
        val tile = LinearLayout(this).apply {
            orientation = 1; gravity = Gravity.CENTER; setPadding(dp(2), dp(8), dp(2), dp(8))
            setOnClickListener { animateTap(this) { openFolder(name, pkgs, all) } }; setOnLongClickListener { folderMenu(name); true }
        }
        val preview = GridLayout(this).apply {
            columnCount = 2
            background = bg(0xff394255.toInt(), 18)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        all.filter { it.activityInfo.packageName in pkgs }.take(4).forEach { info ->
            val miniIcon = android.widget.ImageView(this).apply {
                setImageDrawable(info.loadIcon(packageManager))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            preview.addView(miniIcon, GridLayout.LayoutParams().apply {
                this.width = dp(24); this.height = dp(24)
            })
        }
        tile.addView(preview, LinearLayout.LayoutParams(dp(58), dp(58)))
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
                "Add to Dock" -> {
                    val list = dock()
                    if (pkg !in list && list.size >= 5) Toast.makeText(this, "Dock is full (5 apps)", Toast.LENGTH_SHORT).show()
                    else { if (pkg !in list) list.add(pkg); prefs.edit().putString("dock", list.take(5).joinToString(",")).apply(); refreshDock() }
                }
                "Remove from Dock" -> { val list = dock(); list.remove(pkg); prefs.edit().putString("dock", list.joinToString(",")).apply(); refreshDock() }
            }
        }.show()
    }

    private fun createFolder(pkg: String) {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("Create folder").setView(input).setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Enter a folder name", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val folders = folderMap()
            if (folders.values.any { pkg in it }) {
                Toast.makeText(this, "This app is already in a folder", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            folders[name] = (folders[name] ?: mutableSetOf()).toMutableSet().apply { add(pkg) }
            saveFolders(folders); render()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun openFolder(name: String, pkgs: Set<String>, all: List<ResolveInfo>) {
        val content = LinearLayout(this).apply {
            orientation = 1
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = bg(0xff242833.toInt(), 24)
        }
        val box = GridLayout(this).apply { columnCount = 3 }
        all.filter { it.activityInfo.packageName in pkgs }.forEach { info ->
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(packageManager).toString()
            val tile = LinearLayout(this).apply {
                orientation = 1; gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { animateTap(this) { launch(pkg) } }
            }
            val iconView = TextView(this).apply {
                gravity = Gravity.CENTER
                val drawable = info.loadIcon(packageManager)
                drawable.setBounds(0, 0, dp(44), dp(44))
                setCompoundDrawables(null, drawable, null, null)
            }
            tile.addView(iconView, LinearLayout.LayoutParams(dp(64), dp(64)))
            if (showLabels) tile.addView(TextView(this).apply {
                text = label; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); maxLines = 2
            }, LinearLayout.LayoutParams(-1, dp(34)))
            box.addView(tile, GridLayout.LayoutParams().apply { width = dp(92); height = dp(if (showLabels) 108 else 78) })
        }
        content.addView(box)
        AlertDialog.Builder(this).setTitle(name).setView(content)
            .setNeutralButton("Manage") { _, _ -> folderManage(name) }
            .setPositiveButton("Close", null).show()
    }

    private fun folderManage(name: String) {
        AlertDialog.Builder(this).setTitle("Manage $name")
            .setItems(arrayOf("Add apps", "Remove apps", "Rename", "Delete folder")) { _, index ->
                when (index) {
                    0 -> changeFolderApps(name, add = true)
                    1 -> changeFolderApps(name, add = false)
                    2 -> renameFolder(name)
                    3 -> { val folders = folderMap(); folders.remove(name); saveFolders(folders); render() }
                }
            }.show()
    }

    private fun changeFolderApps(name: String, add: Boolean) {
        val all = apps()
        val folders = folderMap()
        val members = folders[name]?.toMutableSet() ?: mutableSetOf()
        val candidates = all.filter { info ->
            val pkg = info.activityInfo.packageName
            if (add) pkg !in members else pkg in members
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, if (add) "No apps available to add" else "Folder is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        val selected = mutableSetOf<String>()
        AlertDialog.Builder(this).setTitle(if (add) "Add apps to $name" else "Remove apps from $name")
            .setMultiChoiceItems(labels, BooleanArray(candidates.size)) { _, index, checked ->
                val pkg = candidates[index].activityInfo.packageName
                if (checked) selected.add(pkg) else selected.remove(pkg)
            }
            .setPositiveButton(if (add) "Add selected" else "Remove selected") { _, _ ->
                if (add) members.addAll(selected) else members.removeAll(selected)
                folders[name] = members
                saveFolders(folders)
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun renameFolder(name: String) {
        val input = EditText(this).apply { setText(name) }
        AlertDialog.Builder(this).setTitle("Rename folder").setView(input).setPositiveButton("Save") { _, _ ->
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(this, "Folder name cannot be empty", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            if (newName != name) {
                val folders = folderMap()
                if (newName in folders) {
                    Toast.makeText(this, "A folder with that name already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                folders[newName] = folders.remove(name) ?: mutableSetOf()
                saveFolders(folders)
                render()
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun folderMenu(name: String) {
        folderManage(name)
    }

    private fun wallpaperDrawable(): Drawable? {
        val saved = prefs.getString("wallpaper_uri", null) ?: return null
        return try {
            val uri = Uri.parse(saved)
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.let { BitmapDrawable(resources, it) }
            }
        } catch (_: Exception) { null }
    }

    private fun chooseWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 2401)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2401 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString("wallpaper_uri", uri.toString()).apply()
                recreate()
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't save wallpaper selection", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun settings() {
        val labelOption = if (showLabels) "Hide app labels" else "Show app labels"
        AlertDialog.Builder(this).setTitle("Launcher settings")
            .setItems(arrayOf("Home grid", "Customize dock", "Icon size", "Change wallpaper", "Reset wallpaper", labelOption, "Clear home pins")) { _, index ->
                when (index) {
                    0 -> homeGridPicker()
                    1 -> dockPicker()
                    2 -> iconSizePicker()
                    3 -> chooseWallpaper()
                    4 -> { prefs.edit().remove("wallpaper_uri").apply(); recreate() }
                    5 -> { prefs.edit().putBoolean("show_labels", !showLabels).apply(); render() }
                    6 -> AlertDialog.Builder(this).setTitle("Clear home pins?")
                        .setMessage("Remove all pinned apps from the Home tab?")
                        .setPositiveButton("Clear") { _, _ -> prefs.edit().putStringSet("pinned", emptySet()).apply(); render() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun homeGridPicker() {
        val options = arrayOf("4 columns", "5 columns", "6 columns")
        val values = intArrayOf(4, 5, 6)
        val current = values.indexOf(columns).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Home screen grid")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("grid_columns", values[which]).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun iconSizePicker() {
        val sizes = arrayOf("Small", "Medium", "Large")
        val values = intArrayOf(48, 58, 68)
        val current = values.indexOf(iconSizeDp).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Icon size")
            .setSingleChoiceItems(sizes, current) { dialog, which ->
                prefs.edit().putInt("icon_size_dp", values[which]).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockPicker() {
        val all = apps(); val current = dock().toSet()
        val labels = all.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        val checked = BooleanArray(all.size) { all[it].activityInfo.packageName in current }
        val selected = current.toMutableSet()
        AlertDialog.Builder(this).setTitle("Select up to 5 dock apps").setMultiChoiceItems(labels, checked) { dialog, i, isChecked ->
            val pkg = all[i].activityInfo.packageName
            if (isChecked) {
                if (selected.size < 5) selected.add(pkg)
                else {
                    (dialog as AlertDialog).listView.setItemChecked(i, false)
                    Toast.makeText(this, "Maximum 5 dock apps", Toast.LENGTH_SHORT).show()
                }
            } else selected.remove(pkg)
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
                setOnClickListener { animateTap(this) { launch(pkg) } }
                setOnLongClickListener { appMenu(pkg, info.loadLabel(packageManager).toString()); true }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    private fun animateTap(view: android.view.View, action: () -> Unit) {
        view.animate().cancel()
        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(85).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(100)
            action()
        }
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } ?: Toast.makeText(this, "App unavailable", Toast.LENGTH_SHORT).show()
    }
}
