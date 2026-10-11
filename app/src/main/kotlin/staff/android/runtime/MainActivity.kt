package staff.android.runtime

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView
import java.util.Collections
import java.util.concurrent.Executors

class MainActivity : Activity() {
    // region activity-owners
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private var catalog: ICatalogService? = null
    private var started = false
    private var bound = false
    private var generation = 0L
    val events: MutableList<String> =
        Collections.synchronizedList(mutableListOf())
    val drawn: MutableList<String> =
        Collections.synchronizedList(mutableListOf())
    // endregion
    private val buttons = mutableListOf<Button>()
    private lateinit var eventText: TextView
    private var controlsExpanded = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            catalog = ICatalogService.Stub.asInterface(binder)
            buttons.forEach { it.isEnabled = true }
            status.text = "Ready"
            record("Service connected")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            generation++
            catalog = null
            buttons.forEach { it.isEnabled = false }
            if (started) status.text = "Service disconnected"
            record("Service disconnected")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.product_photo).clipToOutline = true
        status = findViewById(R.id.status)
        eventText = findViewById(R.id.event_text)
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
            Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) flags else 0, flags)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
        if (Build.VERSION.SDK_INT >= 28) findViewById<View>(R.id.screen_title).isAccessibilityHeading = true
        fun connect(id: Int, action: () -> Unit) {
            findViewById<Button>(id).apply {
                isEnabled = false
                setOnClickListener { action() }
                buttons += this
            }
        }
        connect(R.id.check_availability, ::workerCheck)
        connect(R.id.check_blocking, ::blockingCheck)
        connect(R.id.check_worker, ::workerCheck)
        connect(R.id.check_callback, ::callbackCheck)
        controlsExpanded = savedInstanceState?.getBoolean("controlsExpanded") ?: false
        updateControls()
        findViewById<View>(R.id.lab_controls_toggle).setOnClickListener {
            controlsExpanded = !controlsExpanded
            updateControls()
        }
        status.viewTreeObserver.addOnDrawListener { drawn += status.text.toString() }
    }

    private fun updateControls() {
        findViewById<View>(R.id.lab_controls).visibility = if (controlsExpanded) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.lab_controls_toggle).setText(
            if (controlsExpanded) R.string.lab_close else R.string.lab_controls,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("controlsExpanded", controlsExpanded)
        super.onSaveInstanceState(outState)
    }

    // region activity-lifetime
    override fun onStart() {
        super.onStart()
        started = true
        bound = bindService(Intent(this, CatalogService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        started = false
        generation++
        catalog = null
        buttons.forEach { it.isEnabled = false }
        if (bound) { unbindService(connection); bound = false }
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
    // endregion

    // region blocking-check
    fun blockingCheck() {
        val service = catalog ?: return
        generation++
        status.text = "Checking"
        main.post { record("Queued probe: ${status.text}") }
        val answer = try {
            service.check("camera")
        } catch (_: RemoteException) {
            "Service unavailable"
        }
        status.text = answer
        record("Click returned: $answer")
    }
    // endregion

    // region worker-check
    fun workerCheck() {
        val service = catalog ?: return
        val request = ++generation
        status.text = "Checking"
        main.post { record("Queued probe: ${status.text}") }
        worker.execute {
            val answer = try {
                service.check("camera")
            } catch (_: RemoteException) {
                "Service unavailable"
            }
            main.post {
                if (started && request == generation) {
                    status.text = answer
                    record("Accepted: $answer")
                } else {
                    record("Ignored obsolete reply")
                }
            }
        }
    }
    // endregion

    // region callback-check
    fun callbackCheck() {
        val service = catalog ?: return
        val request = ++generation
        status.text = "Checking"
        val callback = object : ICatalogCallback.Stub() {
            override fun onResult(productId: String, answer: String, servicePid: Int, serviceTid: Int) {
                val onMain = Looper.myLooper() == Looper.getMainLooper()
                val event = "Callback main=$onMain " +
                    "clientPid=${Process.myPid()} servicePid=$servicePid"
                Log.i("RuntimeLab", event)
                main.post {
                    record(event)
                    if (started && request == generation) {
                        status.text = answer
                        record("Accepted callback: $answer")
                    } else {
                        record("Ignored obsolete callback")
                    }
                }
            }
        }
        worker.execute {
            try {
                service.request("camera", callback)
            } catch (_: RemoteException) {
                main.post {
                    if (started && request == generation) status.text = "Service unavailable"
                }
            }
        }
    }
    // endregion

    private fun record(event: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        events += event
        if (started) eventText.text = synchronized(events) { events.takeLast(8).joinToString("\n") }
        Log.i("RuntimeLab", event)
    }
    fun readyForTest(): Boolean = catalog != null
    fun statusForTest(): String = status.text.toString()
}
