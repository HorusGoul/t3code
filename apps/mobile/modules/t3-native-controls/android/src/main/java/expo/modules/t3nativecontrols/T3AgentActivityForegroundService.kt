package expo.modules.t3nativecontrols

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import org.json.JSONArray
import org.json.JSONObject

class T3AgentActivityForegroundService : Service() {
  private var serviceStartedAtMillis: Long = 0

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    cancelOngoingAgentActivityNotifications()
    super.onDestroy()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      serviceStartedAtMillis = 0
      stopForegroundCompat()
      cancelOngoingAgentActivityNotifications()
      stopSelf()
      return START_NOT_STICKY
    }

    if (serviceStartedAtMillis == 0L) {
      serviceStartedAtMillis = System.currentTimeMillis()
    }

    ensureNotificationChannel()
    val fallbackTitle =
      intent?.getStringExtra(EXTRA_FALLBACK_TITLE)?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE
    val fallbackBody =
      intent?.getStringExtra(EXTRA_FALLBACK_BODY)?.takeIf { it.isNotBlank() } ?: DEFAULT_BODY
    val fallbackChipText = intent
      ?.getStringExtra(EXTRA_FALLBACK_CHIP_TEXT)
      ?.takeIf { it.isNotBlank() }
      ?: DEFAULT_CHIP_TEXT
    val entries = parseNotificationEntries(intent?.getStringExtra(EXTRA_NOTIFICATIONS_JSON))
    syncAgentActivityNotifications(fallbackTitle, fallbackBody, fallbackChipText, entries)
    return START_NOT_STICKY
  }

  private fun startForegroundCompat(notificationId: Int, notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        notificationId,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
      return
    }
    startForeground(notificationId, notification)
  }

  private fun stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      return
    }

    @Suppress("DEPRECATION")
    stopForeground(true)
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }

    val manager = getSystemService(NotificationManager::class.java)
    val existing = manager.getNotificationChannel(SERVICE_CHANNEL_ID)
    if (existing != null) {
      return
    }

    val channel = NotificationChannel(
      SERVICE_CHANNEL_ID,
      SERVICE_CHANNEL_NAME,
      NotificationManager.IMPORTANCE_LOW,
    )
    channel.description = SERVICE_CHANNEL_DESCRIPTION
    channel.setShowBadge(false)
    manager.createNotificationChannel(channel)
  }

  private fun syncAgentActivityNotifications(
    fallbackTitle: String,
    fallbackBody: String,
    fallbackChipText: String,
    entries: List<AgentActivityNotificationEntry>,
  ) {
    val manager = getSystemService(NotificationManager::class.java)
    val acknowledgedTerminalKeys =
      T3AgentActivityNotificationStore.pruneAcknowledgedTerminalKeys(
        this,
        entries.filter { !it.ongoing }.map { it.acknowledgementKey }.toSet(),
      )
    val visibleEntries = entries.filter { entry ->
      entry.ongoing || !acknowledgedTerminalKeys.contains(entry.acknowledgementKey)
    }
    val shouldShowGroupSummary = visibleEntries.size > 1
    val shouldUseAggregateForeground = visibleEntries.size != 1 || !visibleEntries[0].ongoing
    val visibleProgress = progressSnapshotForEntries(visibleEntries)
    val visibleThreadSummary = threadUpdateSummaryText(visibleEntries)
    val aggregateForegroundEntry = AgentActivityNotificationEntry(
      key = LIVE_NOTIFICATION_KEY,
      acknowledgementKey = LIVE_NOTIFICATION_KEY,
      title = fallbackTitle,
      body = if (visibleEntries.isEmpty()) {
        fallbackBody
      } else {
        visibleThreadSummary
      },
      chipText = fallbackChipText,
      phase = AgentActivityPhase.RUNNING,
      deepLinkUrl = null,
      subText = null,
      ongoing = true,
      requestPromoted = visibleEntries.isNotEmpty(),
      occurredAtMillis = null,
      progress = visibleProgress,
    )
    val groupSummaryEntry = AgentActivityNotificationEntry(
      key = GROUP_SUMMARY_NOTIFICATION_KEY,
      acknowledgementKey = GROUP_SUMMARY_NOTIFICATION_KEY,
      title = fallbackTitle,
      body = visibleThreadSummary,
      chipText = fallbackChipText,
      phase = AgentActivityPhase.RUNNING,
      deepLinkUrl = null,
      subText = null,
      ongoing = true,
      requestPromoted = false,
      occurredAtMillis = null,
      progress = visibleProgress,
    )

    val foregroundEntry = if (shouldUseAggregateForeground) {
      aggregateForegroundEntry
    } else {
      visibleEntries[0].copy(progress = visibleProgress)
    }
    val foregroundNotificationId = notificationIdForEntry(foregroundEntry)
    val activeNotificationIds = mutableSetOf(foregroundNotificationId)
    val activeOngoingNotificationIds = mutableSetOf(foregroundNotificationId)
    val activeTerminalNotificationIds = mutableSetOf<Int>()
    startForegroundCompat(
      foregroundNotificationId,
      buildNotification(
        foregroundEntry,
        foregroundNotificationId,
        groupBehavior = NotificationGroupBehavior.NONE,
      ),
    )

    if (shouldShowGroupSummary) {
      val groupSummaryNotificationId = notificationIdForEntry(groupSummaryEntry)
      activeNotificationIds.add(groupSummaryNotificationId)
      activeOngoingNotificationIds.add(groupSummaryNotificationId)
      manager.notify(
        groupSummaryNotificationId,
        buildNotification(
          groupSummaryEntry,
          groupSummaryNotificationId,
          groupBehavior = NotificationGroupBehavior.SUMMARY,
        ),
      )
    }

    for (entry in visibleEntries) {
      if (entry.key == foregroundEntry.key) {
        continue
      }
      val notificationId = notificationIdForEntry(entry)
      activeNotificationIds.add(notificationId)
      if (entry.ongoing) {
        activeOngoingNotificationIds.add(notificationId)
      } else {
        activeTerminalNotificationIds.add(notificationId)
      }
      manager.notify(
        notificationId,
        buildNotification(
          if (shouldShowGroupSummary) {
            entry.copy(requestPromoted = false, progress = null)
          } else {
            entry
          },
          notificationId,
          groupBehavior = if (shouldShowGroupSummary) {
            NotificationGroupBehavior.CHILD
          } else {
            NotificationGroupBehavior.NONE
          },
        ),
      )
    }

    cancelStaleAgentActivityNotifications(manager, activeNotificationIds)
    T3AgentActivityNotificationStore.writePostedOngoingNotificationIds(
      this,
      activeOngoingNotificationIds,
    )
    T3AgentActivityNotificationStore.writePostedTerminalNotificationIds(
      this,
      activeTerminalNotificationIds,
    )
  }

  private fun cancelStaleAgentActivityNotifications(
    manager: NotificationManager,
    activeNotificationIds: Set<Int>,
  ) {
    val previouslyPostedIds =
      T3AgentActivityNotificationStore.readPostedOngoingNotificationIds(this) +
        T3AgentActivityNotificationStore.readPostedTerminalNotificationIds(this) +
        T3AgentActivityNotificationStore.readLegacyPostedNotificationIds(this)

    for (notificationId in previouslyPostedIds) {
      if (!activeNotificationIds.contains(notificationId)) {
        manager.cancel(notificationId)
      }
    }
    if (!activeNotificationIds.contains(LIVE_NOTIFICATION_ID)) {
      manager.cancel(LIVE_NOTIFICATION_ID)
    }
    if (!activeNotificationIds.contains(GROUP_SUMMARY_NOTIFICATION_ID)) {
      manager.cancel(GROUP_SUMMARY_NOTIFICATION_ID)
    }
    T3AgentActivityNotificationStore.clearLegacyPostedNotificationIds(this)
  }

  private fun cancelOngoingAgentActivityNotifications() {
    val manager = getSystemService(NotificationManager::class.java)
    for (notificationId in T3AgentActivityNotificationStore.readPostedOngoingNotificationIds(this)) {
      manager.cancel(notificationId)
    }
    manager.cancel(LIVE_NOTIFICATION_ID)
    manager.cancel(GROUP_SUMMARY_NOTIFICATION_ID)
    T3AgentActivityNotificationStore.clearPostedOngoingNotificationIds(this)
  }

  private fun buildNotification(
    entry: AgentActivityNotificationEntry,
    notificationId: Int,
    groupBehavior: NotificationGroupBehavior,
  ): Notification {
    val contentIntent = if (entry.ongoing) {
      buildActivityContentIntent(entry, notificationId)
    } else {
      buildTerminalNotificationActionIntent(
        T3AgentActivityNotificationActionReceiver.ACTION_OPEN,
        entry,
        notificationId,
      )
    }

    val builder = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
      .setSmallIcon(resolveSmallIcon())
      .setContentTitle(entry.title)
      .setContentText(entry.body)
      .setLocalOnly(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(true)
      .setWhen(entry.occurredAtMillis ?: serviceStartedAtMillis)
      .setShortCriticalText(entry.chipText.take(LIVE_UPDATE_CHIP_TEXT_MAX_LENGTH))
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    if (groupBehavior != NotificationGroupBehavior.NONE) {
      builder.setGroup(AGENT_ACTIVITY_NOTIFICATION_GROUP_KEY)
    }

    if (!entry.subText.isNullOrBlank()) {
      builder.setSubText(entry.subText)
    }
    if (groupBehavior == NotificationGroupBehavior.SUMMARY) {
      builder.setGroupSummary(true)
    }

    if (entry.ongoing) {
      val progressStyle = progressStyleForEntry(entry)
      if (progressStyle != null) {
        builder.setStyle(progressStyle)
      }
      builder
        .setOngoing(true)
        .setCategory(
          if (entry.requestPromoted) {
            NotificationCompat.CATEGORY_PROGRESS
          } else {
            NotificationCompat.CATEGORY_SERVICE
          },
        )
        .setPriority(
          if (entry.requestPromoted) {
            NotificationCompat.PRIORITY_DEFAULT
          } else {
            NotificationCompat.PRIORITY_LOW
          },
        )
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

      if (entry.requestPromoted) {
        builder.setRequestPromotedOngoing(true)
      }
    } else {
      builder
        .setStyle(NotificationCompat.BigTextStyle().bigText(entry.body))
        .setAutoCancel(true)
        .setOngoing(false)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setDeleteIntent(
          buildTerminalNotificationActionIntent(
            T3AgentActivityNotificationActionReceiver.ACTION_DISMISSED,
            entry,
            notificationId,
          ),
        )
    }

    if (contentIntent == null) {
      return builder.build()
    }

    builder.setContentIntent(contentIntent)
    return builder.build()
  }

  private fun progressStyleForEntry(
    entry: AgentActivityNotificationEntry,
  ): NotificationCompat.ProgressStyle? {
    val progress = entry.progress
    if (progress != null && progress.total > 0) {
      val style = NotificationCompat.ProgressStyle()
        .setProgress(progress.completed.coerceIn(0, progress.total))
        .setProgressIndeterminate(false)
        .setStyledByProgress(true)
        .setProgressTrackerIcon(
          IconCompat.createWithResource(this, R.drawable.ic_t3_notification_tracker),
        )
      for (phase in progress.phases) {
        style.addProgressSegment(
          NotificationCompat.ProgressStyle.Segment(1).setColor(progressSegmentColorForPhase(phase)),
        )
      }
      return style
    }

    if (!entry.requestPromoted) {
      return null
    }

    return NotificationCompat.ProgressStyle()
      .setProgressIndeterminate(true)
      .setStyledByProgress(true)
      .setProgressTrackerIcon(
        IconCompat.createWithResource(this, R.drawable.ic_t3_notification_tracker),
      )
  }

  private fun buildActivityContentIntent(
    entry: AgentActivityNotificationEntry,
    notificationId: Int,
  ): PendingIntent? {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val targetIntent = entry.deepLinkUrl?.let { deepLinkUrl ->
      Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUrl)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        setPackage(packageName)
      }
    } ?: launchIntent?.apply {
      addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    return targetIntent?.let { intent ->
      PendingIntent.getActivity(
        this,
        notificationId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }
  }

  private fun buildTerminalNotificationActionIntent(
    action: String,
    entry: AgentActivityNotificationEntry,
    notificationId: Int,
  ): PendingIntent {
    val requestCode = notificationId +
      if (action == T3AgentActivityNotificationActionReceiver.ACTION_OPEN) {
        OPEN_REQUEST_CODE_OFFSET
      } else {
        DISMISS_REQUEST_CODE_OFFSET
      }
    val intent = Intent(this, T3AgentActivityNotificationActionReceiver::class.java).apply {
      this.action = action
      putExtra(T3AgentActivityNotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
      putExtra(
        T3AgentActivityNotificationActionReceiver.EXTRA_ACKNOWLEDGEMENT_KEY,
        entry.acknowledgementKey,
      )
      if (!entry.deepLinkUrl.isNullOrBlank()) {
        putExtra(T3AgentActivityNotificationActionReceiver.EXTRA_DEEP_LINK_URL, entry.deepLinkUrl)
      }
    }

    return PendingIntent.getBroadcast(
      this,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun resolveSmallIcon(): Int {
    return R.drawable.ic_t3_notification
  }

  private fun parseNotificationEntries(raw: String?): List<AgentActivityNotificationEntry> {
    if (raw.isNullOrBlank()) {
      return emptyList()
    }

    val array = try {
      JSONArray(raw)
    } catch (_: Exception) {
      return emptyList()
    }
    val entries = mutableListOf<AgentActivityNotificationEntry>()
    for (index in 0 until array.length()) {
      val item = array.optJSONObject(index) ?: continue
      val key = item.nonBlankString("key") ?: continue
      val title = item.nonBlankString("title") ?: continue
      val body = item.nonBlankString("body") ?: continue
      val chipText = item.nonBlankString("chipText") ?: continue
      val ongoing = item.optBoolean("ongoing", true)
      entries.add(
        AgentActivityNotificationEntry(
          key = key,
          acknowledgementKey = item.nonBlankString("acknowledgementKey") ?: key,
          title = title,
          body = body,
          chipText = chipText,
          phase = AgentActivityPhase.fromWireValue(item.nonBlankString("phase"), ongoing),
          deepLinkUrl = item.nonBlankString("deepLinkUrl"),
          subText = item.nonBlankString("subText"),
          ongoing = ongoing,
          requestPromoted = ongoing,
          occurredAtMillis = item.longOrNull("updatedAtMillis"),
          progress = null,
        ),
      )
    }
    return entries
  }

  private fun notificationIdForEntry(entry: AgentActivityNotificationEntry): Int {
    if (entry.key == LIVE_NOTIFICATION_KEY) {
      return LIVE_NOTIFICATION_ID
    }
    if (entry.key == GROUP_SUMMARY_NOTIFICATION_KEY) {
      return GROUP_SUMMARY_NOTIFICATION_ID
    }
    return THREAD_NOTIFICATION_ID_OFFSET + (entry.key.hashCode() and THREAD_NOTIFICATION_ID_MASK)
  }

  private fun progressSnapshotForEntries(
    entries: List<AgentActivityNotificationEntry>,
  ): AgentActivityProgressSnapshot? {
    if (entries.isEmpty()) {
      return null
    }
    return AgentActivityProgressSnapshot(
      completed = entries.count { !it.ongoing },
      phases = entries.sortedWith(AGENT_ACTIVITY_PROGRESS_ENTRY_COMPARATOR).map { it.phase },
    )
  }

  private fun progressSegmentColorForPhase(phase: AgentActivityPhase): Int =
    when (phase) {
      AgentActivityPhase.WAITING_FOR_APPROVAL,
      AgentActivityPhase.WAITING_FOR_INPUT -> PROGRESS_SEGMENT_REVIEW
      AgentActivityPhase.FAILED -> PROGRESS_SEGMENT_FAILED
      AgentActivityPhase.COMPLETED -> PROGRESS_SEGMENT_COMPLETED
      AgentActivityPhase.RUNNING -> PROGRESS_SEGMENT_RUNNING
      AgentActivityPhase.STARTING -> PROGRESS_SEGMENT_STARTING
      AgentActivityPhase.STALE -> PROGRESS_SEGMENT_STALE
    }

  private fun threadUpdateSummaryText(entries: List<AgentActivityNotificationEntry>): String {
    if (entries.isEmpty()) {
      return DEFAULT_BODY
    }

    val titles = entries.map { it.title.trim() }.filter { it.isNotEmpty() }
    if (titles.isEmpty()) {
      return DEFAULT_BODY
    }

    val visibleTitles = titles.take(MAX_THREAD_SUMMARY_TITLES)
    val remainingCount = titles.size - visibleTitles.size
    val summary = visibleTitles.joinToString(", ")
    if (remainingCount <= 0) {
      return summary
    }
    return "$summary, +$remainingCount more"
  }

  private fun JSONObject.nonBlankString(name: String): String? =
    optString(name).takeIf { it.isNotBlank() }

  private fun JSONObject.longOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) {
      return null
    }
    return optLong(name).takeIf { it > 0L }
  }

  companion object {
    private const val ACTION_START = "expo.modules.t3nativecontrols.AGENT_ACTIVITY_START"
    private const val ACTION_STOP = "expo.modules.t3nativecontrols.AGENT_ACTIVITY_STOP"
    private const val EXTRA_FALLBACK_TITLE = "fallbackTitle"
    private const val EXTRA_FALLBACK_BODY = "fallbackBody"
    private const val EXTRA_FALLBACK_CHIP_TEXT = "fallbackChipText"
    private const val EXTRA_NOTIFICATIONS_JSON = "notificationsJson"
    private const val LIVE_NOTIFICATION_KEY = "agent-activity-live"
    private const val GROUP_SUMMARY_NOTIFICATION_KEY = "agent-activity-summary"
    private const val LIVE_NOTIFICATION_ID = 4103
    private const val GROUP_SUMMARY_NOTIFICATION_ID = 4102
    private const val THREAD_NOTIFICATION_ID_OFFSET = 4104
    private const val THREAD_NOTIFICATION_ID_MASK = 0x00ffffff
    private const val OPEN_REQUEST_CODE_OFFSET = 0x10000000
    private const val DISMISS_REQUEST_CODE_OFFSET = 0x20000000
    private const val AGENT_ACTIVITY_NOTIFICATION_GROUP_KEY =
      "expo.modules.t3nativecontrols.AGENT_ACTIVITY_NOTIFICATIONS"
    private const val SERVICE_CHANNEL_ID = "agent-activity-service"
    private const val SERVICE_CHANNEL_NAME = "Agent Activity Connection"
    private const val SERVICE_CHANNEL_DESCRIPTION =
      "Keeps T3 Code connected to local agent updates while the app is in the background."
    private const val DEFAULT_TITLE = "T3 Code"
    private const val DEFAULT_BODY = "Agent updates are connected."
    private const val DEFAULT_CHIP_TEXT = "T3Code"
    private const val LIVE_UPDATE_CHIP_TEXT_MAX_LENGTH = 6
    private const val MAX_THREAD_SUMMARY_TITLES = 2
    private val PROGRESS_SEGMENT_COMPLETED = Color.rgb(230, 255, 244)
    private val PROGRESS_SEGMENT_RUNNING = Color.rgb(56, 189, 248)
    private val PROGRESS_SEGMENT_REVIEW = Color.rgb(251, 191, 36)
    private val PROGRESS_SEGMENT_FAILED = Color.rgb(248, 113, 113)
    private val PROGRESS_SEGMENT_STARTING = Color.rgb(148, 163, 184)
    private val PROGRESS_SEGMENT_STALE = Color.rgb(100, 116, 139)
    private val AGENT_ACTIVITY_PROGRESS_ENTRY_COMPARATOR =
      compareByDescending<AgentActivityNotificationEntry> { !it.ongoing }
        .thenBy { it.phase.progressOrder }

    fun start(
      context: Context,
      fallbackTitle: String,
      fallbackBody: String,
      fallbackChipText: String,
      notificationsJson: String,
    ) {
      val appContext = context.applicationContext
      val intent = Intent(appContext, T3AgentActivityForegroundService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_FALLBACK_TITLE, fallbackTitle)
        putExtra(EXTRA_FALLBACK_BODY, fallbackBody)
        putExtra(EXTRA_FALLBACK_CHIP_TEXT, fallbackChipText)
        putExtra(EXTRA_NOTIFICATIONS_JSON, notificationsJson)
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        appContext.startForegroundService(intent)
        return
      }

      appContext.startService(intent)
    }

    fun stop(context: Context) {
      val appContext = context.applicationContext
      appContext.stopService(Intent(appContext, T3AgentActivityForegroundService::class.java))
    }
  }

  private data class AgentActivityNotificationEntry(
    val key: String,
    val acknowledgementKey: String,
    val title: String,
    val body: String,
    val chipText: String,
    val phase: AgentActivityPhase,
    val deepLinkUrl: String?,
    val subText: String?,
    val ongoing: Boolean,
    val requestPromoted: Boolean,
    val occurredAtMillis: Long?,
    val progress: AgentActivityProgressSnapshot?,
  )

  private data class AgentActivityProgressSnapshot(
    val completed: Int,
    val phases: List<AgentActivityPhase>,
  ) {
    val total: Int
      get() = phases.size
  }

  private enum class AgentActivityPhase(val wireValue: String, val progressOrder: Int) {
    COMPLETED("completed", 0),
    FAILED("failed", 1),
    WAITING_FOR_APPROVAL("waiting_for_approval", 2),
    WAITING_FOR_INPUT("waiting_for_input", 3),
    RUNNING("running", 4),
    STARTING("starting", 5),
    STALE("stale", 6);

    companion object {
      fun fromWireValue(value: String?, ongoing: Boolean): AgentActivityPhase {
        for (phase in entries) {
          if (phase.wireValue == value) {
            return phase
          }
        }
        return if (ongoing) {
          RUNNING
        } else {
          COMPLETED
        }
      }
    }
  }

  private enum class NotificationGroupBehavior {
    NONE,
    CHILD,
    SUMMARY,
  }
}
