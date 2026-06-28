package expo.modules.t3nativecontrols

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

class T3AgentActivityNotificationActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
    val acknowledgementKey = intent.getStringExtra(EXTRA_ACKNOWLEDGEMENT_KEY).orEmpty()
    T3AgentActivityNotificationStore.markTerminalAcknowledged(context, acknowledgementKey)

    if (notificationId > 0) {
      context.getSystemService(NotificationManager::class.java).cancel(notificationId)
      T3AgentActivityNotificationStore.removePostedTerminalNotificationId(context, notificationId)
    }

    if (intent.action == ACTION_OPEN) {
      openApplication(context, intent.getStringExtra(EXTRA_DEEP_LINK_URL))
    }
  }

  private fun openApplication(context: Context, deepLinkUrl: String?) {
    val launchIntent = if (!deepLinkUrl.isNullOrBlank()) {
      Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUrl)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        setPackage(context.packageName)
      }
    } else {
      context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
      }
    } ?: return

    try {
      context.startActivity(launchIntent)
    } catch (_: ActivityNotFoundException) {
      context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(this)
      }
    }
  }

  companion object {
    const val ACTION_OPEN = "expo.modules.t3nativecontrols.AGENT_ACTIVITY_NOTIFICATION_OPEN"
    const val ACTION_DISMISSED =
      "expo.modules.t3nativecontrols.AGENT_ACTIVITY_NOTIFICATION_DISMISSED"
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    const val EXTRA_ACKNOWLEDGEMENT_KEY = "acknowledgementKey"
    const val EXTRA_DEEP_LINK_URL = "deepLinkUrl"
  }
}
