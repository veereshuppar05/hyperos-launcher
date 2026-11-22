package com.veeresh.hyperlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
    private lateinit var root: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var search: EditText
    private var showingApps = false
    private val columns = 5
    private val prefs by lazy { getSharedPreferences("hyperlauncher", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 20, 27)
        window.navigationBarColor = Color.rgb(18, 20, 27)
        buildUi()
        refreshGrid()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun pinned(): MutableSet<String> = prefs.getStringSet("pinned", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(10))
            setBackgroundColor(Color.rgb(18, 20, 27))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "Hello"; textSize = 30f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val sub = TextView(this).apply { text = "  HyperLauncher"; textSize = 13f; setTextColor(Color.rgb(160,170,188)) }
        header.addView(title); header.addView(sub)
        root.addView(header, LinearLayout.LayoutParams(-1, dp(48)))

        search = EditText(this).apply {
            hint = "Search apps"; setHintTextColor(Color.rgb(155,163,178)); setTextColor(Color.WHITE)
            textSize = 15f; setSingleLine(true); setPadding(dp(16),0,dp(16),0)
            background = rounded(Color.rgb(39,43,54),24)
        }
        val searchLp = LinearLayout.LayoutParams(-1,dp(48)); searchLp.topMargin=dp(14)
        root.addView(search,searchLp)

        val tabs = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(0,dp(12),0,dp(8)) }
        tabs.addView(tabButton("Home",false) { showingApps=false; search.setText(""); refreshGrid() }, LinearLayout.LayoutParams(0,dp(40),1f))
        tabs.addView(tabButton("Apps",true) { showingApps=true; refreshGrid() }, LinearLayout.LayoutParams(0,dp(40),1f))
        root.addView(tabs)

        val scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false }
        grid=GridLayout(this).apply { columnCount=columns; useDefaultMargins=false; alignmentMode=GridLayout.ALIGN_BOUNDS; setPadding(0,dp(6),0,dp(20)) }
        scroll.addView(grid)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))

        val dock=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; background=rounded(Color.rgb(36,40,51),26); setPadding(dp(8),dp(8),dp(8),dp(8)) }
        val dockLabel=TextView(this).apply { text="Home  •  Apps  •  Pin apps from the drawer"; textSize=12f; setTextColor(Color.rgb(205,211,223)); gravity=Gravity.CENTER }
        dock.addView(dockLabel,LinearLayout.LayoutParams(-1,dp(42)))
        root.addView(dock,LinearLayout.LayoutParams(-1,dp(58)))
        setContentView(root)

        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){ if(s?.isNotEmpty()==true) showingApps=true; refreshGrid() }
            override fun afterTextChanged(s:Editable?){}
        })
    }

    private fun tabButton(label:String,selected:Boolean,onClick:()->Unit):TextView = TextView(this).apply {
        text=label; gravity=Gravity.CENTER; textSize=14f; setTextColor(Color.WHITE)
        background=rounded(if(selected) Color.rgb(62,77,105) else Color.rgb(36,40,51),20)
        setOnClickListener { onClick() }
    }

    private fun refreshGrid() {
        if (!::grid.isInitialized) return
        grid.removeAllViews()
        val pm=packageManager
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all=pm.queryIntentActivities(intent,PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName!=packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        val query=search.text?.toString().orEmpty()
        val selected=if(showingApps || query.isNotEmpty()) all.filter { it.loadLabel(pm).toString().contains(query,true) }
            else all.filter { it.activityInfo.packageName in pinned() }
        if(!showingApps && query.isEmpty() && selected.isEmpty()) {
            val empty=TextView(this).apply { text="Your home screen is empty\nOpen Apps and long-press an app to pin it here."; setTextColor(Color.rgb(190,198,212)); textSize=14f; gravity=Gravity.CENTER; setPadding(dp(20),dp(30),dp(20),dp(30)) }
            grid.addView(empty,GridLayout.LayoutParams().apply { width=resources.displayMetrics.widthPixels-dp(32); height=dp(160) })
            return
        }
        val cellWidth=(resources.displayMetrics.widthPixels-dp(32))/columns
        selected.forEach { info ->
            val pkg=info.activityInfo.packageName
            val label=info.loadLabel(pm).toString()
            val tile=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(2),dp(8),dp(2),dp(8))
                isClickable=true; isFocusable=true; background=rounded(Color.TRANSPARENT,16)
                setOnClickListener { launch(pkg) }
                setOnLongClickListener { showAppMenu(pkg,label); true }
            }
            val icon=TextView(this).apply {
                gravity=Gravity.CENTER; background=rounded(Color.rgb(47,53,68),20)
                val d=info.loadIcon(pm); d.setBounds(0,0,dp(48),dp(48)); setCompoundDrawables(null,d,null,null)
            }
            tile.addView(icon,LinearLayout.LayoutParams(dp(58),dp(58)))
            val name=TextView(this).apply { text=label; textSize=10.5f; setTextColor(Color.WHITE); gravity=Gravity.CENTER; maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(0,dp(6),0,0) }
            tile.addView(name,LinearLayout.LayoutParams(-1,dp(34)))
            grid.addView(tile,GridLayout.LayoutParams().apply { width=cellWidth; height=dp(102); setMargins(0,0,0,dp(2)) })
        }
    }

    private fun launch(pkg:String) {
        val i=packageManager.getLaunchIntentForPackage(pkg)
        if(i!=null) startActivity(i) else Toast.makeText(this,"App cannot be opened",Toast.LENGTH_SHORT).show()
    }

    private fun showAppMenu(pkg:String,label:String) {
        val isPinned=pkg in pinned()
        val actions=if(isPinned) arrayOf("Open","Remove from Home","App info","Uninstall") else arrayOf("Open","Pin to Home","App info","Uninstall")
        android.app.AlertDialog.Builder(this).setTitle(label).setItems(actions){_,which->
            when(which){
                0->launch(pkg)
                1->{val set=pinned(); if(isPinned) set.remove(pkg) else set.add(pkg); prefs.edit().putStringSet("pinned",set).apply(); refreshGrid(); Toast.makeText(this,if(isPinned)"Removed from Home" else "Pinned to Home",Toast.LENGTH_SHORT).show()}
                2->startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$pkg")))
                3->startActivity(Intent(Intent.ACTION_DELETE,Uri.parse("package:$pkg")))
            }
        }.show()
    }
}
