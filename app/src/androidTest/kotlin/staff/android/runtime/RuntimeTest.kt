package staff.android.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Condition did not become true")
            Thread.sleep(20)
        }
    }
    private fun withReadyActivity(block: (ActivityScenario<MainActivity>, MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            awaitCondition {
                var ready = false
                scenario.onActivity { ready = it.readyForTest() }
                ready
            }
            instrumentation.waitForIdleSync()
            block(scenario, activity)
        }
    }
    @Test fun handlerPostWaitsForCurrentMainCallback() {
        val steps = mutableListOf<String>()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            steps += "before"
            Handler(Looper.getMainLooper()).post { steps += "queued"; done.countDown() }
            steps += "after"
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("before", "after", "queued"), steps)
    }
    @Test fun blockingCheckSkipsCheckingAtDraw() = withReadyActivity { scenario, activity ->
        scenario.onActivity { it.events.clear(); it.drawn.clear(); it.blockingCheck() }
        awaitCondition { activity.events.contains("Queued probe: Available") && activity.drawn.contains("Available") }
        assertFalse(activity.drawn.contains("Checking"))
        val events = synchronized(activity.events) { activity.events.toList() }
        assertTrue(events.indexOf("Click returned: Available") < events.indexOf("Queued probe: Available"))
        android.util.Log.i("RuntimeEvidence", "blocking=${events.joinToString(" | ")} drawnChecking=false")
    }
    @Test fun workerCheckAllowsCheckingAndThenAvailable() = withReadyActivity { scenario, activity ->
        scenario.onActivity { it.events.clear(); it.drawn.clear(); it.workerCheck() }
        awaitCondition { activity.events.contains("Accepted: Available") && activity.drawn.contains("Available") }
        assertTrue(activity.events.contains("Queued probe: Checking"))
        assertTrue(activity.drawn.contains("Checking"))
        android.util.Log.i("RuntimeEvidence", "worker=Queued probe: Checking | Accepted: Available drawnChecking=true")
    }
    @Test fun normalActionUsesWorkerAndDisclosurePreservesTheResult() = withReadyActivity { scenario, activity ->
        scenario.onActivity {
            assertEquals(android.view.View.GONE, it.findViewById<android.view.View>(R.id.lab_controls).visibility)
            it.events.clear()
            it.findViewById<android.view.View>(R.id.check_availability).performClick()
        }
        awaitCondition { activity.events.contains("Accepted: Available") }
        assertTrue(activity.events.contains("Queued probe: Checking"))
        assertFalse(activity.events.any { it.startsWith("Click returned:") })
        scenario.onActivity {
            it.findViewById<android.view.View>(R.id.lab_controls_toggle).performClick()
            assertEquals(android.view.View.VISIBLE, it.findViewById<android.view.View>(R.id.lab_controls).visibility)
            it.findViewById<android.view.View>(R.id.lab_controls_toggle).performClick()
            assertEquals(android.view.View.GONE, it.findViewById<android.view.View>(R.id.lab_controls).visibility)
            assertEquals("Available", it.statusForTest())
            assertTrue(it.readyForTest())
        }
    }
    @Test fun stoppingScreenRejectsPendingReply() = withReadyActivity { scenario, activity ->
        Binding().use { binding ->
            val service = binding.awaitService()
            service.pauseForLab(true)
            scenario.onActivity { it.events.clear(); it.workerCheck() }
            awaitCondition { service.waitingForLab() > 0 }
            scenario.moveToState(Lifecycle.State.CREATED)
            service.pauseForLab(false)
            awaitCondition { activity.events.contains("Ignored obsolete reply") }
            assertFalse(activity.events.contains("Accepted: Available"))
        }
    }
    @Test fun newerRequestRejectsOlderReply() = withReadyActivity { scenario, activity ->
        scenario.onActivity { it.events.clear(); it.workerCheck(); it.workerCheck() }
        awaitCondition { activity.events.contains("Accepted: Available") }
        assertEquals(1, activity.events.count { it == "Ignored obsolete reply" })
        assertEquals(1, activity.events.count { it == "Accepted: Available" })
    }
    @Test fun remoteCallbackReturnsThroughMainAcceptance() = withReadyActivity { scenario, activity ->
        scenario.onActivity { it.events.clear(); it.callbackCheck() }
        awaitCondition { activity.events.contains("Accepted callback: Available") }
        assertTrue(activity.events.any { it.startsWith("Callback main=false") })
    }
    @Test fun stoppingScreenRejectsPendingRemoteCallback() = withReadyActivity { scenario, activity ->
        // Keep another binding so the remote service lives after the Activity unbinds.
        val remote = Binding()
        try {
            val service = remote.awaitService()
            service.pauseForLab(true)
            scenario.onActivity { it.events.clear(); it.callbackCheck() }
            awaitCondition { service.waitingForLab() > 0 }
            scenario.moveToState(Lifecycle.State.CREATED)
            service.pauseForLab(false)
            awaitCondition { activity.events.contains("Ignored obsolete callback") }
            assertFalse(activity.events.contains("Accepted callback: Available"))
        } finally { remote.close() }
    }

    private inner class Binding : AutoCloseable {
        val ready = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val remote = AtomicReference<ICatalogService>()
        val connectionOnMain = AtomicBoolean()
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote.set(ICatalogService.Stub.asInterface(binder))
                connectionOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { disconnected.countDown() }
        }
        init { assertTrue(context.bindService(Intent(context, CatalogService::class.java), connection, Context.BIND_AUTO_CREATE)) }
        fun awaitService(): ICatalogService {
            assertTrue(ready.await(8, TimeUnit.SECONDS)); return remote.get()
        }
        override fun close() { context.unbindService(connection) }
    }
    @Test fun remoteServiceHasDifferentProcessAndBinderEntryThread() {
        Binding().use { binding ->
            val service = binding.awaitService()
            val remotePid = service.processId()
            val entryTid = service.entryThreadId()
            val callTid = service.callThreadId()
            assertNotEquals(Process.myPid(), remotePid)
            assertEquals(remotePid, entryTid) // Linux main thread ID equals PID.
            assertNotEquals(entryTid, callTid)
            assertTrue(binding.connectionOnMain.get())
            android.util.Log.i("RuntimeEvidence", "clientPid=${Process.myPid()} servicePid=$remotePid onCreateTid=$entryTid rpcTid=$callTid")
        }
    }
    @Test fun remoteCallbackCanArriveOffMain() {
        Binding().use { binding ->
            val received = CountDownLatch(1)
            val onMain = AtomicBoolean(true)
            val pid = AtomicInteger()
            val answer = AtomicReference<String>()
            binding.awaitService().request("camera", object : ICatalogCallback.Stub() {
                override fun onResult(productId: String, result: String, servicePid: Int, serviceTid: Int) {
                    onMain.set(Looper.myLooper() == Looper.getMainLooper())
                    pid.set(servicePid); answer.set(result); received.countDown()
                }
            })
            assertTrue(received.await(5, TimeUnit.SECONDS))
            assertFalse(onMain.get())
            assertNotEquals(Process.myPid(), pid.get())
            assertEquals("Available", answer.get())
        }
    }
    @Test fun localAidlCallbackRunsInlineOnCallingThread() {
        val order = mutableListOf<String>()
        var callbackThread: Thread? = null
        val stub = object : ICatalogCallback.Stub() {
            override fun onResult(productId: String, answer: String, servicePid: Int, serviceTid: Int) {
                callbackThread = Thread.currentThread(); order += "callback"
            }
        }
        val local = ICatalogCallback.Stub.asInterface(stub.asBinder())
        order += "before"; local.onResult("camera","Available",0,0); order += "after"
        assertSame(Thread.currentThread(), callbackThread)
        assertEquals(listOf("before", "callback", "after"), order)
    }
    @Test fun remoteProcessDeathInvalidatesBinderAndFreshBindWorks() {
        val first = Binding()
        val service = first.awaitService()
        val oldPid = service.processId()
        val died = CountDownLatch(1)
        service.asBinder().linkToDeath({ died.countDown() },0)
        try {
            service.crashForLab()
            assertTrue(died.await(8, TimeUnit.SECONDS))
            assertTrue(first.disconnected.await(8, TimeUnit.SECONDS))
            assertFalse(service.asBinder().isBinderAlive)
        } finally { first.close() }
        Binding().use { second ->
            val replacement = second.awaitService()
            assertNotEquals(oldPid, replacement.processId())
            assertEquals("Available", replacement.check("camera"))
        }
    }
    @Test fun processDeathFailsAnInFlightSynchronousCall() {
        Binding().use { binding ->
            val service = binding.awaitService()
            service.pauseForLab(true)
            val failed = CountDownLatch(1)
            val worker = kotlin.concurrent.thread {
                try { service.check("camera") }
                catch (_: android.os.RemoteException) { failed.countDown() }
            }
            awaitCondition { service.waitingForLab() > 0 }
            service.crashForLab()
            assertTrue(failed.await(8, TimeUnit.SECONDS))
            worker.join(2000)
            assertFalse(worker.isAlive)
        }
    }
}
