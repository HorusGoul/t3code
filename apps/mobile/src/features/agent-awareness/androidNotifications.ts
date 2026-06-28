import * as Notifications from "expo-notifications";
import { Platform } from "react-native";

import type { AndroidAgentActivityAlert } from "./localAgentActivity";

export const ANDROID_AGENT_ACTIVITY_NOTIFICATION_CHANNEL_ID = "agent-activity";

let channelReady = false;

export async function ensureAndroidAgentActivityNotificationChannel(): Promise<void> {
  if (Platform.OS !== "android" || channelReady) {
    return;
  }

  await Notifications.setNotificationChannelAsync(ANDROID_AGENT_ACTIVITY_NOTIFICATION_CHANNEL_ID, {
    name: "Agent Activity",
    description: "Local alerts when an agent needs attention or finishes.",
    importance: Notifications.AndroidImportance.DEFAULT,
    lockscreenVisibility: Notifications.AndroidNotificationVisibility.PUBLIC,
    showBadge: true,
    enableVibrate: true,
    vibrationPattern: [0, 250, 250, 250],
  });
  channelReady = true;
}

export async function scheduleAndroidAgentActivityAlert(
  alert: AndroidAgentActivityAlert,
): Promise<void> {
  if (Platform.OS !== "android") {
    return;
  }

  await ensureAndroidAgentActivityNotificationChannel();
  await Notifications.scheduleNotificationAsync({
    identifier: alert.identifier,
    content: {
      title: alert.title,
      body: alert.body,
      data: alert.data,
      priority: Notifications.AndroidNotificationPriority.DEFAULT,
      autoDismiss: true,
    },
    trigger: { channelId: ANDROID_AGENT_ACTIVITY_NOTIFICATION_CHANNEL_ID },
  });
}
