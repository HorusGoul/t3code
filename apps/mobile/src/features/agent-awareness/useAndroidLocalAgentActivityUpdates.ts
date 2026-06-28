import { useAtomValue } from "@effect/atom-react";
import type { EnvironmentThread } from "@t3tools/client-runtime/state/shell";
import type { AgentAwarenessState } from "@t3tools/shared/agentAwareness";
import * as Notifications from "expo-notifications";
import * as Option from "effect/Option";
import { Atom } from "effect/unstable/reactivity";
import { useEffect, useRef, useState } from "react";
import { AppState, Platform, type AppStateStatus } from "react-native";

import {
  startAndroidAgentActivityForegroundService,
  stopAndroidAgentActivityForegroundService,
  updateAndroidAgentActivityForegroundService,
} from "../../native/T3AgentActivityForegroundService";
import { environmentCatalog } from "../../connection/catalog";
import { loadPreferences } from "../../lib/storage";
import { environmentShell } from "../../state/shell";
import { environmentThreadDetails } from "../../state/threads";
import { scheduleAndroidAgentActivityAlert } from "./androidNotifications";
import {
  type AndroidAgentActivityObservedState,
  agentActivityStatesFromShell,
  buildAndroidAgentActivityServiceStatus,
  reconcileAndroidAgentActivityAlerts,
} from "./localAgentActivity";
import { subscribeLiveActivityPreferenceChanges } from "./liveActivityPreferences";

interface AndroidLocalAgentActivitySnapshot {
  readonly environmentCount: number;
  readonly states: ReadonlyArray<AgentAwarenessState>;
  readonly threadDetails: ReadonlyMap<string, EnvironmentThread>;
}

const EMPTY_ANDROID_LOCAL_AGENT_ACTIVITY_SNAPSHOT: AndroidLocalAgentActivitySnapshot =
  Object.freeze({
    environmentCount: 0,
    states: [],
    threadDetails: new Map(),
  });

const androidLocalAgentActivitySnapshotAtom = Atom.make((get) => {
  const catalog = get(environmentCatalog.catalogValueAtom);
  if (catalog.entries.size === 0) {
    return EMPTY_ANDROID_LOCAL_AGENT_ACTIVITY_SNAPSHOT;
  }

  const states: AgentAwarenessState[] = [];
  for (const environmentId of catalog.entries.keys()) {
    const shellState = get(environmentShell.stateValueAtom(environmentId));
    const snapshot = Option.getOrNull(shellState.snapshot);
    if (!snapshot) {
      continue;
    }
    states.push(...agentActivityStatesFromShell({ environmentId, snapshot }));
  }

  const threadDetails = new Map<string, EnvironmentThread>();
  for (const state of states) {
    const detail = get(
      environmentThreadDetails.detailAtom({
        environmentId: state.environmentId,
        threadId: state.threadId,
      }),
    );
    if (detail) {
      threadDetails.set(androidAgentActivityThreadKey(state), detail);
    }
  }

  return {
    environmentCount: catalog.entries.size,
    states,
    threadDetails,
  };
}).pipe(Atom.withLabel("android-local-agent-activity-snapshot"));

const androidLocalAgentActivityPlatformSnapshotAtom =
  Platform.OS === "android"
    ? androidLocalAgentActivitySnapshotAtom
    : Atom.make(EMPTY_ANDROID_LOCAL_AGENT_ACTIVITY_SNAPSHOT).pipe(
        Atom.withLabel("android-local-agent-activity-snapshot:noop"),
      );

