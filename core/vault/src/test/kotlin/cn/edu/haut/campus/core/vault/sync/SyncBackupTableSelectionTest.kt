package cn.edu.haut.campus.core.vault.sync

import cn.edu.haut.campus.core.vault.BackupTableCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncBackupTableSelectionTest {
    @Test
    fun deviceLocalAutomationExecutionTablesAreExcludedFromSyncButRemainBackupCataloged() {
        assertTrue(BackupTableCatalog.policies.any { it.table == "library_booking_plans" })
        assertTrue(BackupTableCatalog.policies.any { it.table == "focus_sessions" })
        assertFalse(SyncBackupTableSelection.isIncluded("library_booking_plans"))
        assertFalse(SyncBackupTableSelection.isIncluded("focus_sessions"))
        assertTrue(SyncBackupTableSelection.isIncluded("courses"))
        assertTrue(SyncBackupTableSelection.isIncluded("personal_events"))
        assertTrue(SyncBackupTableSelection.isIncluded("parcel_items"))
        try {
            SyncBackupTableSelection.requireIncluded("library_booking_plans")
            fail("自动化执行表必须拒绝同步复制")
        } catch (expected: E2eeSyncCryptoException) {
            // expected
        }
    }
}
