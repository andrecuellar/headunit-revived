package com.andrerinas.headunitrevived.main

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * One-time migration notice for the old app: Headunit Revived is becoming Open Headunit.
 *
 * It offers to save a backup of the user's settings and to open the Open Headunit listing, so the
 * user can install the new app and restore their setup during its onboarding. It is not cancelable
 * and the "shown once" flag is only spent when the user takes a migration action (save a backup or
 * open the new app), so "Later" simply brings it back on the next launch. It waits for onboarding to
 * finish so it is never spent behind the setup wizard.
 */
object RenameNotice {

    /** Play Store id of the new app. */
    const val OPEN_HEADUNIT_ID = "com.andrerinas.openheadunit"

    private var dialog: AlertDialog? = null

    fun maybeShow(activity: Activity, settings: Settings, onSaveBackup: () -> Unit) {
        if (settings.renameNoticeShown) return
        if (dialog?.isShowing == true) return
        if (settings.onboardingVersion < OnboardingActivity.CURRENT_ONBOARDING_VERSION) return

        val firstInstallTime = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).firstInstallTime
        } catch (e: PackageManager.NameNotFoundException) {
            0L
        }
        if (!RenameNoticePolicy.shouldOffer(firstInstallTime)) return

        dialog = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setTitle(R.string.rename_notice_title)
            .setMessage(R.string.rename_notice_message)
            .setCancelable(false)
            .setPositiveButton(R.string.rename_notice_save_backup) { d, _ ->
                settings.renameNoticeShown = true
                d.dismiss()
                onSaveBackup()
            }
            .setNeutralButton(R.string.rename_notice_get_app) { d, _ ->
                settings.renameNoticeShown = true
                d.dismiss()
                openListing(activity)
            }
            .setNegativeButton(R.string.rename_notice_later) { d, _ -> d.dismiss() }
            .show()
    }

    /** Opens the Open Headunit Play Store listing, falling back to the web page. */
    fun openListing(activity: Activity) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$OPEN_HEADUNIT_ID"))
        try {
            activity.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$OPEN_HEADUNIT_ID")
                )
            )
        }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