export function useAndroidLocalAgentActivityUpdates(): void {
  const snapshot = useAtomValue(androidLocalAgentActivityPlatformSnapshotAtom);
  const appState = useAppStateStatus();
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [notificationsGranted, setNotificationsGranted] = useState<boolean | null>(null);
  const nowMs = useAndroidAgentActivityClock(
    Platform.OS === "android" &&
      enabled === true &&
      notificationsGranted === true &&
      snapshot.states.length > 0,
  );
  const serviceSignatureRef = useRef<string | null>(null);
  const observedStateRef = useRef<ReadonlyMap<string, AndroidAgentActivityObservedState>>(
    new Map(),
  );

  useEffect(() => {
    if (Platform.OS !== "android") {
      return;
    }

    let cancelled = false;
    const refresh = async () => {
      try {
        const [preferences, permissions] = await Promise.all([
          loadPreferences(),
          Notifications.getPermissionsAsync(),
        ]);
        if (cancelled) {
          return;
        }
        setEnabled(preferences.liveActivitiesEnabled !== false);
        setNotificationsGranted(permissions.granted);
      } catch (error) {
        logAndroidAgentActivityError("Could not refresh Android agent activity settings.", error);
        if (!cancelled) {
          setEnabled(false);
          setNotificationsGranted(false);
        }
      }
    };

    void refresh();
    const unsubscribe = subscribeLiveActivityPreferenceChanges((nextEnabled) => {
      setEnabled(nextEnabled);
      void refreshAndroidNotificationPermission(setNotificationsGranted);
    });

    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, []);

  useEffect(() => {
    if (Platform.OS !== "android" || appState !== "active") {
      return;
    }
    void refreshAndroidNotificationPermission(setNotificationsGranted);
  }, [appState]);

  useEffect(() => {
    if (Platform.OS !== "android") {
      return;
    }

    const canUseNotifications = enabled === true && notificationsGranted === true;
    const reconciliation = reconcileAndroidAgentActivityAlerts({
      previous: observedStateRef.current,
      states: snapshot.states,
      canNotify: canUseNotifications && appState !== "active",
      nowMs,
    });
    observedStateRef.current = reconciliation.next;

    const status = buildAndroidAgentActivityServiceStatus({
      enabled: canUseNotifications,
      environmentCount: snapshot.environmentCount,
      states: snapshot.states,
      threadDetails: snapshot.threadDetails,
      nowMs,
    });

    void (async () => {
      try {
        if (
          status.type === "stopped" ||
          !status.fallbackTitle ||
          !status.fallbackBody ||
          !status.fallbackChipText
        ) {
          if (serviceSignatureRef.current !== null) {
            serviceSignatureRef.current = null;
            await stopAndroidAgentActivityForegroundService();
          }
        } else {
          const notifications = status.notifications ?? [];
          const signature = JSON.stringify({
            fallbackTitle: status.fallbackTitle,
            fallbackBody: status.fallbackBody,
            fallbackChipText: status.fallbackChipText,
            notifications,
          });
          if (serviceSignatureRef.current === null) {
            await startAndroidAgentActivityForegroundService({
              fallbackTitle: status.fallbackTitle,
              fallbackBody: status.fallbackBody,
              fallbackChipText: status.fallbackChipText,
              notifications,
            });
          } else if (serviceSignatureRef.current !== signature) {
            await updateAndroidAgentActivityForegroundService({
              fallbackTitle: status.fallbackTitle,
              fallbackBody: status.fallbackBody,
              fallbackChipText: status.fallbackChipText,
              notifications,
            });
          }
          serviceSignatureRef.current = signature;
        }
      } catch (error) {
        logAndroidAgentActivityError("Could not synchronize Android foreground service.", error);
      }

      for (const alert of reconciliation.alerts) {
        try {
          await scheduleAndroidAgentActivityAlert(alert);
        } catch (error) {
          logAndroidAgentActivityError("Could not schedule Android agent activity alert.", error);
        }
      }
    })();
  }, [appState, enabled, notificationsGranted, nowMs, snapshot]);
}

function useAppStateStatus(): AppStateStatus {
  const [appState, setAppState] = useState(AppState.currentState);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", setAppState);
    return () => {
      subscription.remove();
    };
  }, []);

  return appState;
}

function useAndroidAgentActivityClock(active: boolean): number {
  const [nowMs, setNowMs] = useState(() => Date.now());

  useEffect(() => {
    if (!active) {
      return;
    }
    setNowMs(Date.now());
    const interval = setInterval(() => {
      setNowMs(Date.now());
    }, 20_000);
    return () => {
      clearInterval(interval);
    };
  }, [active]);

  return nowMs;
}

function androidAgentActivityThreadKey(state: AgentAwarenessState): string {
  return `${state.environmentId}:${state.threadId}`;
}

async function refreshAndroidNotificationPermission(
  setNotificationsGranted: (granted: boolean) => void,
): Promise<void> {
  try {
    const permissions = await Notifications.getPermissionsAsync();
    setNotificationsGranted(permissions.granted);
  } catch (error) {
    logAndroidAgentActivityError("Could not refresh Android notification permissions.", error);
    setNotificationsGranted(false);
  }
}

function logAndroidAgentActivityError(context: string, error: unknown): void {
  if (!__DEV__) {
    return;
  }
  console.warn(`[agent-awareness] ${context}`, error);
}
