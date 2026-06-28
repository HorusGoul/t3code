import * as Notifications from "expo-notifications";
import * as Effect from "effect/Effect";
import * as Schema from "effect/Schema";
import { Platform } from "react-native";

import { ensureAndroidAgentActivityNotificationChannels } from "./androidNotifications";

export type NotificationPermissionResult =
  | { readonly type: "unsupported" }
  | { readonly type: "granted" }
  | { readonly type: "denied"; readonly canAskAgain: boolean };

export class NotificationPermissionReadError extends Schema.TaggedErrorClass<NotificationPermissionReadError>()(
  "NotificationPermissionReadError",
  {
    cause: Schema.Defect(),
  },
) {
  override get message(): string {
    return "Failed to read notification permissions for this device.";
  }
}

export class NotificationPermissionRequestError extends Schema.TaggedErrorClass<NotificationPermissionRequestError>()(
  "NotificationPermissionRequestError",
  {
    cause: Schema.Defect(),
  },
) {
  override get message(): string {
    return "Failed to request notification permissions for this device.";
  }
}

function supportsAgentNotifications(): boolean {
  return Platform.OS === "ios" || Platform.OS === "android";
}

const ensurePlatformNotificationSetup = Effect.tryPromise({
  try: () =>
    Platform.OS === "android"
      ? ensureAndroidAgentActivityNotificationChannels()
      : Promise.resolve(null),
  catch: (cause) => new NotificationPermissionRequestError({ cause }),
});

export const requestAgentNotificationPermission: Effect.Effect<
  NotificationPermissionResult,
  NotificationPermissionReadError | NotificationPermissionRequestError
> = Effect.gen(function* () {
  if (!supportsAgentNotifications()) {
    return { type: "unsupported" };
  }

  const existing = yield* Effect.tryPromise({
    try: () => Notifications.getPermissionsAsync(),
    catch: (cause) => new NotificationPermissionReadError({ cause }),
  });
  if (existing.granted) {
    yield* ensurePlatformNotificationSetup;
    return { type: "granted" };
  }

  if (!existing.canAskAgain) {
    return { type: "denied", canAskAgain: false };
  }

  const requested = yield* Effect.tryPromise({
    try: () =>
      Notifications.requestPermissionsAsync({
        android: {},
        ios: {
          allowAlert: true,
          allowBadge: true,
          allowSound: true,
        },
      }),
    catch: (cause) => new NotificationPermissionRequestError({ cause }),
  });
  if (!requested.granted) {
    return { type: "denied", canAskAgain: requested.canAskAgain };
  }

  yield* ensurePlatformNotificationSetup;
  return { type: "granted" };
});
