package com.canh0chua.gpempty

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val TAG = "GP_AUTOMATOR"
    
    // UI Elements
    private lateinit var btnStartStop: Button
    private lateinit var btnShowLogs: Button
    private lateinit var rgDeleteMode: RadioGroup
    private lateinit var rbSkip: RadioButton
    private lateinit var rbDeleteAll: RadioButton
    private lateinit var tvSkipDays: TextView
    
    // Run Limit & Schedule UI
    // Schedule UI
    private lateinit var swSchedule9pm: Switch
    private lateinit var swShowWarning: Switch
    private lateinit var tvWarningBanner: TextView

    // Settings
    private val activityLogs = mutableListOf<String>()
    private var isAutomationActive = false
    private var deleteMode = "SKIP" // "SKIP" or "ALL"
    private var skipDays = 1 // Fixed to 1 day (Today)
    
    private var schedule9pmEnabled = false
    private var lastRunTimestamp: Long = 0
    private var gracePeriodStart: Long = 0

    // Scheduling Handler
    private val handler = Handler(Looper.getMainLooper())
    private val scheduleRunnable = object : Runnable {
        override fun run() {
            checkSchedule()
            handler.postDelayed(this, 60000) // Check every minute
        }
    }


    private val PREFS_NAME = "GPAutomatorPrefs"
    private val KEY_ACTIVE = "isAutomationActive"
    private val KEY_MODE = "deleteMode"
    private val KEY_SCHEDULE_9PM = "schedule9pmEnabled"
    private val KEY_LAST_RUN_TS = "lastRunTimestamp"
    private val KEY_SHOW_WARNING = "showWarningBanner"
    
    private var showWarningBanner = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.automation_webview)
        
        setupUI()
        loadSettings()
        updateUIState()
        
        setupWebView()
        
        // Start Scheduling Loop
        handler.post(scheduleRunnable)
        
        checkReliabilitySettings()

        // Start at Google Photos Login
        webView.loadUrl("https://photos.google.com/login")
        
        requestNotificationPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scheduleRunnable)
    }

    private fun setupUI() {
        btnStartStop = findViewById(R.id.btn_start_stop)
        btnShowLogs = findViewById(R.id.btn_show_logs)
        btnShowLogs.setOnClickListener { 
            showLogsDialog()
        }

        val btnContact: View = findViewById(R.id.btn_contact)
        btnContact.setOnClickListener { showContactDialog() }
        rgDeleteMode = findViewById(R.id.rg_delete_mode)
        rbSkip = findViewById(R.id.rb_skip)
        rbDeleteAll = findViewById(R.id.rb_delete_all)
        tvSkipDays = findViewById(R.id.tv_skip_days)
        
        // Static text now
        // tvSkipDays.text = "1d (Today)" // Removed per request

        swSchedule9pm = findViewById(R.id.sw_schedule_9pm)


        btnStartStop.setOnClickListener {
            if (!isAutomationActive) {
                // STARTING
                // checkAndResetRunCounter() // Removed
                Log.d(TAG, "Manual start")
                
                isAutomationActive = true
                saveSettings()
                addLog("Automation started (Mode: $deleteMode).")
                lastInjectedUrl = null
                
                updateWarningBannerState()
                
                // Reset Stop Flag in JS context before loading
                webView.evaluateJavascript("window.gpAutomatorStop = false;", null)
                webView.loadUrl("https://photos.google.com/")
            } else {
                // STOPPING
                isAutomationActive = false
                saveSettings()
                addLog("Automation stopped manually.")
                
                updateWarningBannerState()
                
                // KILL SWITCH: Immediately tell JS to stop
                webView.evaluateJavascript("window.gpAutomatorStop = true;", null)
                
                lastInjectedUrl = null
                // Reload to force stop and clean state
                webView.loadUrl("https://photos.google.com/")
            }
            updateUIState()
        }


        rgDeleteMode.setOnCheckedChangeListener { _, checkedId ->
            deleteMode = if (checkedId == R.id.rb_delete_all) "ALL" else "SKIP"
            saveSettings()
        }

        swSchedule9pm.setOnCheckedChangeListener { _, isChecked ->
            schedule9pmEnabled = isChecked
             saveSettings()
             if (isChecked) {
                 addLog("Smart Schedule Enabled. Runs every ~4h (8AM-10PM).")
                 checkSchedule() // Check immediately
                 startKeepAliveService()
             } else {
                 addLog("Schedule Disabled.")
                 stopKeepAliveService()
             }
        }
        
        
        swShowWarning = findViewById(R.id.sw_show_warning)
        tvWarningBanner = findViewById(R.id.tv_warning_banner)
        
        swShowWarning.setOnCheckedChangeListener { _, isChecked ->
            showWarningBanner = isChecked
            saveSettings() 
            updateWarningBannerState()
        }
    }
    
    private fun updateWarningBannerState() {
        if (showWarningBanner) {
            tvWarningBanner.visibility = View.VISIBLE
        } else {
            tvWarningBanner.visibility = View.GONE
        }
    }

    private fun checkSchedule() {
        if (!schedule9pmEnabled) return
        if (isAutomationActive) return 
        
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        
        // Maintenance Window: 08:00 to 22:00 (8AM - 10PM)
        if (hour < 8 || hour >= 22) return

        // Interval: 4 Hours (14400000 ms)
        val interval = 4 * 60 * 60 * 1000
        val timeSinceLast = now - lastRunTimestamp
        
        if (timeSinceLast > interval) {
             if (gracePeriodStart == 0L) {
                 gracePeriodStart = now
                 Log.d(TAG, "Smart Schedule due. Starting 2m grace period.")
                 addLog("Schedule due. Waiting 2m grace period...")
                 return
             }
             
             val graceDiff = now - gracePeriodStart
             if (graceDiff < 2 * 60 * 1000) {
                 // Still waiting
                 return
             }
        
             Log.d(TAG, "Smart Schedule Trigger! (Grace period over)")
             addLog("Smart Schedule Triggered (Catch-up run).")
             
             // Reset grace period
             gracePeriodStart = 0L
             
             // Start Automation
             isAutomationActive = true
             // Do NOT update timestamp here. Update it when run FINISHES successfully.
             saveSettings()
             
             lastInjectedUrl = null
             webView.loadUrl("https://photos.google.com/")
             updateUIState()
        } else {
             // Not due yet, reset grace period just in case
             gracePeriodStart = 0L
        }
    }
    
    // Removed checkAndResetRunCounter and updateRunsStatusLabel

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isAutomationActive = prefs.getBoolean(KEY_ACTIVE, false)
        deleteMode = prefs.getString(KEY_MODE, "SKIP") ?: "SKIP"
        skipDays = 1 // Hardcoded
        
        // autoRunEnabled = prefs.getBoolean(KEY_AUTO_RUN_ENABLED, true) 
        // autoRunLimit = prefs.getInt(KEY_AUTO_RUN_LIMIT, 2) 
        // currentRunsToday = prefs.getInt(KEY_CURRENT_RUNS, 0)
        lastRunTimestamp = prefs.getLong(KEY_LAST_RUN_TS, 0)
        schedule9pmEnabled = prefs.getBoolean(KEY_SCHEDULE_9PM, true) // Default enabled

        // Apply to UI
        findViewById<RadioButton>(if (deleteMode == "ALL") R.id.rb_delete_all else R.id.rb_skip).isChecked = true
        
        // skipDays is hardcoded, no need to update UI from prefs
        // tvSkipDays.text = "1d (Today)" // Removed
        
        swSchedule9pm.isChecked = schedule9pmEnabled
        if (schedule9pmEnabled) startKeepAliveService()
        
        loadLogs() // Load persisted logs
        
        showWarningBanner = prefs.getBoolean(KEY_SHOW_WARNING, true)
        swShowWarning.isChecked = showWarningBanner
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ACTIVE, isAutomationActive)
            .putString(KEY_MODE, deleteMode)
            // skipDays is hardcoded to 1, no need to save/load
            // Removed AutoRun keys
            .putLong(KEY_LAST_RUN_TS, lastRunTimestamp)
            .putBoolean(KEY_SCHEDULE_9PM, schedule9pmEnabled)
            .putBoolean(KEY_SHOW_WARNING, showWarningBanner)
            .apply()
    }

    private fun updateUIState() {
        if (isAutomationActive) {
            btnStartStop.text = "STOP"
            btnStartStop.backgroundTintList = getColorStateList(R.color.teal_700)
            rgDeleteMode.isEnabled = false
            for (i in 0 until rgDeleteMode.childCount) rgDeleteMode.getChildAt(i).isEnabled = false
        } else {
            btnStartStop.text = "DELETE"
            btnStartStop.backgroundTintList = getColorStateList(R.color.purple_500)
            rgDeleteMode.isEnabled = true
            for (i in 0 until rgDeleteMode.childCount) rgDeleteMode.getChildAt(i).isEnabled = true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            allowContentAccess = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                val message = consoleMessage?.message() ?: return false
                Log.d(TAG, "JS Console: $message")
                
                when {
                    message.contains("GP_AUTOMATOR:BATCH_COMPLETE") -> {
                        // User Request: Delete all should verify no photos before going to trash.
                        // So here we reload the LIBRARY to continue deleting.
                        val parts = message.split(":")
                        val count = if (parts.size > 2) parts[2] else "some"
                        addLog("Batch deleted ($count). Reloading Library...")
                        Log.d(TAG, "Batch complete ($count). Reloading Library to continue...")
                        runOnUiThread { 
                            // Update timestamp on activity to prevent immediate re-trigger if failed
                            lastRunTimestamp = System.currentTimeMillis() 
                            saveSettings()
                            
                            lastInjectedUrl = null
                            webView.loadUrl("https://photos.google.com/")
                        }
                    }
                    message.contains("GP_AUTOMATOR:FINISHED") -> {
                        // "Finished" means no items found to delete.
                        addLog("Library cleared (or limit reached).")
                        Log.d(TAG, "Library finished. Navigating to TRASH.")
                        runOnUiThread {
                            lastInjectedUrl = null
                            webView.loadUrl("https://photos.google.com/trash")
                        }
                    }
                    message.contains("GP_AUTOMATOR:TRASH_CLEANED") -> {
                        addLog("Trash emptied.")
                        Log.d(TAG, "Trash emptied. Reloading for verification...")
                        runOnUiThread {
                            lastInjectedUrl = null
                            webView.reload()
                        }
                    }
                    message.contains("GP_AUTOMATOR:TRASH_VERIFIED") -> {
                        addLog("Trash verified. Run Complete.")
                        Log.d(TAG, "Trash verified. Run Complete.")
                        runOnUiThread {
                            // Run Complete. Stop automation.
                            isAutomationActive = false
                            // SUCCESS: Update Last Run Timestamp
                            lastRunTimestamp = System.currentTimeMillis()
                            saveSettings()
                            
                            updateUIState()
                            
                            addLog("Automation Run Finished.")
                            
                            // Return to Library
                            lastInjectedUrl = null
                            webView.loadUrl("https://photos.google.com/")
                        }
                    }
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
                checkAndInject(url)
            }
        }
    }

    private var lastInjectedUrl: String? = null

    private fun checkAndInject(url: String?) {
        if (url == null) return
        if (!url.contains("photos.google.com")) return
        
        // ALWAYS INJECT WARNING BANNER (If enabled)
        // Native Banner handled by UI state updates checkAndInject
        
        // CHECK IF AUTOMATION IS ACTIVE
        if (!isAutomationActive) {
            Log.d(TAG, "Automation is INACTIVE. Skipping injection.")
            return
        }

        if (url == lastInjectedUrl) {
            Log.d(TAG, "Already injected for $url. Skipping.")
            return
        }
        
        lastInjectedUrl = url

        // INJECT SETTINGS FIRST
        val settingsScript = """
            window.GP_SETTINGS = {
                deleteMode: "$deleteMode",
                skipDays: $skipDays
            };
            console.log("GP_AUTOMATOR: Settings injected - Mode: $deleteMode, Skip: $skipDays");
        """.trimIndent()
        webView.evaluateJavascript(settingsScript, null)

        when {
            url.contains("/trash") -> {
                Log.d(TAG, "Injecting Trash script")
                injectEmptyTrashScript()
            }
            url.contains("/10") || 
            url == "https://photos.google.com/" || 
            url == "https://photos.google.com" ||
            url.contains("photos.google.com/u/") -> {
                Log.d(TAG, "Injecting Automation script")
                injectAutomationScript()
            }
            else -> {
                Log.d(TAG, "URL does not match automation triggers: $url")
            }
        }
    }

    private fun startKeepAliveService() {
        val serviceIntent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopKeepAliveService() {
        val serviceIntent = Intent(this, KeepAliveService::class.java)
        stopService(serviceIntent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun injectEmptyTrashScript() {
        val script = """
            (async function() {
                const randomSleep = (min, max) => new Promise(res => setTimeout(res, Math.random() * (max - min) + min));
                
                const simulateClickAggressive = (el) => {
                    if (!el) return;
                    console.log("GP_AUTOMATOR: Aggressively clicking: " + el.textContent.trim());
                    el.focus();
                    const opts = { bubbles: true, cancelable: true, view: window, buttons: 1 };
                    
                    // Pointer Events
                    el.dispatchEvent(new PointerEvent('pointerdown', opts));
                    el.dispatchEvent(new PointerEvent('pointerup', opts));
                    
                    // Mouse Events
                    el.dispatchEvent(new MouseEvent('mousedown', opts));
                    el.dispatchEvent(new MouseEvent('mouseup', opts));
                    
                    el.click();
                    el.dispatchEvent(new MouseEvent('click', opts));
                };

                console.log("GP_AUTOMATOR: On Trash page. Looking for Empty trash button...");
                await randomSleep(3000, 5000);

                const findEmptyBtn = () => {
                    const buttons = Array.from(document.querySelectorAll('button, [role="button"]'));
                    return buttons.find(b => {
                        const t = b.textContent.toLowerCase().trim();
                        const l = (b.getAttribute('aria-label') || "").toLowerCase();
                        return (t.includes("empty trash") || l.includes("empty trash") || t.includes("empty bin") || l.includes("empty bin")) && b.getBoundingClientRect().width > 0;
                    });
                };

                const emptyBtn = findEmptyBtn();
                if (emptyBtn) {
                    console.log("GP_AUTOMATOR: Found Empty Trash button. Triggering dialog...");
                    simulateClickAggressive(emptyBtn);
                    await randomSleep(4000, 6000);
                    
                    const findConfirm = () => {
                        // More specific selectors for the dialog button
                        const dialogButtons = Array.from(document.querySelectorAll('div[role="dialog"] button, span[jsname="V67aGc"]'));
                        return dialogButtons.find(b => {
                            const t = b.textContent.toLowerCase().trim();
                            const rect = b.getBoundingClientRect();
                            return (rect.width > 0) && (t === "empty trash" || t === "empty bin" || t === "delete" || t === "delete permanently");
                        }) || Array.from(document.querySelectorAll('button')).find(b => {
                            const t = b.textContent.toLowerCase().trim();
                            const rect = b.getBoundingClientRect();
                            return (rect.width > 0 && rect.top > 200) && (t === "empty trash" || t === "empty bin" || t === "delete");
                        });
                    };

                    const confirm = findConfirm();
                    if (confirm) {
                        console.log("GP_AUTOMATOR: Confirmation found. Finalizing...");
                        simulateClickAggressive(confirm);
                        await randomSleep(10000, 15000); 
                        console.log("GP_AUTOMATOR:TRASH_CLEANED");
                    } else {
                        console.log("GP_AUTOMATOR: Confirmation button NOT found (trying again or verified).");
                        console.log("GP_AUTOMATOR:TRASH_VERIFIED");
                    }
                } else {
                    console.log("GP_AUTOMATOR: Empty trash button NOT found (might be already empty).");
                    console.log("GP_AUTOMATOR:TRASH_VERIFIED");
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun injectAutomationScript() {
        val script = """
            {
                (async function() {
                    console.log("GP_AUTOMATOR: Script entry point reached.");
                    
                    if (window.gpAutomatorStop) {
                        console.log("GP_AUTOMATOR: Stop flag detected. Halting loop.");
                        window.gpAutomatorRunning = false; // Reset flag
                        return;
                    }

                    if (window.gpAutomatorRunning) {
                        console.log("GP_AUTOMATOR: Already running in this window. Skipping.");
                        return;
                    }
                    window.gpAutomatorRunning = true;

                    const LIMIT = 200; // Increased limit for faster clearing
                    const randomSleep = (min, max) => new Promise(res => setTimeout(res, Math.random() * (max - min) + min));
                    
                    const isSelectionMode = () => {
                        return !!document.querySelector('[aria-label="Selection mode"], [aria-label="Done"], [aria-label="Deselect all"], [aria-label="Close"], [aria-label="Exit selection mode"], [aria-label="Cancel Selection"]');
                    };
                    
                    const simulateClick = (el) => {
                        if (!el) return;
                        ['mousedown', 'mouseup', 'click'].forEach(name => {
                            el.dispatchEvent(new MouseEvent(name, {
                                bubbles: true,
                                cancelable: true,
                                view: window
                            }));
                        });
                    };

                    const simulateLongPress = async (el) => {
                        if (!el) return;
                        console.log("GP_AUTOMATOR: Simulating Long Press on:", el);
                        const opts = { bubbles: true, cancelable: true, view: window, buttons: 1 };
                        el.dispatchEvent(new PointerEvent('pointerdown', opts));
                        el.dispatchEvent(new MouseEvent('mousedown', opts));
                        await randomSleep(800, 1200); // Hold for ~1s
                        el.dispatchEvent(new MouseEvent('mouseup', opts));
                        el.dispatchEvent(new PointerEvent('pointerup', opts));
                        el.dispatchEvent(new MouseEvent('click', opts));
                    };

                    const enterSelectionMode = async () => {
                        if (isSelectionMode()) return true;
                        
                        // Strategy 1: "Select photos" button (3-dot menu or top bar)
                        let btn = document.querySelector('[aria-label="Select photos"]');
                        if (!btn) {
                            const overflow = document.querySelector('[aria-label="More options"], [aria-label="Overflow action menu"]');
                            if (overflow) {
                                simulateClick(overflow);
                                await randomSleep(1000, 1500);
                                btn = Array.from(document.querySelectorAll('[role="menuitem"], button')).find(el => el.textContent.includes("Select"));
                            }
                        }
                        if (btn) {
                            console.log("GP_AUTOMATOR: Entering selection mode via Button...");
                            simulateClick(btn);
                            await randomSleep(2000, 4000);
                            return isSelectionMode();
                        }

                        // Strategy 2: Long Press on first photo
                        console.log("GP_AUTOMATOR: Button not found. Trying Long Press on first item...");
                        const firstItem = document.querySelector('[role="checkbox"], [aria-label^="Photo"], [aria-label^="Video"]');
                        if (firstItem) {
                            await simulateLongPress(firstItem);
                            await randomSleep(2000, 3000);
                            return isSelectionMode();
                        }

                        return false;
                    };

                    const waitForGrid = () => {
                        return new Promise((resolve) => {
                            let count = 0;
                            const check = () => {
                                const items = document.querySelectorAll('[aria-label^="Photo"], [aria-label^="Video"], [aria-label^="Select"]');
                                if (items.length > 0) return resolve(true); // Return true even if just 1 item
                                
                                // Explicit Empty Check
                                const emptyText = document.body.innerText;
                                if (emptyText.includes("No photos") || emptyText.includes("Your library is empty")) {
                                    return resolve(false);
                                }

                                if (++count > 20) return resolve(false); // Increased timeout
                                setTimeout(check, 500);
                            };
                            check();
                        });
                    };

                    const getCheckmarks = () => {
                        const settings = window.GP_SETTINGS || { deleteMode: "SKIP", skipDays: 1 };
                        console.log("GP_AUTOMATOR: getCheckmarks using Settings: ", settings);

                        if (settings.deleteMode === "ALL") {
                            // In Delete All, we want everything.
                            let selector = '[role="checkbox"], [aria-checked]';
                            return Array.from(document.querySelectorAll(selector)).filter(el => {
                                const rect = el.getBoundingClientRect();
                                return rect.width > 0 && rect.height > 0 && !(el.getAttribute('aria-label')||"").toLowerCase().includes("select all");
                            });
                        }

                        // SKIP MODE - IMPROVED LOGIC
                        // 1. Identify where to start cutting off (skipThreshold).
                        // We find all "Headers" (Day/Date markers).
                        const allHeaders = Array.from(document.querySelectorAll('h1, h2, h3, [role="heading"], div.V67S5c, div[jsname="T21qX"]'))
                                                .filter(h => {
                                                    const text = h.textContent.trim().toLowerCase();
                                                    const isSidebar = h.closest('[role="navigation"], .gb_7d, aside');
                                                    const isNav = text.includes("photos") || text === "library" || text.includes("search");
                                                    
                                                    // STRICTER FILTER: Must contain a date-like keyword to be a day header.
                                                    // AND must NOT contain "memory", "highlight", "spotlight".
                                                    // Use Regex for word boundaries to avoid "mark" matching "mar"!
                                                    const dateRegex = /\b(today|yesterday|mon|tue|wed|thu|fri|sat|sun|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|20)\b/i;
                                                    const blacklist = ["memory", "memories", "highlight", "spotlight", "recent", "best of", "question mark"];
                                                    
                                                    const hasDate = dateRegex.test(text);
                                                    const isBlacklisted = blacklist.some(b => text.includes(b));

                                                    return !isSidebar && text.length > 0 && !isNav && hasDate && !isBlacklisted;
                                                });
                        
                        console.log("GP_AUTOMATOR: Found Day Headers: " + allHeaders.map(h => h.textContent.trim()).slice(0, 5).join(", ") + "...");

                        // Sort by Y position to be sure
                        allHeaders.sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);

                        let skipThreshold = -1;
                        // HARDCODED to 1 Day (Today)
                        let headersToSkip = 1;
                        
                        if (allHeaders.length > headersToSkip) {
                             const cutoffHeader = allHeaders[headersToSkip]; 
                             skipThreshold = cutoffHeader.getBoundingClientRect().top;
                             skipThreshold -= 10;
                             
                             // VISUAL DEBUG: Inject Red Line at Cutoff
                             let line = document.getElementById('gp-cutoff-line');
                             if (!line) {
                                 line = document.createElement('div');
                                 line.id = 'gp-cutoff-line';
                                 line.style.position = 'fixed';
                                 line.style.left = '0';
                                 line.style.right = '0';
                                 line.style.height = '4px';
                                 line.style.backgroundColor = 'red';
                                 line.style.zIndex = '9999';
                                 line.style.pointerEvents = 'none';
                                 
                                 // Fix CSP Violation: Do not use innerHTML
                                 line.textContent = "SKIP ABOVE THIS LINE";
                                 line.style.color = "white";
                                 line.style.fontSize = "12px";
                                 line.style.fontWeight = "bold";
                                 line.style.textAlign = "center";
                                 line.style.lineHeight = "15px"; 
                                 
                                 document.body.appendChild(line);
                             }
                             line.style.top = skipThreshold + 'px';
                        }
                        
                        // If we have enough headers, set the threshold at the top of the (N+1)th header.
                        // Everything ABOVE this line is "Recent" (Skip). Everything BELOW is "Old" (Delete).
                        if (allHeaders.length > headersToSkip) {
                             const cutoffHeader = allHeaders[headersToSkip]; // 0-indexed, so index N is the N+1th header
                             skipThreshold = cutoffHeader.getBoundingClientRect().top;
                             // Give a small buffer (e.g. -10px) to ensure the header itself represents the start
                             skipThreshold -= 10;
                        }

                        // If we haven't found enough headers yet, it means everything visible is "too new".
                        // Logic: If we see fewer headers than skip count, we Delete NOTHING on this screen.
                        if (skipThreshold === -1) {
                             console.log("GP_AUTOMATOR: Too few days visible (" + allHeaders.length + "/" + headersToSkip + "). Skipping all visible.");
                             return [];
                        }

                        console.log("GP_AUTOMATOR: Skip Threshold Y=" + skipThreshold);

                        // 2. Select Items BELOW the threshold
                        // Use the SAME robust selector as "Delete All"
                        let selector = '[role="checkbox"], [aria-checked]';
                        return Array.from(document.querySelectorAll(selector))
                                     .filter(el => {
                                         const rect = el.getBoundingClientRect();
                                         const label = (el.getAttribute('aria-label') || "").toLowerCase();
                                         const isVisible = rect.width > 0 && rect.height > 0;
                                         const isSelectAll = label.includes("select all");
                                         
                                         // Crucial: Only take items visually BELOW the threshold
                                         const isOldEnough = rect.top > skipThreshold;

                                         return isVisible && !isSelectAll && isOldEnough;
                                     });
                    };

                    console.log("GP_AUTOMATOR: Mobile loop starting...");

                    try {
                            let idleScrolls = 0;
                            let lastScrollY = window.scrollY;
                            // Increased limit significantly to handle long "Skip" zones
                            const MAX_IDLE_SCROLLS = 50; 

                            while (true) {
                                if (window.gpAutomatorStop) {
                                    console.log("GP_AUTOMATOR: Stop flag detected. Halting loop.");
                                    break;
                                }
                                if (window.location.href.includes("/photo/")) {
                                    console.log("GP_AUTOMATOR: Accidental photo view. Back...");
                                    window.history.back();
                                    await randomSleep(2000, 3000);
                                    continue;
                                }

                                const hasItems = await waitForGrid();
                                if (!hasItems) {
                                    console.log("GP_AUTOMATOR: No items detected in grid (Time out or Empty State).");
                                    break; 
                                }
                                
                                if (await enterSelectionMode()) {
                                    const checkmarks = getCheckmarks();
                                    if (checkmarks.length > 0) {
                                        idleScrolls = 0; 
                                        console.log("GP_AUTOMATOR: Selecting " + checkmarks.length + " items");
                                        
                                        // Optimization: If "Select All" is available for a day?
                                        // For now, stick to individual clicks but faster.
                                        for (let i = 0; i < Math.min(checkmarks.length, LIMIT); i++) {
                                            // Faster clicks
                                            checkmarks[i].click(); 
                                            await randomSleep(10, 50); 
                                        }
                                        await randomSleep(500, 1000);

                                        console.log("GP_AUTOMATOR: Finding Delete...");
                                        await randomSleep(1000, 2000); 

                                        const findDelete = () => {
                                            const buttons = Array.from(document.querySelectorAll('button, [role="button"], [aria-label], a[role="button"]'));
                                            let bestMatch = null;
                                            for (const b of buttons) {
                                                const label = (b.getAttribute('aria-label') || "").toLowerCase();
                                                const text = b.textContent.toLowerCase();
                                                const rect = b.getBoundingClientRect();
                                                const isSidebar = b.closest('[role="navigation"], .gb_7d, aside, [aria-label="Navigation drawer"]');
                                                const hasSize = rect.width > 0 && rect.height > 0;
                                                const inViewport = rect.top < window.innerHeight && rect.left < window.innerWidth && rect.bottom >= 0;

                                                const keywords = ["delete", "trash", "bin", "remove"];
                                                const matched = keywords.find(k => label.includes(k) || text.includes(k));

                                                if (!isSidebar && hasSize && inViewport && matched) {
                                                    if (label === "delete" || label === "move to trash" || label === "trash" || label === "move to bin") {
                                                        bestMatch = b;
                                                        break;
                                                    }
                                                    if (!bestMatch) bestMatch = b;
                                                }
                                            }
                                            return bestMatch;
                                        };

                                        let deleteBtn = findDelete();
                                        if (!deleteBtn) {
                                            const overflow = document.querySelector('[aria-label="More options"], [aria-label="Overflow action menu"]');
                                            if (overflow) {
                                                simulateClick(overflow);
                                                await randomSleep(1000, 1500);
                                                deleteBtn = findDelete();
                                            }
                                        }

                                        if (deleteBtn) {
                                            console.log("GP_AUTOMATOR: Clicking Delete button...");
                                            simulateClick(deleteBtn); // Ensure dispatch events
                                            deleteBtn.click();
                                            await randomSleep(1500, 3000);

                                            const findConfirm = () => {
                                                const allButtons = Array.from(document.querySelectorAll('button, [role="button"], div[role="button"]'));
                                                let bestConfirm = null;
                                                for (const b of allButtons) {
                                                    const rect = b.getBoundingClientRect();
                                                    const isVisible = rect.width > 0 && rect.height > 0 && rect.left >= 0 && rect.top >= 0;
                                                    if (!isVisible) continue;
                                                    const t = b.textContent.toLowerCase().trim();
                                                    const l = (b.getAttribute('aria-label') || "").toLowerCase();
                                                    if (rect.top < 60) continue;
                                                    if (t === "cancel" || l === "cancel") continue;
                                                    const inDialog = !!b.closest('[role="dialog"], .Q6S63, .K9q70b');
                                                    const keywords = ["move to trash", "delete", "move to bin", "move", "remove", "confirm", "allow"];
                                                    const matched = keywords.find(k => t.includes(k) || l.includes(k));
                                                    if (matched) {
                                                        if (inDialog) return b;
                                                        if (!bestConfirm) bestConfirm = b;
                                                    }
                                                }
                                                return bestConfirm;
                                            };

                                            const confirm = findConfirm();
                                            if (confirm) {
                                                console.log("GP_AUTOMATOR: Confirming deletion...");
                                                simulateClick(confirm);
                                                confirm.click();
                                                console.log("GP_AUTOMATOR: Waiting for deletion...");
                                                await randomSleep(8000, 12000);
                                                
                                                // After delete, check if we need to reload to refresh state or continue
                                                if (window.GP_SETTINGS && window.GP_SETTINGS.deleteMode === "ALL") {
                                                     console.log("GP_AUTOMATOR:BATCH_COMPLETE:" + checkmarks.length);
                                                     window.gpAutomatorRunning = false;
                                                     return;
                                                }

                                            } else {
                                                console.log("GP_AUTOMATOR: Confirm button NOT found.");
                                            }
                                        }
                                    } else {
                                        idleScrolls++;
                                        console.log("GP_AUTOMATOR: No items selected. Idle count: " + idleScrolls);
                                    }
                                } else {
                                    idleScrolls++;
                                    console.log("GP_AUTOMATOR: Failed to enter selection mode. Idle count: " + idleScrolls);
                                }

                                console.log("GP_AUTOMATOR: Scrolling...");
                                lastScrollY = window.scrollY;
                                window.scrollBy(0, 1500);
                                await randomSleep(2000, 4000);
                                
                                const isBottom = (window.innerHeight + window.scrollY) >= document.body.offsetHeight - 100;

                                if (isBottom && idleScrolls > 2) {
                                     console.log("GP_AUTOMATOR: Bottom reached. Finishing.");
                                     break;
                                }

                                if (window.scrollY === lastScrollY && idleScrolls > MAX_IDLE_SCROLLS) {
                                    console.log("GP_AUTOMATOR: Stuck or Empty. Finishing.");
                                    break;
                                }
                            }
                    } catch (err) {
                        console.log("GP_AUTOMATOR: ERROR: " + err);
                    }
                    
                    window.gpAutomatorRunning = false;
                    console.log("GP_AUTOMATOR:FINISHED");
                })();
            }
        """.trimIndent()
        
        webView.evaluateJavascript(script, null)
    }
    private fun addLog(message: String) {
        val timestamp = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val fullLog = "[$dateStr] $message"
        
        activityLogs.add(0, fullLog)
        
        // Prune logs older than 30 days
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        val cutoff = System.currentTimeMillis() - thirtyDaysMs
        
        // Simple string parsing based pruning since we store full strings
        // Or better: Filter based on parsed date or keep a parallel list?
        // Let's use JSON array of objects or just strings and parse on clean up.
        // For simplicity: Clean up on load. Here just keep recent 500 in memory maybe?
        // User asked "keep activity logs for 30 days". 
        // Let's rely on save/load for full 30 day retention.
        
        saveLogs()
        Log.d(TAG, "ActivityLog: $message")
    }
    
    // JSON Import needed
    // import org.json.JSONArray

    private fun saveLogs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val jsonArray = org.json.JSONArray()
        // Limit to reasonable amount to avoid SharedPrefs overflow?
        // 30 days of verbose logs might be large. 
        // Let's store max 2000 entries? 
        activityLogs.take(2000).forEach { jsonArray.put(it) }
        prefs.edit().putString("KEY_ACTIVITY_LOGS", jsonArray.toString()).apply()
    }

    private fun loadLogs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val jsonStr = prefs.getString("KEY_ACTIVITY_LOGS", "[]")
        activityLogs.clear()
        try {
            val jsonArray = org.json.JSONArray(jsonStr)
            val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            for (i in 0 until jsonArray.length()) {
                val log = jsonArray.getString(i)
                // Parse date
                // Log format: [yyyy-MM-dd HH:mm:ss] Message
                if (log.startsWith("[")) {
                    val endBracket = log.indexOf("]")
                    if (endBracket > 1) {
                        val datePart = log.substring(1, endBracket)
                        val date = sdf.parse(datePart)
                        if (date != null && date.time > sevenDaysAgo) {
                            activityLogs.add(log)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showLogsDialog() {
        val logContent = if (activityLogs.isEmpty()) "No activity logs yet." else activityLogs.joinToString("\n")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Activity Logs (Last 7 Days)")
            .setMessage(logContent)
            .setPositiveButton("Clear") { dialog, _ ->
                activityLogs.clear()
                saveLogs()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun checkReliabilitySettings() {
        // 1. Ignore Battery Optimizations (Exclude from Doze/App Standby)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request battery optimization ignore", e)
            }
        }

        // 2. Unused App Permissions (Auto-Revoke)
        // Intent to open "App Info" or specific "Unused apps" setting if possible
        // Ideally we want ACTION_AUTO_REVOKE_PERMISSIONS (API 30+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
             val intent = android.content.Intent(android.content.Intent.ACTION_AUTO_REVOKE_PERMISSIONS)
             intent.data = android.net.Uri.fromParts("package", packageName, null)
             try {
                // We don't want to force this on every launch if already done?
                // There isn't a simple public API to check if this is disabled.
                // So we might skip forcing it automatically or use a preference to show it once.
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                if (!prefs.getBoolean("hasRequestedAutoRevoke", false)) {
                     startActivity(intent)
                     prefs.edit().putBoolean("hasRequestedAutoRevoke", true).apply()
                }
             } catch (e: Exception) {
                 // Fallback to App Info
                 // val appInfoIntent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                 // appInfoIntent.data = android.net.Uri.parse("package:$packageName")
                 // startActivity(appInfoIntent)
                 Log.d(TAG, "Auto-revoke intent not supported or failed")
             }
        }
    }


    private fun showContactDialog() {
        val buildDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(BuildConfig.BUILD_TIME))
        
        val message = """
            Developer: Canh0Chua
            Email: canh0chua@gmail.com
            
            Version: ${BuildConfig.VERSION_NAME}
            Build Date: $buildDate
            
            GitHub: https://github.com/canh0chua/gpempty
        """.trimIndent()

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("About & Contact")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .setNeutralButton("Open GitHub") { _, _ ->
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/canh0chua/gpempty"))
                startActivity(intent)
            }
            .create()
            
        dialog.show()
        
        // Make links clickable if we used a TextView, but for Alert dialog message, just text.
        // We added a Neutral button for the link instead for better UX.
    }
}
