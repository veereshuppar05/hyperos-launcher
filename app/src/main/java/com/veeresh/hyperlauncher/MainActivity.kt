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
    private val showFolderLabels: Boolean get() = prefs.getBoolean("folder_show_labels", true)
    private val showFolderAppLabels: Boolean get() = prefs.getBoolean("folder_app_show_labels", showHomeLabels)
    private val folderAppLabelSize: Float get() = prefs.getFloat("folder_app_label_size_sp", homeLabelSize)
    private val folderLabelSize: Float get() = prefs.getFloat("folder_label_size_sp", 10.5f)
    private val iconSizeDp: Int get() = prefs.getInt("icon_size_dp", 58)
    private val drawerIconSizeDp: Int get() = prefs.getInt("drawer_icon_size_dp", 64)
    private val dockIconSizeDp: Int get() = prefs.getInt("dock_icon_size_dp", 34)
    private val dockShowLabels: Boolean get() = prefs.getBoolean("dock_show_labels", false)
    private val dockBarHeightDp: Int get() = (if (dockShowLabels) dockIconSizeDp + 32 else 60) + intArrayOf(-8, 0, 8)[prefs.getInt("dock_height_style", 1).coerceIn(0, 2)]
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
            gravity = Gravity.CENTER; setTextColor(if (drawerMode) drawerLabelColor() else homeLabelColor()); maxLines = 2
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
        val previewColumns = prefs.getInt("folder_preview_grid", 2).coerceIn(2, 4)
        val previewCellSize = when (previewColumns) {
            3 -> 16
            4 -> 12
            else -> 24
        }
        val preview = GridLayout(this).apply {
            columnCount = previewColumns
            background = folderPreviewBackground()
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val previewApps = all.filter { it.activityInfo.packageName in pkgs }
            .let { if (prefs.getBoolean("folder_reverse_sort", false)) it.reversed() else it }
        previewApps.take(previewColumns * previewColumns).forEach { info ->
            val miniIcon = android.widget.ImageView(this).apply {
                setImageDrawable(info.loadIcon(packageManager))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            preview.addView(miniIcon, GridLayout.LayoutParams().apply {
                this.width = dp(previewCellSize); this.height = dp(previewCellSize)
            })
        }
        tile.addView(preview, LinearLayout.LayoutParams(dp(58), dp(58)))
        if (showFolderLabels) {
            val folderLabel = TextView(this)
            folderLabel.text = name
            folderLabel.textSize = folderLabelSize
            folderLabel.gravity = Gravity.CENTER
            folderLabel.setTextColor(Color.WHITE)
            folderLabel.maxLines = 1
            tile.addView(folderLabel, LinearLayout.LayoutParams(-1, dp(34)))
        }
        grid.addView(tile, GridLayout.LayoutParams().apply {
            this.width = width
            height = dp(if (showFolderLabels) 102 else 78)
        })
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
            background = openedFolderBackground()
            alpha = 0f
            scaleX = 0.82f
            scaleY = 0.82f
        }
        content.addView(TextView(this).apply {
            text = name; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(44)))
        val folderColumns = prefs.getInt("folder_open_grid", 3).coerceIn(3, 5)
        val dialogWidthDp = if (folderColumns == 5) 360 else 320
        val cellWidthDp = (dialogWidthDp - 36) / folderColumns
        val savedFolderIconSize = prefs.getInt("folder_open_icon_size", 0)
        val iconSize = if (savedFolderIconSize in 32..56) savedFolderIconSize else when (folderColumns) {
            4 -> 36
            5 -> 32
            else -> 44
        }
        val folderApps = all.filter { it.activityInfo.packageName in pkgs }
            .let { if (prefs.getBoolean("folder_reverse_sort", false)) it.reversed() else it }
        val tileHeightDp = iconSize + 12 + (if (showFolderAppLabels) 34 else 0) + 12

        val folderSearch = EditText(this).apply {
            hint = "Search apps in $name"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(0xff9ba3b2.toInt())
            setPadding(dp(14), 0, dp(14), 0)
            background = bg(0xff303643.toInt(), 16)
        }
        content.addView(folderSearch, LinearLayout.LayoutParams(-1, dp(42)).apply {
            bottomMargin = dp(10)
        })

        val box = GridLayout(this).apply { columnCount = folderColumns }
        val folderScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(box, FrameLayout.LayoutParams(-1, -2))
        }
        content.addView(folderScroll, LinearLayout.LayoutParams(-1, dp(120)))

        fun updateFolderGrid(rawQuery: String) {
            val query = rawQuery.trim()
            val visibleApps = folderApps.filter { info ->
                val label = info.loadLabel(packageManager).toString()
                val pkg = info.activityInfo.packageName
                query.isEmpty() || label.contains(query, ignoreCase = true) ||
                    pkg.contains(query, ignoreCase = true)
            }
            box.removeAllViews()
            visibleApps.forEach { info ->
                val pkg = info.activityInfo.packageName
                val label = info.loadLabel(packageManager).toString()
                val tile = LinearLayout(this).apply {
                    orientation = 1; gravity = Gravity.CENTER
                    setPadding(dp(2), dp(6), dp(2), dp(6))
                    setOnClickListener { closeFolderDialog(dialog, content) { animateTap(this) { launch(pkg) } } }
                    setOnLongClickListener {
                        folderAppMenu(name, pkg, label, dialog, content)
                        true
                    }
                }
                val iconView = TextView(this).apply {
                    gravity = Gravity.CENTER
                    val drawable = info.loadIcon(packageManager)
                    drawable.setBounds(0, 0, dp(iconSize), dp(iconSize))
                    setCompoundDrawables(null, drawable, null, null)
                compoundDrawablePadding = dp(prefs.getInt("dock_icon_label_gap_dp", 4).coerceIn(0, 8))
                }
                tile.addView(iconView, LinearLayout.LayoutParams(dp(cellWidthDp), dp(iconSize + 12)))
                if (showFolderAppLabels) tile.addView(TextView(this).apply {
                    text = label
                    textSize = if (folderColumns == 5) minOf(folderAppLabelSize, 9f) else folderAppLabelSize
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(dp(cellWidthDp - 4), dp(34)))
                box.addView(tile, GridLayout.LayoutParams().apply {
                    width = dp(cellWidthDp)
                    height = dp(tileHeightDp)
                })
            }
            if (visibleApps.isEmpty()) {
                val empty = TextView(this).apply {
                    text = if (folderApps.isEmpty()) "This folder is empty" else "No matching apps"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(0xffaeb7c7.toInt())
                }
                box.addView(empty, GridLayout.LayoutParams().apply {
                    width = dp(dialogWidthDp - 36)
                    height = dp(90)
                    columnSpec = GridLayout.spec(0, folderColumns)
                })
            }
            val folderRows = ((visibleApps.size + folderColumns - 1) / folderColumns).coerceAtLeast(1)
            val desiredGridHeight = dp(folderRows * tileHeightDp)
            val maxGridHeight = (resources.displayMetrics.heightPixels - dp(300)).coerceAtLeast(dp(140))
            folderScroll.layoutParams = (folderScroll.layoutParams as LinearLayout.LayoutParams).apply {
                height = minOf(desiredGridHeight, maxGridHeight)
            }
            folderScroll.scrollTo(0, 0)
            folderScroll.requestLayout()
        }

        folderSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateFolderGrid(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        updateFolderGrid("")

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
        dialog.window?.setLayout(dp(dialogWidthDp), -2)
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun folderAppMenu(
        folderName: String,
        pkg: String,
        label: String,
        folderDialog: android.app.Dialog,
        folderContent: android.view.View
    ) {
        val isHidden = pkg in hidden()
        val isInDock = pkg in dock()
        val options = arrayOf(
            "Open",
            "Remove from folder",
            "App info",
            if (isInDock) "Remove from Dock" else "Add to Dock",
            if (isHidden) "Unhide app" else "Hide app",
            "Share app",
            "Uninstall"
        )
        AlertDialog.Builder(this).setTitle(label).setItems(options) { _, index ->
            closeFolderDialog(folderDialog, folderContent) {
                when (options[index]) {
                    "Open" -> launch(pkg)
                    "Remove from folder" -> {
                        val folders = folderMap()
                        val members = folders[folderName]?.toMutableSet() ?: mutableSetOf()
                        members.remove(pkg)
                        if (members.isEmpty()) folders.remove(folderName) else folders[folderName] = members
                        saveFolders(folders)
                        Toast.makeText(this, "Removed $label from $folderName", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    "App info" -> startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    )
                    "Add to Dock" -> {
                        val items = dock()
                        if (items.size >= 5 && pkg !in items) {
                            Toast.makeText(this, "Dock is full (5 apps)", Toast.LENGTH_SHORT).show()
                        } else {
                            if (pkg !in items) items.add(pkg)
                            prefs.edit().putString("dock", items.take(5).joinToString(",")).apply()
                            refreshDock()
                        }
                    }
                    "Remove from Dock" -> {
                        val items = dock()
                        items.remove(pkg)
                        prefs.edit().putString("dock", items.joinToString(",")).apply()
                        refreshDock()
                    }
                    "Hide app", "Unhide app" -> {
                        val items = hidden()
                        if (isHidden) items.remove(pkg) else items.add(pkg)
                        prefs.edit().putStringSet("hidden_apps", items).apply()
                        render()
                    }
                    "Share app" -> {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "$label\nhttps://play.google.com/store/apps/details?id=$pkg")
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share $label"))
                    }
                    "Uninstall" -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                }
            }
        }.setNegativeButton("Cancel", null).show()
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
            val wallpaper = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.let { BitmapDrawable(resources, it) }
            } ?: return null
            val alphaValues = intArrayOf(0, 40, 80, 120)
            val dimAlpha = alphaValues[prefs.getInt("wallpaper_dim_level", 0).coerceIn(0, alphaValues.lastIndex)]
            if (dimAlpha == 0) wallpaper
            else android.graphics.drawable.LayerDrawable(
                arrayOf(wallpaper, android.graphics.drawable.ColorDrawable(Color.argb(dimAlpha, 0, 0, 0)))
            )
        } catch (_: Exception) { null }
    }

    private fun wallpaperDimPicker() {
        val options = arrayOf("Off", "Light", "Medium", "Strong")
        val current = prefs.getInt("wallpaper_dim_level", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Wallpaper dimming")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("wallpaper_dim_level", which).apply()
                dialog.dismiss()
                recreate()
            }.setNegativeButton("Cancel", null).show()
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
            .setItems(arrayOf("Home grid", "App drawer grid", "App sorting", "Customize dock", "Home icon size", "App drawer icon size", "Home label size", "App drawer label size", "Home spacing", "Change wallpaper", "Reset wallpaper", homeLabelOption, drawerLabelOption, "Clear home pins", "Manage hidden apps", "Dock icon size", "Dock background", if (dockShowLabels) "Hide dock labels" else "Show dock labels", "Home icon shape", "Home icon background", "Folder preview background", "Folder preview shape", "Folder preview layout", if (showFolderLabels) "Hide folder labels" else "Show folder labels", "Folder label size", "Folder app grid", "Opened folder background", "Opened folder shape", if (showFolderAppLabels) "Hide folder app labels" else "Show folder app labels", "Folder app sorting", "Opened folder icon size", "Opened folder app label size", "Wallpaper dimming", "Home icon background opacity", "Home label colour", "App drawer label colour", "Dock background shape", "Dock background opacity", "Dock label size", "Dock label colour", "Dock side padding", "Dock icon spacing", if (prefs.getBoolean("dock_label_bold", false)) "Dock label: Bold" else "Dock label: Regular", "Dock label background", "Dock icon opacity", "Dock vertical position", "Dock height", "Dock alignment", "Dock label shadow", "Dock icon-label spacing", "Dock label letter spacing", "Dock label opacity", "Dock label font style")) { _, index ->
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
                    20 -> folderPreviewBackgroundPicker()
                    21 -> folderPreviewShapePicker()
                    22 -> folderPreviewGridPicker()
                    23 -> { prefs.edit().putBoolean("folder_show_labels", !showFolderLabels).apply(); render() }
                    24 -> folderLabelSizePicker()
                    25 -> folderOpenGridPicker()
                    26 -> openedFolderBackgroundPicker()
                    27 -> openedFolderShapePicker()
                    28 -> { prefs.edit().putBoolean("folder_app_show_labels", !showFolderAppLabels).apply(); render() }
                    29 -> folderSortPicker()
                    30 -> folderOpenIconSizePicker()
                    31 -> folderOpenAppLabelSizePicker()
                    32 -> wallpaperDimPicker()
                    33 -> homeIconBackgroundOpacityPicker()
                    34 -> homeLabelColorPicker()
                    35 -> drawerLabelColorPicker()
                    36 -> dockBackgroundShapePicker()
                    37 -> dockBackgroundOpacityPicker()
                    38 -> dockLabelSizePicker()
                    39 -> appLabelColorPicker("Dock label colour", "dock_label_color")
                    40 -> dockSidePaddingPicker()
                    41 -> dockIconSpacingPicker()
                    42 -> { prefs.edit().putBoolean("dock_label_bold", !prefs.getBoolean("dock_label_bold", false)).apply(); refreshDock() }
                    43 -> dockLabelBackgroundPicker()
                    44 -> dockIconOpacityPicker()
                    45 -> dockVerticalOffsetPicker()
                    46 -> dockHeightPicker()
                    47 -> dockAlignmentPicker()
                    48 -> dockLabelShadowPicker()
                    49 -> dockIconLabelGapPicker()
                    50 -> dockLabelLetterSpacingPicker()
                    51 -> dockLabelOpacityPicker()
                    52 -> dockLabelFontPicker()
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

    private fun openedFolderBackground(): android.graphics.drawable.Drawable? {
        val colors = intArrayOf(
            0xff20242d.toInt(),
            0xff263449.toInt(),
            0xff302844.toInt(),
            0xff203a35.toInt(),
            0xff402a34.toInt(),
            0xff403322.toInt()
        )
        val colorStyle = prefs.getInt("opened_folder_background", 0)
        if (colorStyle == 6) return null
        val radius = when (prefs.getInt("opened_folder_shape", 0)) {
            1 -> 20
            2 -> 10
            3 -> 0
            else -> 28
        }
        return bg(colors[colorStyle.coerceIn(0, colors.lastIndex)], radius)
    }

    private fun openedFolderShapePicker() {
        val options = arrayOf("Extra rounded", "Squircle", "Rounded", "Square")
        val current = prefs.getInt("opened_folder_shape", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Opened folder shape")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("opened_folder_shape", which).apply()
                dialog.dismiss()
                Toast.makeText(this, "Folder shape updated", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun openedFolderBackgroundPicker() {
        val options = arrayOf("Dark", "Blue", "Purple", "Teal", "Rose", "Amber", "Transparent")
        val current = prefs.getInt("opened_folder_background", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Opened folder background")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("opened_folder_background", which).apply()
                dialog.dismiss()
                Toast.makeText(this, "Folder background updated", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun folderPreviewBackground(): android.graphics.drawable.Drawable? {
        val colors = intArrayOf(
            0xff394255.toInt(),
            0xff355b91.toInt(),
            0xff684a91.toInt(),
            0xff326c63.toInt(),
            0xff854b62.toInt(),
            0xff80643a.toInt()
        )
        val style = prefs.getInt("folder_preview_background", 0)
        if (style == 6) return null
        val color = colors[style.coerceIn(0, colors.lastIndex)]
        return when (prefs.getInt("folder_preview_shape", 0)) {
            1 -> GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            2 -> bg(color, 24)
            3 -> null
            else -> bg(color, 18)
        }
    }

    private fun folderSortPicker() {
        val options = arrayOf("A → Z", "Z → A")
        val current = if (prefs.getBoolean("folder_reverse_sort", false)) 1 else 0
        AlertDialog.Builder(this).setTitle("Folder app sorting")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putBoolean("folder_reverse_sort", which == 1).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun folderOpenAppLabelSizePicker() {
        val options = arrayOf("Small (9 sp)", "Medium (10 sp)", "Large (11 sp)", "Extra large (12 sp)")
        val values = floatArrayOf(9f, 10f, 11f, 12f)
        val current = values.indexOfFirst { it == folderAppLabelSize }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Opened folder app label size")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putFloat("folder_app_label_size_sp", values[which]).apply()
                dialog.dismiss()
                Toast.makeText(this, "Folder app label size updated", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun folderOpenIconSizePicker() {
        val options = arrayOf("Automatic", "Small (32 dp)", "Medium (40 dp)", "Large (48 dp)", "Extra large (56 dp)")
        val values = intArrayOf(0, 32, 40, 48, 56)
        val current = values.indexOf(prefs.getInt("folder_open_icon_size", 0)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Opened folder icon size")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("folder_open_icon_size", values[which]).apply()
                dialog.dismiss()
                Toast.makeText(this, "Folder icon size updated", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun folderOpenGridPicker() {
        val options = arrayOf("3 columns", "4 columns", "5 columns")
        val values = intArrayOf(3, 4, 5)
        val current = values.indexOf(prefs.getInt("folder_open_grid", 3)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Opened folder grid")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("folder_open_grid", values[which]).apply()
                dialog.dismiss()
                Toast.makeText(this, "Folder layout updated", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun folderLabelSizePicker() {
        val options = arrayOf("Small (9.5 sp)", "Medium (10.5 sp)", "Large (11.5 sp)")
        val values = floatArrayOf(9.5f, 10.5f, 11.5f)
        val current = values.indexOfFirst { it == folderLabelSize }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Folder label size")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putFloat("folder_label_size_sp", values[which]).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun folderPreviewGridPicker() {
        val options = arrayOf("2 × 2 (up to 4 apps)", "3 × 3 (up to 9 apps)", "4 × 4 (up to 16 apps)")
        val values = intArrayOf(2, 3, 4)
        val current = values.indexOf(prefs.getInt("folder_preview_grid", 2)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Folder preview layout")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("folder_preview_grid", values[which]).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun folderPreviewShapePicker() {
        val options = arrayOf("Rounded square", "Circle", "Squircle", "No background")
        val current = prefs.getInt("folder_preview_shape", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Folder preview shape")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("folder_preview_shape", which).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun folderPreviewBackgroundPicker() {
        val options = arrayOf("Slate", "Blue", "Purple", "Teal", "Rose", "Amber", "Transparent")
        val current = prefs.getInt("folder_preview_background", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Folder preview background")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("folder_preview_background", which).apply()
                dialog.dismiss()
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
        val selected = colors[prefs.getInt("home_icon_bg_color", 0).coerceIn(0, colors.lastIndex)]
        val opacity = intArrayOf(100, 75, 50, 25)[prefs.getInt("home_icon_bg_opacity", 0).coerceIn(0, 3)]
        return Color.argb(
            (Color.alpha(selected) * opacity) / 100,
            Color.red(selected),
            Color.green(selected),
            Color.blue(selected)
        )
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

    private fun homeIconBackgroundOpacityPicker() {
        val options = arrayOf("100% — Solid", "75% — Slightly transparent", "50% — Semi-transparent", "25% — Subtle")
        val current = prefs.getInt("home_icon_bg_opacity", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Home icon background opacity")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("home_icon_bg_opacity", which).apply()
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
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

    private fun homeLabelColor(): Int = appLabelColor("home_label_color")

    private fun drawerLabelColor(): Int = appLabelColor("drawer_label_color")

    private fun appLabelColor(key: String): Int {
        val colors = intArrayOf(
            Color.WHITE,
            0xffe4e8f0.toInt(),
            0xffa8c7fa.toInt(),
            0xffb0bec5.toInt(),
            0xffffd180.toInt()
        )
        return colors[prefs.getInt(key, 0).coerceIn(0, colors.lastIndex)]
    }

    private fun homeLabelColorPicker() {
        appLabelColorPicker("Home label colour", "home_label_color")
    }

    private fun drawerLabelColorPicker() {
        appLabelColorPicker("App drawer label colour", "drawer_label_color")
    }

    private fun appLabelColorPicker(title: String, key: String) {
        val options = arrayOf("White", "Soft white", "Sky blue", "Silver", "Warm gold")
        val current = prefs.getInt(key, 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt(key, which).apply()
                dialog.dismiss()
                if (key == "dock_label_color") refreshDock() else render()
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

    private fun dockIconLabelGapPicker() {
        val options = arrayOf("Tight", "Standard", "Wide")
        val values = intArrayOf(0, 4, 8)
        val current = values.indexOf(prefs.getInt("dock_icon_label_gap_dp", 4)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Dock icon-label spacing")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_icon_label_gap_dp", values[which]).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockLabelOpacityPicker() {
        val options = arrayOf("50%", "75%", "100%")
        val values = intArrayOf(128, 191, 255)
        val current = values.indexOf(prefs.getInt("dock_label_alpha", 255)).let { if (it < 0) 2 else it }
        AlertDialog.Builder(this).setTitle("Dock label opacity")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_label_alpha", values[which]).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }


    private fun dockLabelFontPicker() {
        val options = arrayOf("Default Sans", "Serif", "Monospace", "Sans Condensed")
        val values = arrayOf("sans", "serif", "mono", "condensed")
        val current = values.indexOf(prefs.getString("dock_label_font", "sans")).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Dock label font style")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putString("dock_label_font", values[which]).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockLabelLetterSpacingPicker() {
        val options = arrayOf("Normal", "Medium", "Wide")
        val values = floatArrayOf(0f, 0.05f, 0.1f)
        val saved = prefs.getFloat("dock_label_letter_spacing", 0f).coerceIn(0f, 0.1f)
        val current = if (saved < 0.025f) 0 else if (saved < 0.075f) 1 else 2
        AlertDialog.Builder(this).setTitle("Dock label letter spacing")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putFloat("dock_label_letter_spacing", values[which]).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }


    private fun dockLabelShadowPicker() {
        val options = arrayOf("Off", "Subtle", "Strong")
        val current = prefs.getInt("dock_label_shadow", 0).coerceIn(0, 2)
        AlertDialog.Builder(this).setTitle("Dock label shadow")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_label_shadow", which).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockAlignmentPicker() {
        val options = arrayOf("Left", "Center", "Right")
        val current = prefs.getInt("dock_alignment", 1).coerceIn(0, 2)
        AlertDialog.Builder(this).setTitle("Dock alignment")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_alignment", which).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockHeightPicker() {
        val options = arrayOf("Compact", "Standard", "Tall")
        val current = prefs.getInt("dock_height_style", 1).coerceIn(0, 2)
        AlertDialog.Builder(this).setTitle("Dock height")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_height_style", which).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockVerticalOffsetPicker() {
        val values = intArrayOf(-6, 0, 6)
        val options = arrayOf("Up", "Center", "Down")
        val current = prefs.getInt("dock_vertical_offset", 1).coerceIn(0, 2)
        AlertDialog.Builder(this).setTitle("Dock vertical position")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_vertical_offset", which).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockIconOpacityPicker() {
        val options = arrayOf("50%", "75%", "100%")
        val values = intArrayOf(128, 191, 255)
        val current = values.indexOf(prefs.getInt("dock_icon_alpha", 255)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Dock icon opacity")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_icon_alpha", values[which]).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockLabelBackgroundPicker() {
        val options = arrayOf("None", "Subtle pill", "Dark capsule")
        val current = prefs.getInt("dock_label_background", 0).coerceIn(0, 2)
        AlertDialog.Builder(this).setTitle("Dock label background")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_label_background", which).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockIconSpacingPicker() {
        val options = arrayOf("Tight", "Standard", "Spacious")
        val current = prefs.getInt("dock_icon_spacing", 1).coerceIn(0, 2)
        AlertDialog.Builder(this).setTitle("Dock icon spacing")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_icon_spacing", which).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockSidePaddingPicker() {
        val options = arrayOf("Compact (2 dp)", "Standard (6 dp)", "Wide (12 dp)")
        val current = prefs.getInt("dock_side_padding", 1).coerceIn(0, 2)
        AlertDialog.Builder(this).setTitle("Dock side padding")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_side_padding", which).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockLabelSizePicker() {
        val options = arrayOf("Small (8 sp)", "Medium (10 sp)", "Large (12 sp)", "Extra large (14 sp)")
        val values = floatArrayOf(8f, 10f, 12f, 14f)
        val current = values.indexOfFirst { it == prefs.getFloat("dock_label_size_sp", 10f) }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Dock label size")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putFloat("dock_label_size_sp", values[which]).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockIconSizePicker() {
        val options = arrayOf("Extra small (24 dp)", "Small (28 dp)", "Compact (32 dp)", "Medium (34 dp)", "Medium-large (36 dp)", "Large (40 dp)", "Extra large (44 dp)")
        val values = intArrayOf(24, 28, 32, 34, 36, 40, 44)
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

    private fun dockBackgroundOpacityPicker() {
        val options = arrayOf("100% — Solid", "75% — Slightly transparent", "50% — Semi-transparent", "25% — Subtle")
        val current = prefs.getInt("dock_background_opacity", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Dock background opacity")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_background_opacity", which).apply()
                dialog.dismiss()
                refreshDock()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dockBackgroundShapePicker() {
        val options = arrayOf("Rounded (default)", "Soft rounded", "Subtle corners", "Square")
        val current = prefs.getInt("dock_background_shape", 0).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(this).setTitle("Dock background shape")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("dock_background_shape", which).apply()
                dialog.dismiss()
                refreshDock()
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
        val radius = intArrayOf(24, 18, 10, 2)[
            prefs.getInt("dock_background_shape", 0).coerceIn(0, 3)
        ]
        val opacity = intArrayOf(100, 75, 50, 25)[
            prefs.getInt("dock_background_opacity", 0).coerceIn(0, 3)
        ]
        fun applyOpacity(color: Int): Int = Color.argb(
            Color.alpha(color) * opacity / 100,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
        return when (prefs.getInt("dock_background_style", 0)) {
            1 -> null
            2 -> GradientDrawable().apply {
                setColor(applyOpacity(0x222f3748))
                setStroke(dp(1), applyOpacity(0xff596579.toInt()))
                cornerRadius = dp(radius).toFloat()
            }
            3 -> bg(applyOpacity(0xff151922.toInt()), radius)
            else -> bg(applyOpacity(0xff242833.toInt()), radius)
        }
    }
    private fun refreshDock() {
        var ancestor: android.view.ViewParent? = grid.parent
        while (ancestor != null && ancestor !is LinearLayout) ancestor = ancestor.parent
        val parent = ancestor as? LinearLayout ?: return
        parent.removeViewAt(parent.childCount - 1)
        val bar = LinearLayout(this).apply {
            orientation = 0; gravity = intArrayOf(Gravity.LEFT or Gravity.CENTER_VERTICAL, Gravity.CENTER, Gravity.RIGHT or Gravity.CENTER_VERTICAL)[prefs.getInt("dock_alignment", 1).coerceIn(0, 2)]; background = dockBackground()
            val sidePadding = intArrayOf(2, 6, 12)[prefs.getInt("dock_side_padding", 1).coerceIn(0, 2)]
            setPadding(dp(sidePadding), dp(6), dp(sidePadding), dp(6))
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
                translationY = dp(intArrayOf(-6, 0, 6)[prefs.getInt("dock_vertical_offset", 1).coerceIn(0, 2)]).toFloat()
                includeFontPadding = false
                maxLines = 1
                text = if (dockShowLabels) info.loadLabel(packageManager).toString() else ""
                textSize = prefs.getFloat("dock_label_size_sp", 10f)
                letterSpacing = prefs.getFloat("dock_label_letter_spacing", 0f).coerceIn(0f, 0.1f)
                val labelColor = appLabelColor("dock_label_color")
                setTextColor(Color.argb(prefs.getInt("dock_label_alpha", 255).coerceIn(0, 255), Color.red(labelColor), Color.green(labelColor), Color.blue(labelColor)))
                background = when (prefs.getInt("dock_label_background", 0).coerceIn(0, 2)) {
                    1 -> bg(Color.argb(51, 77, 86, 104), 6)
                    2 -> bg(Color.rgb(34, 40, 51), 8)
                    else -> null
                }
                setPadding(dp(2), dp(1), dp(2), dp(1))
                setTypeface(android.graphics.Typeface.create(when (prefs.getString("dock_label_font", "sans")) {
                    "serif" -> "serif"
                    "mono" -> "monospace"
                    "condensed" -> "sans-serif-condensed"
                    else -> "sans-serif"
                }, if (prefs.getBoolean("dock_label_bold", false)) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL))
                when (prefs.getInt("dock_label_shadow", 0).coerceIn(0, 2)) {
                    1 -> setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
                    2 -> setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0xff000000.toInt())
                    else -> setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                }
                contentDescription = info.loadLabel(packageManager).toString()
                val drawable = info.loadIcon(packageManager); drawable.alpha = prefs.getInt("dock_icon_alpha", 255).coerceIn(0, 255); drawable.setBounds(0, 0, dp(dockIconSizeDp), dp(dockIconSizeDp))
                setCompoundDrawables(null, drawable, null, null)
                setOnClickListener { animateTap(this) { launch(pkg) } }
                setOnLongClickListener { appMenu(pkg, info.loadLabel(packageManager).toString()); true }
            }, LinearLayout.LayoutParams(-2, dp(if (dockShowLabels) dockIconSizeDp + 20 else 44)).apply {
                val gap = intArrayOf(2, 8, 16)[prefs.getInt("dock_icon_spacing", 1).coerceIn(0, 2)]
                leftMargin = dp(gap); rightMargin = dp(gap)
            })
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
