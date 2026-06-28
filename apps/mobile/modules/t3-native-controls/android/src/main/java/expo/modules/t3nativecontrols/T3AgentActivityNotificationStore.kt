package expo.modules.t3nativecontrols

import android.content.Context

object T3AgentActivityNotificationStore {
  fun readPostedOngoingNotificationIds(context: Context): Set<Int> =
    readIntSet(context, PREFERENCE_POSTED_ONGOING_NOTIFICATION_IDS)

  fun writePostedOngoingNotificationIds(context: Context, notificationIds: Set<Int>) {
    writeIntSet(context, PREFERENCE_POSTED_ONGOING_NOTIFICATION_IDS, notificationIds)
  }

  fun clearPostedOngoingNotificationIds(context: Context) {
    preferences(context)
      .edit()
      .remove(PREFERENCE_POSTED_ONGOING_NOTIFICATION_IDS)
      .apply()
  }

  fun readPostedTerminalNotificationIds(context: Context): Set<Int> =
    readIntSet(context, PREFERENCE_POSTED_TERMINAL_NOTIFICATION_IDS)

  fun writePostedTerminalNotificationIds(context: Context, notificationIds: Set<Int>) {
    writeIntSet(context, PREFERENCE_POSTED_TERMINAL_NOTIFICATION_IDS, notificationIds)
  }

  fun removePostedTerminalNotificationId(context: Context, notificationId: Int) {
    writePostedTerminalNotificationIds(
      context,
      readPostedTerminalNotificationIds(context) - notificationId,
    )
  }

  fun readLegacyPostedNotificationIds(context: Context): Set<Int> =
    readIntSet(context, PREFERENCE_LEGACY_POSTED_NOTIFICATION_IDS)

  fun clearLegacyPostedNotificationIds(context: Context) {
    preferences(context)
      .edit()
      .remove(PREFERENCE_LEGACY_POSTED_NOTIFICATION_IDS)
      .apply()
  }

  fun markTerminalAcknowledged(context: Context, acknowledgementKey: String) {
    if (acknowledgementKey.isBlank()) {
      return
    }
    preferences(context)
      .edit()
      .putStringSet(
        PREFERENCE_ACKNOWLEDGED_TERMINAL_KEYS,
        readAcknowledgedTerminalKeys(context) + acknowledgementKey,
      )
      .apply()
  }

  fun pruneAcknowledgedTerminalKeys(
    context: Context,
    currentTerminalAcknowledgementKeys: Set<String>,
  ): Set<String> {
    val retained = readAcknowledgedTerminalKeys(context).intersect(currentTerminalAcknowledgementKeys)
    if (retained.size != readAcknowledgedTerminalKeys(context).size) {
      preferences(context)
        .edit()
        .putStringSet(PREFERENCE_ACKNOWLEDGED_TERMINAL_KEYS, retained)
        .apply()
    }
    return retained
  }

  private fun readAcknowledgedTerminalKeys(context: Context): Set<String> =
    preferences(context)
      .getStringSet(PREFERENCE_ACKNOWLEDGED_TERMINAL_KEYS, emptySet())
      ?.toSet()
      ?: emptySet()

  private fun readIntSet(context: Context, preferenceName: String): Set<Int> =
    preferences(context)
      .getStringSet(preferenceName, emptySet())
      ?.mapNotNull { it.toIntOrNull() }
      ?.toSet()
      ?: emptySet()

  private fun writeIntSet(context: Context, preferenceName: String, values: Set<Int>) {
    preferences(context)
      .edit()
      .putStringSet(preferenceName, values.map { it.toString() }.toSet())
      .apply()
  }

  private fun preferences(context: Context) =
    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  private const val PREFERENCES_NAME = "t3_agent_activity_notifications"
  private const val PREFERENCE_POSTED_ONGOING_NOTIFICATION_IDS =
    "posted_ongoing_notification_ids"
  private const val PREFERENCE_POSTED_TERMINAL_NOTIFICATION_IDS =
    "posted_terminal_notification_ids"
  private const val PREFERENCE_LEGACY_POSTED_NOTIFICATION_IDS = "posted_notification_ids"
  private const val PREFERENCE_ACKNOWLEDGED_TERMINAL_KEYS = "acknowledged_terminal_keys"
}
