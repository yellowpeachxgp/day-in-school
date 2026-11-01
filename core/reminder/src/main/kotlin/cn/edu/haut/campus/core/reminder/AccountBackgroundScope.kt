package cn.edu.haut.campus.core.reminder

import android.content.Context
import android.content.Intent
import android.net.Uri
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.ActiveSession

/** 平台回调的账号与激活代数；同账号重新激活后旧回调也不能复用。 */
data class AccountBackgroundScope(val profileId: String, val epoch: Long) {
    init {
        require(profileId.isNotBlank() && epoch > 0)
    }

    fun attach(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_PROFILE_ID, profileId)
        putExtra(EXTRA_EPOCH, epoch)
        data = (data ?: Uri.parse("dayinschool://background/action")).buildUpon()
            .appendQueryParameter("profile", profileId)
            .appendQueryParameter("epoch", epoch.toString()).build()
    }

    fun matches(
        sessionProfileId: String,
        sessionGeneration: Long
    ): Boolean = profileId == sessionProfileId && epoch == sessionGeneration

    fun matches(session: ActiveSession): Boolean = matches(session.profileId, session.generation)

    fun isActive(context: Context): Boolean =
        AccountSessionCoordinator.getInstance(context).currentSnapshot()?.let(::matches) == true

    companion object {
        const val EXTRA_PROFILE_ID = "extra_account_profile_id"
        const val EXTRA_EPOCH = "extra_account_epoch"

        fun from(session: ActiveSession) = AccountBackgroundScope(session.profileId, session.generation)
        fun current(context: Context): AccountBackgroundScope? =
            AccountSessionCoordinator.getInstance(context).currentSnapshot()?.let(::from)
        fun read(intent: Intent): AccountBackgroundScope? {
            val id = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() } ?: return null
            val epoch = intent.getLongExtra(EXTRA_EPOCH, -1L).takeIf { it > 0L } ?: return null
            return AccountBackgroundScope(id, epoch)
        }
    }
}
