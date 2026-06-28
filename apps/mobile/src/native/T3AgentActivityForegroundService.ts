import { requireOptionalNativeModule } from "expo";
import * as Linking from "expo-linking";
import { Platform } from "react-native";

import type { AndroidAgentActivityServiceNotification } from "../features/agent-awareness/localAgentActivity";

interface T3NativeControlsModule {
  readonly startAgentActivityForegroundServiceAsync: (
    fallbackTitle: string,
    fallbackBody: string,
    fallbackChipText: string,
    notificationsJson: string,
  ) => Promise<void>;
  readonly updateAgentActivityForegroundServiceAsync: (
    fallbackTitle: string,
    fallbackBody: string,
    fallbackChipText: string,
    notificationsJson: string,
  ) => Promise<void>;
  readonly stopAgentActivityForegroundServiceAsync: () => Promise<void>;
}

const nativeModule =
  Platform.OS === "android"
    ? requireOptionalNativeModule<T3NativeControlsModule>("T3NativeControls")
    : null;

const ANDROID_LIVE_UPDATE_CHIP_TEXT_MAX_LENGTH = 6;

function normalizeNotificationText(value: string): string {
  const trimmed = value.replace(/\s+/g, " ").trim();
  return trimmed.length > 0 ? trimmed : "T3 Code";
}

function normalizeNotificationChipText(value: string): string {
  const trimmed = value.replace(/\s+/g, " ").trim();
  return (trimmed.length > 0 ? trimmed : "T3Code").slice(
    0,
    ANDROID_LIVE_UPDATE_CHIP_TEXT_MAX_LENGTH,
  );
}

export async function startAndroidAgentActivityForegroundService(input: {
  readonly fallbackTitle: string;
  readonly fallbackBody: string;
  readonly fallbackChipText: string;
  readonly notifications: ReadonlyArray<AndroidAgentActivityServiceNotification>;
}): Promise<void> {
  if (Platform.OS !== "android" || !nativeModule) {
    return;
  }
  await nativeModule.startAgentActivityForegroundServiceAsync(
    normalizeNotificationText(input.fallbackTitle),
    normalizeNotificationText(input.fallbackBody),
    normalizeNotificationChipText(input.fallbackChipText),
    serializeNotifications(input.notifications),
  );
}

export async function updateAndroidAgentActivityForegroundService(input: {
  readonly fallbackTitle: string;
  readonly fallbackBody: string;
  readonly fallbackChipText: string;
  readonly notifications: ReadonlyArray<AndroidAgentActivityServiceNotification>;
}): Promise<void> {
  if (Platform.OS !== "android" || !nativeModule) {
    return;
  }
  await nativeModule.updateAgentActivityForegroundServiceAsync(
    normalizeNotificationText(input.fallbackTitle),
    normalizeNotificationText(input.fallbackBody),
    normalizeNotificationChipText(input.fallbackChipText),
    serializeNotifications(input.notifications),
  );
}

export async function stopAndroidAgentActivityForegroundService(): Promise<void> {
  if (Platform.OS !== "android" || !nativeModule) {
    return;
  }
  await nativeModule.stopAgentActivityForegroundServiceAsync();
}

function serializeNotifications(
  notifications: ReadonlyArray<AndroidAgentActivityServiceNotification>,
): string {
  return JSON.stringify(
    notifications.map((notification) => ({
      key: normalizeNotificationText(notification.key),
      acknowledgementKey: normalizeNotificationText(notification.acknowledgementKey),
      title: normalizeNotificationText(notification.title),
      body: normalizeNotificationText(notification.body),
      chipText: normalizeNotificationChipText(notification.chipText),
      phase: normalizeNotificationText(notification.phase),
      deepLinkUrl: Linking.createURL(notification.deepLink),
      subText: normalizeNotificationText(notification.subText),
      ongoing: notification.ongoing,
      updatedAt: notification.updatedAt,
      updatedAtMillis: timestampMillis(notification.updatedAt),
    })),
  );
}

function timestampMillis(value: string): number | null {
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}
