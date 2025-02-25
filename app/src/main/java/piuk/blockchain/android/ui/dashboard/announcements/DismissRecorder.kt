package piuk.blockchain.android.ui.dashboard.announcements

import piuk.blockchain.androidcore.utils.SessionPrefs

/**
 * Maintains a boolean flag for recording if a dialog has been dismissed.
 */

enum class DismissRule {
    CardPersistent,
    CardPeriodic,
    CardOneTime
}

interface DismissClock {
    fun now(): Long
}

class DismissRecorder(
    private val prefs: SessionPrefs,
    private val clock: DismissClock
) {
    operator fun get(key: String) = DismissEntry(key)

    inner class DismissEntry(val prefsKey: String) {
        val isDismissed: Boolean
            get() = isDismissed(prefsKey)

        fun dismiss(dismissRule: DismissRule) {
            when (dismissRule) {
                DismissRule.CardPersistent -> dismissForever(prefsKey)
                DismissRule.CardPeriodic -> dismissPeriodic(prefsKey)
                DismissRule.CardOneTime -> dismissForever(prefsKey)
            }
        }

        fun done() {
            dismissForever(prefsKey)
        }
    }

    fun dismissPeriodic(prefsKey: String) {
        prefs.deleteDismissalRecord(prefsKey) // In case there is a legacy setting
        prefs.recordDismissal(prefsKey, DISMISS_INTERVAL_PERIODIC)
    }

    fun dismissForever(prefsKey: String) {
        prefs.deleteDismissalRecord(prefsKey) // In case there is a legacy setting
        prefs.recordDismissal(prefsKey, DISMISS_INTERVAL_FOREVER)
    }

    fun isDismissed(prefsKey: String): Boolean =
        try {
            val nextShow = prefs.getDismissalEntry(prefsKey)
            val now = clock.now()

            nextShow != 0L && now <= nextShow
        } catch (e: ClassCastException) {
            // Try the legacy key
            prefs.getLegacyDismissalEntry(prefsKey)
        }

    // For debug/QA
    internal fun reinstateAllAnnouncements(announcementList: AnnouncementList) {
        announcementList.dismissKeys().forEach { prefs.deleteDismissalRecord(it) }
    }

    private var interval = ONE_WEEK

    fun setPeriod(days: Long) {
        interval = days * 24L * 60L * 60L * 1000L
    }

    @Suppress("PrivatePropertyName")
    private val DISMISS_INTERVAL_PERIODIC: Long
        get() = clock.now() + interval

    companion object {
        private const val ONE_WEEK = 7L * 24L * 60L * 60L * 1000L
        private const val DISMISS_INTERVAL_FOREVER = Long.MAX_VALUE
    }
}
