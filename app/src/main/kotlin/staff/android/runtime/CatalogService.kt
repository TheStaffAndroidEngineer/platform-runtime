package staff.android.runtime

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CatalogService : Service() {
    private var entryTid = 0
    private val worker = Executors.newSingleThreadExecutor()
    private val gate = AtomicReference<CountDownLatch?>()
    private val waiting = AtomicInteger()
    override fun onCreate() {
        super.onCreate()
        entryTid = Process.myTid()
        Log.i("RuntimeLab", "Service.onCreate pid=${Process.myPid()} tid=$entryTid main=${Looper.myLooper() == Looper.getMainLooper()}")
    }

    // region catalog-stub
    private val endpoint = object : ICatalogService.Stub() {
        override fun check(productId: String): String {
            waiting.incrementAndGet()
            try {
                gate.get()?.let { kotlin.check(it.await(5, TimeUnit.SECONDS)) }
            // Deliberate fixture delay, not a measured production latency.
                Thread.sleep(350)
                return if (productId == "camera") "Available" else "Unavailable"
            } finally {
                waiting.decrementAndGet()
            }
        }

        override fun request(productId: String, callback: ICatalogCallback) {
            worker.execute {
                try {
                    val answer = check(productId)
                    callback.onResult(productId, answer, Process.myPid(), Process.myTid())
                } catch (_: RemoteException) {
                    Log.i("RuntimeLab", "Client unavailable at callback")
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        override fun processId(): Int = Process.myPid()
        override fun entryThreadId(): Int = entryTid
        override fun callThreadId(): Int = Process.myTid()
        override fun pauseForLab(paused: Boolean) {
            kotlin.check(BuildConfig.DEBUG)
            if (paused) gate.compareAndSet(null, CountDownLatch(1))
            else gate.getAndSet(null)?.countDown()
        }
        override fun waitingForLab(): Int = waiting.get()
        override fun crashForLab() {
            kotlin.check(BuildConfig.DEBUG)
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 100)
        }
    }
    // endregion
    override fun onBind(intent: Intent): IBinder = endpoint
    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
