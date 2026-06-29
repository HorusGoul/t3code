import * as Notifications from "expo-notifications";
import { Platform } from "react-native";

import { extractAgentNotificationDataFromNotification } from "./notificationPayload";

export const ANDROID_AGENT_ACTIVITY_NOTIFICATION_CHANNEL_ID = "agent-activity";
export const ANDROID_AGENT_ACTIVITY_ALERT_NOTIFICATION_CHANNEL_ID = "agent-activity-alerts";

export interface AndroidAgentActivityNotificationChannels {
  readonly notificationChannelId: string;
  readonly alertNotificationChannelId: string;
}

const PROMINENT_AGENT_ACTIVITY_PHASES = new Set([
  "waiting_for_approval",
  "waiting_for_input",
  "failed",
]);

let notificationHandlerConfigured = false;

export function androidAgentActivityNotificationChannels(): AndroidAgentActivityNotificationChannels | null {
  if (Platform.OS !== "android") {
    return null;
  }
  return {
    notificationChannelId: ANDROID_AGENT_ACTIVITY_NOTIFICATION_CHANNEL_ID,
    alertNotificationChannelId: ANDROID_AGENT_ACTIVITY_ALERT_NOTIFICATION_CHANNEL_ID,
  };
}

export async function ensureAndroidAgentActivityNotificationChannels(): Promise<AndroidAgentActivityNotificationChannels | null> {
  const channels = androidAgentActivityNotificationChannels();
  if (!channels) {
    return null;
  }

  await Notifications.setNotificationChannelAsync(channels.notificationChannelId, {
    name: "Agent Activity",
    description: "Ongoing T3 Code agent status updates.",
    importance: Notifications.AndroidImportance.DEFAULT,
    lockscreenVisibility: Notifications.AndroidNotificationVisibility.PUBLIC,
    showBadge: false,
    sound: null,
  });
  await Notifications.setNotificationChannelAsync(channels.alertNotificationChannelId, {
    name: "Agent Activity Alerts",
    description: "Prominent T3 Code agent updates that need attention.",
    importance: Notifications.AndroidImportance.HIGH,
    lockscreenVisibility: Notifications.AndroidNotificationVisibility.PUBLIC,
    showBadge: true,
    sound: "default",
    enableVibrate: true,
  });

  return channels;
}

export function agentNotificationBehaviorForData(
  data: Record<string, unknown> | null,
): Notifications.NotificationBehavior {
  const phase = typeof data?.phase === "string" ? data.phase : null;
  const isAgentActivityNotification =
    typeof data?.deepLink === "string" ||
    (typeof data?.environmentId === "string" && typeof data?.threadId === "string");
  const isProminent =
    isAgentActivityNotification && phase !== null && PROMINENT_AGENT_ACTIVITY_PHASES.has(phase);

  return {
    shouldShowBanner: isProminent || !isAgentActivityNotification,
    shouldShowList: true,
    shouldPlaySound: isProminent,
    shouldSetBadge: false,
    priority: isProminent
      ? Notifications.AndroidNotificationPriority.HIGH
      : Notifications.AndroidNotificationPriority.DEFAULT,
  };
}

export function configureAndroidAgentActivityNotificationHandling(): void {
  if (notificationHandlerConfigured || Platform.OS !== "android") {
    return;
  }

  notificationHandlerConfigured = true;
  Notifications.setNotificationHandler({
    handleNotification: async (notification) =>
      agentNotificationBehaviorForData(extractAgentNotificationDataFromNotification(notification)),
  });
}

export function __resetAndroidAgentActivityNotificationsForTest(): void {
  notificationHandlerConfigured = false;
}
