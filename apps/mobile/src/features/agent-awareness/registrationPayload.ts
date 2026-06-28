import type { RelayDeviceRegistrationRequest } from "@t3tools/contracts/relay";

import type { Preferences } from "../../lib/storage";

interface BaseRelayDeviceRegistrationInput {
  readonly deviceId: string;
  readonly label: string;
  readonly appVersion?: string;
  readonly pushToken?: string;
  readonly notificationsEnabled: boolean;
  readonly preferences: Preferences;
}

type RelayDeviceRegistrationInput = BaseRelayDeviceRegistrationInput &
  (
    | {
        readonly platform: "ios";
        readonly iosMajorVersion: number;
        readonly pushToStartToken?: string;
      }
    | {
        readonly platform: "android";
        readonly androidApiLevel: number;
        readonly notificationChannelId: string;
        readonly alertNotificationChannelId: string;
      }
  );

export function makeRelayDeviceRegistrationRequest(
  input: RelayDeviceRegistrationInput,
): RelayDeviceRegistrationRequest {
  const liveActivitiesEnabled = input.preferences.liveActivitiesEnabled !== false;
  const base = {
    deviceId: input.deviceId,
    label: input.label,
    ...(input.pushToken ? { pushToken: input.pushToken } : {}),
    ...(input.appVersion ? { appVersion: input.appVersion } : {}),
    preferences: {
      liveActivitiesEnabled,
      notificationsEnabled: input.notificationsEnabled,
      notifyOnApproval: true,
      notifyOnInput: true,
      notifyOnCompletion: true,
      notifyOnFailure: true,
    },
  };

  switch (input.platform) {
    case "ios":
      return {
        ...base,
        platform: "ios",
        iosMajorVersion: input.iosMajorVersion,
        ...(input.pushToStartToken ? { pushToStartToken: input.pushToStartToken } : {}),
      };
    case "android":
      return {
        ...base,
        platform: "android",
        androidApiLevel: input.androidApiLevel,
        notificationChannelId: input.notificationChannelId,
        alertNotificationChannelId: input.alertNotificationChannelId,
      };
  }
}
