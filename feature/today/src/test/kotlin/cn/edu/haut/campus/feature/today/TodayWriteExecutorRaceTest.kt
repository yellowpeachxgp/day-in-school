package cn.edu.haut.campus.feature.today

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.domain.SchoolDirectory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PR#104 B1 竞态回归：保存已受理后立刻离场清空 owner，写入仍必须完成。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayWriteExecutorRaceTest {

    @Test
    fun enqueuedWriteSurvivesImmediateCompositionOwnerCancellation() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val allowWriteFinish = CompletableDeferred<Unit>()
        val writeCompleted = AtomicBoolean(false)
        val completion = AtomicReference<Result<Unit>?>(null)
        val successHooks = AtomicInteger(0)
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = TodayWriteExecutor(
            writeScope = writeScope,
            runner = object : TodaySessionWriteRunner {
                override suspend fun <T : Any> withActiveSessionWrite(
                    profileId: String,
                    sessionGeneration: Long,
                    write: suspend () -> T
                ): T? = write()
            },
            afterSuccessfulWrite = { successHooks.incrementAndGet() }
        )
        val compositionOwnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var writeJob: Job? = null
        val enqueued = CompletableDeferred<Unit>()
        compositionOwnerScope.launch {
            writeJob = executor.enqueue(
                profileId = "profile-a",
                sessionGeneration = 1L,
                write = {
                    writeStarted.complete(Unit)
                    allowWriteFinish.await()
                    writeCompleted.set(true)
                },
                onComplete = { result -> completion.set(result) }
            )
            enqueued.complete(Unit)
        }

        enqueued.await()
        writeStarted.await()
        compositionOwnerScope.cancel()
        allowWriteFinish.complete(Unit)
        assertNotNull(writeJob)
        writeJob!!.join()

        assertTrue("组合 owner 已取消时写入仍必须跑到事务结束", writeCompleted.get())
        assertTrue("完成回调必须能用于关闭/反馈", completion.get()?.isSuccess == true)
        assertEquals(1, successHooks.get())
        writeScope.cancel()
    }

    @Test
    fun staleSessionDoesNotExecuteWrite() = runBlocking {
        val writeCalled = AtomicBoolean(false)
        val completion = AtomicReference<Result<Unit>?>(null)
        val successHooks = AtomicInteger(0)
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = TodayWriteExecutor(
            writeScope = writeScope,
            runner = object : TodaySessionWriteRunner {
                override suspend fun <T : Any> withActiveSessionWrite(
                    profileId: String,
                    sessionGeneration: Long,
                    write: suspend () -> T
                ): T? = null
            },
            afterSuccessfulWrite = { successHooks.incrementAndGet() }
        )

        val job = executor.enqueue(
            profileId = "profile-a",
            sessionGeneration = 1L,
            write = { writeCalled.set(true) },
            onComplete = { result -> completion.set(result) }
        )
        assertNotNull(job)
        job!!.join()

        assertFalse(writeCalled.get())
        assertTrue(completion.get()?.isFailure == true)
        assertTrue(completion.get()?.exceptionOrNull() is TodaySessionMismatchWriteException)
        assertEquals(0, successHooks.get())
        writeScope.cancel()
    }

    @Test
    fun onCompleteExceptionIsReportedWithoutFailingDurableWrite() = runBlocking {
        val callbackError = IllegalStateException("completion callback exploded")
        val reported = AtomicReference<Throwable?>(null)
        val writeCompleted = AtomicBoolean(false)
        val successHooks = AtomicInteger(0)
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = TodayWriteExecutor(
            writeScope = writeScope,
            runner = object : TodaySessionWriteRunner {
                override suspend fun <T : Any> withActiveSessionWrite(
                    profileId: String,
                    sessionGeneration: Long,
                    write: suspend () -> T
                ): T? = write()
            },
            afterSuccessfulWrite = { successHooks.incrementAndGet() },
            callbackErrorReporter = { reported.set(it) }
        )

        val job = executor.enqueue(
            profileId = "profile-a",
            sessionGeneration = 1L,
            write = { writeCompleted.set(true) },
            onComplete = { result ->
                if (result.isSuccess) throw callbackError
            }
        )

        assertNotNull(job)
        job!!.join()
        assertTrue("回调异常不得回滚或中断已经完成的耐用写", writeCompleted.get())
        assertTrue("回调异常必须显式上报，不能静默吞掉", reported.get() === callbackError)
        assertEquals(1, successHooks.get())
        writeScope.cancel()
    }

    @Test
    fun realAccountSessionSwitchIsMutexedWithWritesAndStaleWriteFailsClosed() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val sessions = AccountSessionCoordinator.getInstance(context)
        val first = sessions.initialize()
        val secondProfile = sessions.createOfflineProfile(
            SchoolDirectory.hautProfile(),
            "并发回归身份 " + System.nanoTime()
        )
        val events = CopyOnWriteArrayList<String>()
        val hookKey = "today-session-race-" + System.nanoTime()
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val executor = TodayWriteExecutor(
            writeScope = writeScope,
            runner = object : TodaySessionWriteRunner {
                override suspend fun <T : Any> withActiveSessionWrite(
                    profileId: String,
                    sessionGeneration: Long,
                    write: suspend () -> T
                ): T? = sessions.withActiveSession(profileId, sessionGeneration) {
                    write()
                }
            }
        )
        try {
            sessions.registerLifecycleHook(
                key = hookKey,
                beforeSwitch = { events.add("switch-before") },
                afterSwitch = { events.add("switch-after") }
            )
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            val firstCompletion = AtomicReference<Result<Unit>?>(null)
            val firstWriteJob = requireNotNull(
                executor.enqueue(
                    profileId = first.profileId,
                    sessionGeneration = first.generation,
                    write = {
                        events.add("write-start")
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        events.add("write-end")
                    },
                    onComplete = { result -> firstCompletion.set(result) }
                )
            )
            writeStarted.await()

            val switchToSecond = async { sessions.switchProfile(secondProfile.id) }
            yield()
            delay(50)
            assertFalse("真实 switchProfile 不得越过已经开始的 withActiveSession 写", events.contains("switch-before"))
            releaseWrite.complete(Unit)
            switchToSecond.await()
            firstWriteJob.join()
            assertTrue(firstCompletion.get()?.isSuccess == true)
            assertTrue(
                "写结束必须先于切换 hook，证明真实互斥锁没有交错",
                events.indexOf("write-end") >= 0 && events.indexOf("write-end") < events.indexOf("switch-before")
            )

            val switchHookEntered = CompletableDeferred<Unit>()
            val releaseSwitchHook = CompletableDeferred<Unit>()
            sessions.registerLifecycleHook(
                key = hookKey,
                beforeSwitch = {
                    switchHookEntered.complete(Unit)
                    releaseSwitchHook.await()
                },
                afterSwitch = { }
            )
            val switchBack = async { sessions.switchProfile(first.profileId) }
            switchHookEntered.await()

            val staleWriteCalled = AtomicBoolean(false)
            val staleCompletion = AtomicReference<Result<Unit>?>(null)
            val staleJob = requireNotNull(
                executor.enqueue(
                    profileId = first.profileId,
                    sessionGeneration = first.generation,
                    write = { staleWriteCalled.set(true) },
                    onComplete = { result -> staleCompletion.set(result) }
                )
            )
            yield()
            assertFalse("切换持锁期间旧会话写不得进入", staleWriteCalled.get())
            assertFalse(staleJob.isCompleted)

            releaseSwitchHook.complete(Unit)
            switchBack.await()
            staleJob.join()
            assertFalse("切换完成后旧 generation 必须 fail-closed", staleWriteCalled.get())
            assertTrue(staleCompletion.get()?.exceptionOrNull() is TodaySessionMismatchWriteException)
        } finally {
            writeScope.cancel()
        }
    }
}
