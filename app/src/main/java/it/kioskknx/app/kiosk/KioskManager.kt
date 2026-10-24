package it.kioskknx.app.kiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import it.kioskknx.app.KioskBus
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.ui.MainActivity
import it.kioskknx.app.util.L

/** Modalità Kiosk: Device Owner, Lock Task e policy di sistema. */
object KioskManager {
    private const val TAG = "Kiosk"
    private var policiesApplied = false

    fun admin(context: Context) = ComponentName(context, KioskDeviceAdminReceiver::class.java)

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(context: Context): Boolean = dpm(context)?.isDeviceOwnerApp(context.packageName) == true

    fun isInLockTask(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /** Applica le policy Device Owner (una volta per processo). */
    fun applyPolicies(context: Context) {
        if (policiesApplied || !isDeviceOwner(context)) return
        val dpm = dpm(context) ?: return
        val admin = admin(context)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            if (Build.VERSION.SDK_INT >= 28) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            // Launcher predefinito = KioskKNX
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, MainActivity::class.java))
            dpm.setKeyguardDisabled(admin, true)
            dpm.setStatusBarDisabled(admin, true)
            // Schermo sempre acceso quando alimentato (AC|USB|Wireless)
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7")
            // Permessi concessi automaticamente
            val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            perms.forEach {
                dpm.setPermissionGrantState(admin, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            policiesApplied = true
            L.i(TAG, "Policy Device Owner applicate")
        } catch (e: Exception) {
            L.w(TAG, "Applicazione policy parziale", e)
        }
    }

    /** Entra in Lock Task se l'app è Device Owner e l'opzione è attiva. */
    fun enterKiosk(activity: Activity) {
        val ctx = activity.applicationContext
        if (!isDeviceOwner(ctx) || !ConfigRepository.current.kiosk.lockTaskEnabled) {
            KioskBus.lockTaskActive.value = false
            return
        }
        applyPolicies(ctx)
        if (!isInLockTask(ctx)) {
            try {
                activity.startLockTask()
                L.i(TAG, "Lock Task attivato")
            } catch (e: Exception) {
                L.w(TAG, "startLockTask fallito", e)
            }
        }
        KioskBus.lockTaskActive.value = isInLockTask(ctx)
    }

    fun exitKiosk(activity: Activity) {
        try {
            if (isInLockTask(activity)) activity.stopLockTask()
        } catch (e: Exception) {
            L.w(TAG, "stopLockTask fallito", e)
        }
        KioskBus.lockTaskActive.value = false
        if (isDeviceOwner(activity)) {
            runCatching { dpm(activity)?.setStatusBarDisabled(admin(activity), false) }
        }
        policiesApplied = false
    }

    /** Rimuove le policy e il Device Owner, così l'app si può disinstallare normalmente. */
    @Suppress("DEPRECATION")
    fun clearDeviceOwner(activity: Activity): Boolean {
        if (!isDeviceOwner(activity)) return false
        exitKiosk(activity)
        val dpm = dpm(activity) ?: return false
        val admin = admin(activity)
        return try {
            runCatching { dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName) }
            runCatching { dpm.setKeyguardDisabled(admin, false) }
            runCatching { dpm.setStatusBarDisabled(admin, false) }
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) }
            runCatching { dpm.setLockTaskPackages(admin, arrayOf()) }
            dpm.clearDeviceOwnerApp(activity.packageName)
            L.i(TAG, "Device Owner rimosso")
            true
        } catch (e: Exception) {
            L.e(TAG, "Rimozione Device Owner fallita", e)
            false
        }
    }

    /** Rimuove KioskKNX come launcher persistente (usato da "Esci dall'App"). */
    fun releaseHome(context: Context) {
        if (!isDeviceOwner(context)) return
        runCatching { dpm(context)?.clearPackagePersistentPreferredActivities(admin(context), context.packageName) }
        policiesApplied = false
    }

    fun reboot(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        return try {
            dpm(context)?.reboot(admin(context)); true
        } catch (e: Exception) {
            L.w(TAG, "Riavvio non consentito", e); false
        }
    }
}
