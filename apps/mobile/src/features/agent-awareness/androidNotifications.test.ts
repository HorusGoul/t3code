import { beforeEach, describe, expect, it, vi } from "vite-plus/test";
import * as Notifications from "expo-notifications";
import { Platform } from "react-native";

import {
  ANDROID_AGENT_ACTIVITY_ALERT_NOTIFICATION_CHANNEL_ID,
  ANDROID_AGENT_ACTIVITY_NOTIFICATION_CHANNEL_ID,
  __resetAndroidAgentActivityNotificationsForTest,
  agentNotificationBehaviorForData,
  configureAndroidAgentActivityNotificationHandling,
  ensureAndroidAgentActivityNotificationChannels,
} from "./androidNotifications";

vi.mock("react-native", () => ({
  Platform: {
    OS: "android",
  },
}));

vi.mock("expo-notifications", () => ({
  AndroidImportance: {
    DEFAULT: 5,
    HIGH: 6,
  },
  AndroidNotificationPriority: {
    DEFAULT: "default",
    HIGH: "high",
  },
  AndroidNotificationVisibility: {
    PUBLIC: 1,
  },
  setNotificationChannelAsync: vi.fn(() => Promise.resolve(null)),
  setNotificationHandler: vi.fn(),
}));

describe("Android agent activity notifications", () => {
  beforeEach(() => {
    Object.assign(Platform, { OS: "android" });
    __resetAndroidAgentActivityNotificationsForTest();
    vi.clearAllMocks();
  });

  it("configures ongoing and alert notification channels", async () => {
    await ensureAndroidAgentActivityNotificationChannels();

    expect(Notifications.setNotificationChannelAsync).toHaveBeenCalledWith(
      ANDROID_AGENT_ACTIVITY_NOTIFICATION_CHANNEL_ID,
      expect.objectContaining({
        name: "Agent Activity",
        importance: Notifications.AndroidImportance.DEFAULT,
        showBadge: false,
      }),
    );
    expect(Notifications.setNotificationChannelAsync).toHaveBeenCalledWith(
      ANDROID_AGENT_ACTIVITY_ALERT_NOTIFICATION_CHANNEL_ID,
      expect.objectContaining({
        name: "Agent Activity Alerts",
        importance: Notifications.AndroidImportance.HIGH,
        showBadge: true,
      }),
    );
  });

  it("keeps running agent updates quiet in the foreground", () => {
    expect(
      agentNotificationBehaviorForData({
        environmentId: "env-1",
        threadId: "thread-1",
        phase: "running",
      }),
    ).toEqual({
      shouldShowBanner: false,
      shouldShowList: true,
      shouldPlaySound: false,
      shouldSetBadge: false,
      priority: Notifications.AndroidNotificationPriority.DEFAULT,
    });
  });

  it("surfaces waiting agent updates prominently in the foreground", () => {
    expect(
      agentNotificationBehaviorForData({
        deepLink: "/threads/env-1/thread-1",
        phase: "waiting_for_input",
      }),
    ).toEqual({
      shouldShowBanner: true,
      shouldShowList: true,
      shouldPlaySound: true,
      shouldSetBadge: false,
      priority: Notifications.AndroidNotificationPriority.HIGH,
    });
  });

  it("registers the foreground handler once on Android only", () => {
    configureAndroidAgentActivityNotificationHandling();
    configureAndroidAgentActivityNotificationHandling();

    expect(Notifications.setNotificationHandler).toHaveBeenCalledTimes(1);

    Object.assign(Platform, { OS: "ios" });
    __resetAndroidAgentActivityNotificationsForTest();
    configureAndroidAgentActivityNotificationHandling();

    expect(Notifications.setNotificationHandler).toHaveBeenCalledTimes(1);
  });
});
