package cn.edu.haut.campus.core.automation.android

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** processWake 的进程内串行化边界；闹钟、WorkManager 与 restore 同时到达时也必须依次对账。 */
class ExclusiveWakeRunner {
    private val mutex = Mutex()

    suspend fun <T> call(block: suspend () -> T): T = mutex.withLock {
        block()
    }
}
