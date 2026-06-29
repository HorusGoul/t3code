import type { RelayClientDeviceRecord } from "@t3tools/contracts/relay";
import { describe, expect, it } from "vite-plus/test";

import {
  mobileClientActivityUpdatesLabel,
  mobileClientNotificationDetail,
  mobileClientPlatformLabel,
  mobileClientUpdatedAtLabel,
} from "./MobileClientsUserProfilePage.logic";

type IosDeviceRecord = Extract<RelayClientDeviceRecord, { readonly platform: "ios" }>;
type AndroidDeviceRecord = Extract<RelayClientDeviceRecord, { readonly platform: "android" }>;

function device(overrides: Partial<IosDeviceRecord> = {}): IosDeviceRecord {
  return {
    deviceId: "device-1",
    label: "Julius’s iPhone",
    platform: "ios",
    iosMajorVersion: 18,
    appVersion: "1.2.3",
    notifications: {
      enabled: true,
      notifyOnApproval: true,
      notifyOnInput: false,
      notifyOnCompletion: true,
      notifyOnFailure: false,
    },
    liveActivities: { enabled: true },
    updatedAt: "2026-06-21T12:00:00.000Z",
    ...overrides,
  };
}

function androidDevice(overrides: Partial<AndroidDeviceRecord> = {}): AndroidDeviceRecord {
  return {
    deviceId: "device-2",
    label: "Julius’s Pixel",
    platform: "android",
    androidApiLevel: 35,
    appVersion: "1.2.3",
    notificationChannelId: "agent-activity",
    alertNotificationChannelId: "agent-activity-alerts",
    notifications: {
      enabled: true,
      notifyOnApproval: true,
      notifyOnInput: true,
      notifyOnCompletion: true,
      notifyOnFailure: true,
    },
    liveActivities: { enabled: true },
    updatedAt: "2026-06-21T12:00:00.000Z",
    ...overrides,
  };
}

describe("mobile client presentation", () => {
  it("describes the client platform and enabled notification events", () => {
    const client = device();

    expect(mobileClientPlatformLabel(client)).toBe("iOS 18 · T3 Code 1.2.3");
    expect(mobileClientActivityUpdatesLabel(client)).toBe("Live Activities");
    expect(mobileClientNotificationDetail(client)).toBe(
      "Alerts enabled for approvals, completions.",
    );
  });

  it("describes Android agent activity clients", () => {
    const client = androidDevice();

    expect(mobileClientPlatformLabel(client)).toBe("Android API 35 · T3 Code 1.2.3");
    expect(mobileClientActivityUpdatesLabel(client)).toBe("Agent Activity");
  });

  it("distinguishes disabled notifications from an empty event selection", () => {
    expect(
      mobileClientNotificationDetail(
        device({ notifications: { ...device().notifications, enabled: false } }),
      ),
    ).toBe("Push notifications are disabled on this device.");
    expect(
      mobileClientNotificationDetail(
        device({
          notifications: {
            enabled: true,
            notifyOnApproval: false,
            notifyOnInput: false,
            notifyOnCompletion: false,
            notifyOnFailure: false,
          },
        }),
      ),
    ).toBe("Push notifications are enabled, but no alert types are selected.");
  });

  it("handles missing app versions and invalid update timestamps", () => {
    expect(mobileClientPlatformLabel(device({ appVersion: null }))).toBe("iOS 18");
    expect(
      mobileClientPlatformLabel(androidDevice({ androidApiLevel: null, appVersion: null })),
    ).toBe("Android");
    expect(mobileClientUpdatedAtLabel("not-a-date")).toBe("Update time unavailable");
  });
});
