import { describe, expect, it } from "vite-plus/test";
import * as Schema from "effect/Schema";
import * as OpenApi from "effect/unstable/httpapi/OpenApi";

import { RelayApi, RelayClientDeviceRecord, RelayDeviceRegistrationRequest } from "./relay.ts";

const decodeRelayDeviceRegistrationRequest = Schema.decodeUnknownSync(
  RelayDeviceRegistrationRequest,
);
const decodeRelayClientDeviceRecord = Schema.decodeUnknownSync(RelayClientDeviceRecord);

describe("RelayApi security", () => {
  it("describes DPoP access tokens using the HTTP DPoP authorization scheme", () => {
    const document = OpenApi.fromApi(RelayApi);

    expect(document.components.securitySchemes?.relayDpop).toEqual({
      type: "http",
      scheme: "DPoP",
      description: "DPoP-bound access token. Requests must also include the DPoP proof JWT header.",
    });
  });
});

describe("Relay device schemas", () => {
  it("accepts Android agent activity notification registration payloads", () => {
    expect(
      decodeRelayDeviceRegistrationRequest({
        deviceId: "device-1",
        label: "Julius Pixel",
        platform: "android",
        androidApiLevel: 35,
        appVersion: "1.0.0",
        pushToken: "fcm-token",
        notificationChannelId: "agent-activity",
        alertNotificationChannelId: "agent-activity-alerts",
        preferences: {
          liveActivitiesEnabled: true,
          notificationsEnabled: true,
          notifyOnApproval: true,
          notifyOnInput: true,
          notifyOnCompletion: true,
          notifyOnFailure: true,
        },
      }),
    ).toMatchObject({
      platform: "android",
      notificationChannelId: "agent-activity",
      alertNotificationChannelId: "agent-activity-alerts",
    });
  });

  it("accepts Android client device records", () => {
    expect(
      decodeRelayClientDeviceRecord({
        deviceId: "device-1",
        label: "Julius Pixel",
        platform: "android",
        androidApiLevel: 35,
        appVersion: null,
        notificationChannelId: "agent-activity",
        alertNotificationChannelId: "agent-activity-alerts",
        notifications: {
          enabled: true,
          notifyOnApproval: true,
          notifyOnInput: true,
          notifyOnCompletion: true,
          notifyOnFailure: true,
        },
        liveActivities: {
          enabled: true,
        },
        updatedAt: "2026-06-01T00:00:00.000Z",
      }),
    ).toMatchObject({
      platform: "android",
      androidApiLevel: 35,
    });
  });
});
