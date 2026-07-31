package com.andrerinas.headunitrevived.main

/**
 * Decides who sees the one-time rename notice on the old app.
 *
 * Existing users (installed before the rename went live) always get it, so they learn the app is
 * becoming Open Headunit and can carry their settings over. Brand-new installs only get it during a
 * short window after the release, since a rename notice means nothing to someone who never knew the
 * old name.
 *
 * The decision is based only on the install time, which is recorded once at install and never
 * changes on updates. It does not read the current wall clock on purpose: many head units run
 * offline with a wrong or unset system clock, and existing users must still be reached.
 */
object RenameNoticePolicy {

    // Date the renamed build went live (UTC epoch millis).
    const val RENAME_RELEASE_MS = 1785542400000L // 2026-08-01

    // How long after the release brand-new installs still see the notice.
    const val NEW_USER_WINDOW_MS = 30L * 24 * 60 * 60 * 1000 // 30 days

    fun shouldOffer(firstInstallTimeMs: Long): Boolean {
        // Installed before the release (existing user) or within the window afterwards.
        // Unknown install time (0) falls on the show side, so we inform rather than stay silent.
        return firstInstallTimeMs < RENAME_RELEASE_MS + NEW_USER_WINDOW_MS
    }
}
