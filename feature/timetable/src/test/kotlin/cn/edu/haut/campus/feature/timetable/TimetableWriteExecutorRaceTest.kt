package cn.edu.haut.campus.feature.timetable

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
class TimetableWriteExecutorRaceTest {

    @Test
    fun enqueuedWriteSurvivesImmediateCompositionOwnerCancellation() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val allowWriteFinish = CompletableDeferred<Unit>()
        val writeCompleted = AtomicBoolean(false)
        val completion = AtomicReference<Result<Unit>?>(null)
        val successHooks = AtomicInteger(0)
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = TimetableWriteExecutor(
            writeScope = writeScope,
            runner = object : TimetableSessionWriteRunner {
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
        val executor = TimetableWriteExecutor(
            writeScope = writeScope,
            runner = object : TimetableSessionWriteRunner {
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
        assertTrue(completion.get()?.exceptionOrNull() is TimetableSessionMismatchWriteException)
        assertEquals(0, successHooks.get())
        writeScope.cancel()
    }
}
