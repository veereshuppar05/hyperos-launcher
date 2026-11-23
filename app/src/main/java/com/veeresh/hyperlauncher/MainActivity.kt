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
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var grid: GridLayout
    private lateinit var appScroll: ScrollView
    private lateinit var alphabetRail: TextView
    private val sectionHeaders = mutableMapOf<Char, android.view.View>()
    private lateinit var search: EditText
    private var showingApps = false
    private var pageTransitioning = false
    private val columns: Int get() = prefs.getInt("grid_columns", 5)
    private val drawerColumns: Int get() = prefs.getInt("drawer_columns", 5)
    private val prefs by lazy { getSharedPreferences("hyperlauncher", MODE_PRIVATE) }
    private val showLabels: Boolean get() = prefs.getBoolean("show_labels", true)
    private val showHomeLabels: Boolean get() = prefs.getBoolean("home_show_labels", showLabels)
    private val showDrawerLabels: Boolean get() = prefs.getBoolean("drawer_show_labels", showLabels)
    private val iconSizeDp: Int get() = prefs.getInt("icon_size_dp", 58)
    private val drawerIconSizeDp: Int get() = prefs.getInt("drawer_icon_size_dp", 64)
    private val dockIconSizeDp: Int get() = prefs.getInt("dock_icon_size_dp", 34)
    private val dockShowLabels: Boolean get() = prefs.getBoolean("dock_show_labels", false)
    private val dockBarHeightDp: Int get() = if (dockShowLabels) dockIconSizeDp + 32 else 60
    private val labelSize: Float get() = prefs.getFloat("label_size_sp", 11f)
    private val homeLabelSize: Float get() = prefs.getFloat("home_label_size_sp", labelSize - 0.5f)
    private val drawerLabelSize: Float get() = prefs.getFloat("drawer_label_size_sp", labelSize + 0.5f)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun pinned(): MutableSet<String> = prefs.getStringSet("pinned", emptySet())?.toMutableSet() ?: mutableSetOf()
    private fun hidden(): MutableSet<String> = prefs.getStringSet("hidden_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
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
            hint = if (showingApps) "Search all apps" else "Search apps"
            setHintTextColor(0xff9ba3b2.toInt()); setTextColor(Color.WHITE)
            setSingleLine(true); setPadding(dp(16), 0, dp(16), 0); background = bg(0xff272b36.toInt(), 24)
            setOnClickListener {
                if (!showingApps) switchPage(true)
                requestFocus()
                post {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })

        val tabs = LinearLayout(this).apply { orientation = 0; setPadding(0, dp(10), 0, dp(8)) }
        tabs.addView(tab("Home") { switchPage(false) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        tabs.addView(tab("Apps") { switchPage(true) }, LinearLayout.LayoutParams(0, dp(40), 1f))
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
                            switchPage(true)
                        } else if (deltaX > 0 && showingApps) {
                            switchPage(false)
                        }
                    }
                }
            }
            false
        }
        appScroll = scroll
        scroll.isLongClickable = true
        scroll.setOnLongClickListener {
            if (!showingApps && search.text.isEmpty()) {
                homeQuickCustomize()
                true
            } else false
        }
        grid = GridLayout(this).apply { columnCount = columns }
        scroll.addView(grid)
        val contentFrame = FrameLayout(this)
        contentFrame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        alphabetRail = TextView(this).apply {
            text = "A\nB\nC\nD\nE\nF\nG\nH\nI\nJ\nK\nL\nM\nN\nO\nP\nQ\nR\nS\nT\nU\nV\nW\nX\nY\nZ\n#"
            textSize = 9f
            setTextColor(0xffb9c5df.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(2), dp(3), dp(2))
            visibility = android.view.View.GONE
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    val letters = sectionHeaders.keys.sorted().toList()
                    if (letters.isNotEmpty()) {
                        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#"
                        val railIndex = ((event.y / height.coerceAtLeast(1)) * alphabet.length)
                            .toInt().coerceIn(0, alphabet.lastIndex)
                        val target = alphabet[railIndex]
                        val nearest = letters.minByOrNull { candidate ->
                            if (target == '#') if (candidate == '#') 0 else 1000
                            else if (candidate == '#') 1000 else kotlin.math.abs(candidate - target)
                        } ?: target
                        sectionHeaders[nearest]?.let { appScroll.smoothScrollTo(0, it.top) }
                    }
                    true
                } else event.action == MotionEvent.ACTION_UP
            }
        }
        contentFrame.addView(alphabetRail, FrameLayout.LayoutParams(dp(26), -1, Gravity.END))
        root.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        val dockBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER; orientation = 0; background = dockBackground()
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        root.addView(dockBar, LinearLayout.LayoutParams(-1, dp(dockBarHeightDp)))
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) showingApps = true
                if (!pageTransitioning) render()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        updateDock(dockBar)
    }

    private fun switchPage(toApps: Boolean) {
        if (pageTransitioning || showingApps == toApps) {
            if (!toApps && search.text.isNotEmpty()) search.setText("")
            return
        }
        pageTransitioning = true
        val direction = if (toApps) -1f else 1f
        grid.animate().cancel()
        grid.animate().alpha(0f).translationX(dp(24) * direction).setDuration(110)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
                showingApps = toApps
                pageTransitioning = false
                if (search.text.isNotEmpty()) search.setText("") else render()
                grid.alpha = 0f
                grid.translationX = dp(24) * -direction
                grid.animate().alpha(1f).translationX(0f).setDuration(190)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }.start()
    }

    private fun tab(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.WHITE)
        background = bg(0xff303642.toInt(), 20); setOnClickListener { action() }
    }

    private fun apps(): List<ResolveInfo> = packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in hidden() }
        .distinctBy { it.activityInfo.packageName }
        .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

    private fun render() {
        if (!::grid.isInitialized) return
        grid.columnCount = if (showingApps) drawerColumns else columns
        grid.removeAllViews()
        sectionHeaders.clear()
        if (::alphabetRail.isInitialized) alphabetRail.visibility = if (showingApps && search.text.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val all = apps()
        val query = search.text.toString()
        val folderMembers = folderMap().values.flatten().toSet()
        val home = pinned().filter { it !in folderMembers }
        val chosen = if (showingApps || query.isNotEmpty()) all.filter { info ->
                         info.loadLabel(packageManager).toString().contains(query, true) ||
                         info.activityInfo.packageName.contains(query, true)
                     }
                     else all.filter { it.activityInfo.packageName in home }
        if (!showingApps && query.isEmpty()) folderMap().forEach { (name, packages) -> folderTile(name, packages, all) }
        val activeColumns = if (showingApps) drawerColumns else columns
        val width = (resources.displayMetrics.widthPixels - dp(32)) / activeColumns
        if (showingApps && query.isEmpty()) {
            chosen.groupBy { it.loadLabel(packageManager).toString().firstOrNull()?.uppercaseChar() ?: '#' }
                .toSortedMap(compareBy<Char> { if (it == '#') 27 else it.code - 'A'.code })
                .let { sections ->
                    if (prefs.getBoolean("drawer_reverse_sort", false)) sections.toSortedMap(compareByDescending<Char> { if (it == '#') 27 else it.code - 'A'.code }) else sections
                }
                .forEach { (letter, group) ->
                    appSectionHeader(letter)
                    val sortedGroup = if (prefs.getBoolean("drawer_reverse_sort", false)) group.reversed() else group
                    sortedGroup.forEach { appTile(it, width) }
                }
        } else {
            chosen.forEach { appTile(it, width) }
        }
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

    private fun appSectionHeader(letter: Char) {
        val header = TextView(this).apply {
            text = letter.toString()
            textSize = 13f
            setTextColor(0xffaebbd6.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        sectionHeaders[letter] = header
        grid.addView(header, GridLayout.LayoutParams().apply {
            width = resources.displayMetrics.widthPixels - dp(32)
            height = dp(30)
            columnSpec = GridLayout.spec(0, if (showingApps) drawerColumns else columns)
        })
    }

    private fun appTile(info: ResolveInfo, width: Int) {
        val pkg = info.activityInfo.packageName
        val label = info.loadLabel(packageManager).toString()
        val drawerMode = showingApps || search.text.isNotEmpty()
        val homeSpacing = prefs.getInt("home_tile_spacing", 8)
        val tile = LinearLayout(this).apply {
            orientation = 1; gravity = Gravity.CENTER
            setPadding(dp(3), dp(if (drawerMode) 4 else homeSpacing), dp(3), dp(if (drawerMode) 4 else homeSpacing))
            setOnClickListener { animateTap(this) { launch(pkg) } }
            setOnLongClickListener { appMenu(pkg, label); true }
        }
        val drawer = showingApps
        val displayIconSize = if (drawer) drawerIconSizeDp else iconSizeDp
        val icon = TextView(this).apply {
            gravity = Gravity.CENTER
            background = if (drawer) null else homeIconBackground()
            val drawable = info.loadIcon(packageManager)
            drawable.setBounds(0, 0, dp(displayIconSize - 10), dp(displayIconSize - 10))
            setCompoundDrawables(null, drawable, null, null)
        }
        tile.addView(icon, LinearLayout.LayoutParams(dp(displayIconSize), dp(displayIconSize)))
        val itemHasLabels = if (drawerMode) showDrawerLabels else showHomeLabels
        if (itemHasLabels) tile.addView(TextView(this).apply {
            text = label; textSize = if (drawerMode) drawerLabelSize else homeLabelSize
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); maxLines = 2
        }, LinearLayout.LayoutParams(-1, dp(36)))
        grid.addView(tile, GridLayout.LayoutParams().apply {
            this.width = width
            height = dp(displayIconSize + (if (itemHasLabels) 48 else 20) + if (!drawerMode) homeSpacing * 2 else 0)
        })
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
        val isHidden = pkg in hidden()
        val options = mutableListOf("Open", if (isPinned) "Unpin from Home" else "Pin to Home", "App info", "Uninstall", if (isHidden) "Unhide app" else "Hide app", "Share app")
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
                "Hide app", "Unhide app" -> { val set = hidden(); if (isHidden) set.remove(pkg) else set.add(pkg); prefs.edit().putStringSet("hidden_apps", set).apply(); render() }
                "Share app" -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "$label\nhttps://play.google.com/store/apps/details?id=$pkg")
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share $label"))
                }
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
        val dialog = android.app.Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = 1
            setPadding(dp(18), dp(18), dp(18), dp(12))
            background = bg(0xff20242d.toInt(), 28)
            alpha = 0f
            scaleX = 0.82f
            scaleY = 0.82f
        }
        content.addView(TextView(this).apply {
            text = name; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(44)))
        val box = GridLayout(this).apply { columnCount = 3 }
        all.filter { it.activityInfo.packageName in pkgs }.forEach { info ->
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(packageManager).toString()
            val tile = LinearLayout(this).apply {
                orientation = 1; gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { closeFolderDialog(dialog, content) { animateTap(this) { launch(pkg) } } }
            }
            val iconView = TextView(this).apply {
                gravity = Gravity.CENTER
                val drawable = info.loadIcon(packageManager)
                drawable.setBounds(0, 0, dp(44), dp(44))
                setCompoundDrawables(null, drawable, null, null)
            }
            tile.addView(iconView, LinearLayout.LayoutParams(dp(64), dp(64)))
            if (showHomeLabels) tile.addView(TextView(this).apply {
                text = label; textSize = homeLabelSize; gravity = Gravity.CENTER; setTextColor(Color.WHITE); maxLines = 2
            }, LinearLayout.LayoutParams(-1, dp(34)))
            box.addView(tile, GridLayout.LayoutParams().apply { width = dp(92); height = dp(if (showHomeLabels) 108 else 78) })
        }
        content.addView(box)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        actions.addView(TextView(this).apply {
            text = "Manage"; textSize = 14f; setTextColor(0xffb8c7ff.toInt()); gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { closeFolderDialog(dialog, content) { folderManage(name) } }
        })
        actions.addView(TextView(this).apply {
            text = "Close"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { closeFolderDialog(dialog, content) }
        })
        content.addView(actions)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(dp(320), -2)
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun closeFolderDialog(dialog: android.app.Dialog, content: android.view.View, afterClose: (() -> Unit)? = null) {
        content.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f)
            .setDuration(160).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                dialog.dismiss()
                afterClose?.invoke()
            }.start()
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

    private fun homeQuickCustomize() {
        AlertDialog.Builder(this).setTitle("Home customization")
             .setItems(arrayOf("Home grid", "Home icon size", "Home label size", "Home icon shape", "Home icon background", "Change wallpaper", "More launcher settings")) { _, index ->
                when (index) {
                    0 -> homeGridPicker()
                    1 -> iconSizePicker()
                    2 -> homeLabelSizePicker()
                    3 -> homeIconShapePicker()
                    4 -> homeIconBackgroundColorPicker()
                    5 -> chooseWallpaper()
                    6 -> settings()
                }
            }.show()
    }

    private fun settings() {
        val homeLabelOption = if (showHomeLabels) "Hide home labels" else "Show home labels"
        val drawerLabelOption = if (showDrawerLabels) "Hide app drawer labels" else "Show app drawer labels"
        AlertDialog.Builder(this).setTitle("Launcher settings")
            .setItems(arrayOf("Home grid", "App drawer grid", "App sorting", "Customize dock", "Home icon size", "App drawer icon size", "Home label size", "App drawer label size", "Home spacing", "Change wallpaper", "Reset wallpaper", homeLabelOption, drawerLabelOption, "Clear home pins", "Manage hidden apps", "Dock icon size", "Dock background", if (dockShowLabels) "Hide dock labels" else "Show dock labels", "Home icon shape", "Home icon background")) { _, index ->
                when (index) {
                    0 -> homeGridPicker()
                    1 -> drawerGridPicker()
                    2 -> drawerSortPicker()
                    3 -> dockPicker()
                    4 -> iconSizePicker()
                    5 -> drawerIconSizePicker()
                    6 -> homeLabelSizePicker()
                    7 -> drawerLabelSizePicker()
                    8 -> homeSpacingPicker()
                    9 -> chooseWallpaper()
                    10 -> { prefs.edit().remove("wallpaper_uri").apply(); recreate() }
                    11 -> { prefs.edit().putBoolean("home_show_labels", !showHomeLabels).apply(); render() }
                    12 -> { prefs.edit().putBoolean("drawer_show_labels", !showDrawerLabels).apply(); render() }
                    13 -> AlertDialog.Builder(this).setTitle("Clear home pins?")
                        .setMessage("Remove all pinned apps from the Home tab?")
                        .setPositiveButton("Clear") { _, _ -> prefs.edit().putStringSet("pinned", emptySet()).apply(); render() }
                        .setNegativeButton("Cancel", null).show()
                    14 -> manageHiddenApps()
                    15 -> dockIconSizePicker()
                    16 -> dockBackgroundPicker()
                    17 -> { prefs.edit().putBoolean("dock_show_labels", !dockShowLabels).apply(); refreshDock() }
                    18 -> homeIconShapePicker()
                    19 -> homeIconBackgroundColorPicker()
                }
            }.show()
    }

    private fun manageHiddenApps() {
        val all = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL
        ).filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val labels = all.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        val current = hidden()
        val checked = BooleanArray(all.size) { all[it].activityInfo.packageName in current }
        val selected = current.toMutableSet()
        AlertDialog.Builder(this).setTitle("Hide apps")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                val pkg = all[index].activityInfo.packageName
                if (isChecked) selected.add(pkg) else selected.remove(pkg)
            }
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet("hidden_apps", selected).apply()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun homeIconBackgroundColor(): Int {
        val colors = intArrayOf(
            0xff2f3544.toInt(),
            0xff355b91.toInt(),
            0xff684a91.toInt(),
            0xff326c63.toInt(),
            0xff854b62.toInt(),
            0xff80643a.toInt()
        )
        return colors[prefs.getInt("home_icon_bg_color", 0).coerceIn(0, colors.lastIndex)]
    }

    private fun homeIconBackground(): android.graphics.drawable.Drawable? {
        val color = homeIconBackgroundColor()
        return when (prefs.getInt("home_icon_shape", 0)) {
            1 -> GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            2 -> bg(color, 24)
            3 -> null
            else -> bg(color, 18)
        }
    }

    private fun homeIconShapePicker() {
        val options = arrayOf("Rounded square", "Circle", "Squircle", "No background")
        val current = prefs.getInt("home_icon_shape", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Home icon shape")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("home_icon_shape", which).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun homeIconBackgroundColorPicker() {
        val options = arrayOf("Slate", "Blue", "Purple", "Teal", "Rose", "Amber")
        val current = prefs.getInt("home_icon_bg_color", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Home icon background")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("home_icon_bg_color", which).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
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

    private fun drawerGridPicker() {
        val options = arrayOf("4 columns", "5 columns", "6 columns", "7 columns")
        val values = intArrayOf(4, 5, 6, 7)
        val current = values.indexOf(drawerColumns).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("App drawer grid")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("drawer_columns", values[which]).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }


    private fun drawerSortPicker() {
        val options = arrayOf("A → Z", "Z → A")
        val current = if (prefs.getBoolean("drawer_reverse_sort", false)) 1 else 0
        AlertDialog.Builder(this).setTitle("App drawer sorting")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putBoolean("drawer_reverse_sort", which == 1).apply()
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

    private fun homeLabelSizePicker() {
        val options = arrayOf("Small (9.5 sp)", "Medium (10.5 sp)", "Large (11.5 sp)")
        val values = floatArrayOf(9.5f, 10.5f, 11.5f)
        val current = values.indexOfFirst { it == homeLabelSize }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Home label size")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putFloat("home_label_size_sp", values[which]).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun drawerLabelSizePicker() {
        val options = arrayOf("Small (10.5 sp)", "Medium (11.5 sp)", "Large (12.5 sp)")
        val values = floatArrayOf(10.5f, 11.5f, 12.5f)
        val current = values.indexOfFirst { it == drawerLabelSize }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("App drawer label size")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putFloat("drawer_label_size_sp", values[which]).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun drawerIconSizePicker() {
        val sizes = arrayOf("Small (48 dp)", "Medium (64 dp)", "Large (72 dp)", "Extra large (80 dp)")
        val values = intArrayOf(48, 64, 72, 80)
        val current = values.indexOf(drawerIconSizeDp).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("App drawer icon size")
            .setSingleChoiceItems(sizes, current) { dialog, which ->
                prefs.edit().putInt("drawer_icon_size_dp", values[which]).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }


    private fun homeSpacingPicker() {
        val options = arrayOf("Compact", "Standard", "Relaxed")
        val values = intArrayOf(4, 8, 12)
        val current = values.indexOf(prefs.getInt("home_tile_spacing", 8)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Home spacing")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("home_tile_spacing", values[which]).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockIconSizePicker() {
        val options = arrayOf("Small (28 dp)", "Medium (34 dp)", "Large (40 dp)")
        val values = intArrayOf(28, 34, 40)
        val current = values.indexOf(dockIconSizeDp).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Dock icon size")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_icon_size_dp", values[which]).apply()
                dialog.dismiss()
                refreshDock()
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

    private fun dockBackgroundPicker() {
        val options = arrayOf("Glass panel", "Transparent", "Outline", "Dark panel")
        val current = prefs.getInt("dock_background_style", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Dock background")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_background_style", which).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockBackground(): android.graphics.drawable.Drawable? {
        return when (prefs.getInt("dock_background_style", 0)) {
            1 -> null
            2 -> GradientDrawable().apply {
                setColor(0x222f3748)
                setStroke(dp(1), 0xff596579.toInt())
                cornerRadius = dp(24).toFloat()
            }
            3 -> bg(0xff151922.toInt(), 24)
            else -> bg(0xff242833.toInt(), 24)
        }
    }

    private fun refreshDock() {
        var ancestor: android.view.ViewParent? = grid.parent
        while (ancestor != null && ancestor !is LinearLayout) ancestor = ancestor.parent
        val parent = ancestor as? LinearLayout ?: return
        parent.removeViewAt(parent.childCount - 1)
        val bar = LinearLayout(this).apply {
            orientation = 0; gravity = Gravity.CENTER; background = dockBackground()
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(dockBarHeightDp)))
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
                includeFontPadding = false
                maxLines = 1
                text = if (dockShowLabels) info.loadLabel(packageManager).toString() else ""
                textSize = 10f
                setTextColor(0xffe6eaf2.toInt())
                contentDescription = info.loadLabel(packageManager).toString()
                val drawable = info.loadIcon(packageManager); drawable.setBounds(0, 0, dp(dockIconSizeDp), dp(dockIconSizeDp))
                setCompoundDrawables(null, drawable, null, null)
                setOnClickListener { animateTap(this) { launch(pkg) } }
                setOnLongClickListener { appMenu(pkg, info.loadLabel(packageManager).toString()); true }
            }, LinearLayout.LayoutParams(0, dp(if (dockShowLabels) dockIconSizeDp + 20 else 44), 1f))
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
