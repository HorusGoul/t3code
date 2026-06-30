import type {
  EnvironmentId,
  OrchestrationShellSnapshot,
  OrchestrationThread,
} from "@t3tools/contracts";
import {
  type AgentAwarenessPhase,
  type AgentAwarenessState,
  isTerminalAgentAwarenessPhase,
  projectThreadAwareness,
} from "@t3tools/shared/agentAwareness";
import { formatDuration } from "@t3tools/shared/orchestrationTiming";

import {
  buildThreadFeed,
  type ThreadFeedActivity,
  type ThreadFeedEntry,
} from "../../lib/threadActivity";

export const ANDROID_AGENT_ACTIVITY_TERMINAL_ALERT_RETENTION_MS = 10 * 60 * 1000;
const ANDROID_LIVE_UPDATE_CHIP_TEXT_MAX_LENGTH = 6;

export interface AndroidAgentActivityServiceStatus {
  readonly type: "running" | "stopped";
  readonly fallbackTitle?: string;
  readonly fallbackBody?: string;
  readonly fallbackChipText?: string;
  readonly notifications?: ReadonlyArray<AndroidAgentActivityServiceNotification>;
}

export interface AndroidAgentActivityServiceNotification {
  readonly key: string;
  readonly acknowledgementKey: string;
  readonly title: string;
  readonly body: string;
  readonly chipText: string;
  readonly phase: AgentAwarenessPhase;
  readonly deepLink: string;
  readonly subText: string;
  readonly ongoing: boolean;
  readonly updatedAt: string;
}

export interface AndroidAgentActivityObservedState {
  readonly phase: AgentAwarenessPhase;
  readonly updatedAt: string;
}

export interface AndroidAgentActivityAlert {
  readonly identifier: string;
  readonly title: string;
  readonly body: string;
  readonly data: {
    readonly deepLink: string;
    readonly environmentId: string;
    readonly threadId: string;
    readonly source: "android-local-agent-activity";
  };
}

export function agentActivityStatesFromShell(input: {
  readonly environmentId: EnvironmentId;
  readonly snapshot: OrchestrationShellSnapshot;
}): ReadonlyArray<AgentAwarenessState> {
  const projectsById = new Map(input.snapshot.projects.map((project) => [project.id, project]));
  const states: AgentAwarenessState[] = [];

  for (const thread of input.snapshot.threads) {
    if (thread.archivedAt !== null) {
      continue;
    }
    const project = projectsById.get(thread.projectId);
    if (!project) {
      continue;
    }
    const state = projectThreadAwareness({
      environmentId: input.environmentId,
      project,
      thread,
    });
    if (state) {
      states.push(state);
    }
  }

  return states.sort(compareAgentActivityStates);
}

export function buildAndroidAgentActivityServiceStatus(input: {
  readonly enabled: boolean;
  readonly environmentCount: number;
  readonly states: ReadonlyArray<AgentAwarenessState>;
  readonly threadDetails?: ReadonlyMap<string, OrchestrationThread>;
  readonly nowMs: number;
}): AndroidAgentActivityServiceStatus {
  if (!input.enabled || input.environmentCount === 0) {
    return { type: "stopped" };
  }

  const visibleStates = visibleAndroidAgentActivityStates({ states: input.states });
  if (visibleStates.length > 0) {
    return {
      type: "running",
      fallbackTitle: "T3 Code",
      fallbackBody:
        input.environmentCount === 1
          ? "Agent updates are connected to 1 environment."
          : `Agent updates are connected to ${input.environmentCount} environments.`,
      fallbackChipText: serviceAggregateChipTextForStates(visibleStates),
      notifications: visibleStates.map((state) =>
        notificationForState({
          state,
          threadDetail: input.threadDetails?.get(agentActivityStateKey(state)) ?? null,
          nowMs: input.nowMs,
        }),
      ),
    };
  }

  return {
    type: "running",
    fallbackTitle: "T3 Code",
    fallbackChipText: "T3Code",
    fallbackBody:
      input.environmentCount === 1
        ? "Agent updates are connected to 1 environment."
        : `Agent updates are connected to ${input.environmentCount} environments.`,
  };
}

function serviceAggregateChipTextForStates(states: ReadonlyArray<AgentAwarenessState>): string {
  const total = states.length;
  if (total === 0) {
    return "T3Code";
  }

  const completedCount = countStatesByPhase(states, "completed");
  if (completedCount > 0) {
    return total === 1 ? "Done" : aggregatePhaseChipText("D", completedCount, total);
  }

  const failedCount = countStatesByPhase(states, "failed");
  if (failedCount > 0) {
    return total === 1 ? "Failed" : aggregatePhaseChipText("F", failedCount, total);
  }

  const approvalCount = countStatesByPhase(states, "waiting_for_approval");
  if (approvalCount > 0) {
    return total === 1 ? "Review" : aggregatePhaseChipText("R", approvalCount, total);
  }

  const inputCount = countStatesByPhase(states, "waiting_for_input");
  if (inputCount > 0) {
    return total === 1 ? "Input" : aggregatePhaseChipText("I", inputCount, total);
  }

  const runningCount = countStatesByPhase(states, "running");
  if (runningCount > 0) {
    return total === 1 ? "Work" : aggregatePhaseChipText("W", runningCount, total);
  }

  const startingCount = countStatesByPhase(states, "starting");
  if (startingCount > 0) {
    return total === 1 ? "Start" : aggregatePhaseChipText("S", startingCount, total);
  }

  return "T3Code";
}

function aggregatePhaseChipText(prefix: string, count: number, total: number): string {
  const fractionText = `${prefix}${count}/${total}`;
  if (fractionText.length <= ANDROID_LIVE_UPDATE_CHIP_TEXT_MAX_LENGTH) {
    return fractionText;
  }
  return `${prefix}${compactChipCount(count)}`;
}

function compactChipCount(count: number): string {
  return count > 99 ? "99+" : String(count);
}

function countStatesByPhase(
  states: ReadonlyArray<AgentAwarenessState>,
  phase: AgentAwarenessPhase,
): number {
  return states.reduce((count, state) => (state.phase === phase ? count + 1 : count), 0);
}

export function reconcileAndroidAgentActivityAlerts(input: {
  readonly previous: ReadonlyMap<string, AndroidAgentActivityObservedState>;
  readonly states: ReadonlyArray<AgentAwarenessState>;
  readonly canNotify: boolean;
  readonly nowMs: number;
}): {
  readonly next: ReadonlyMap<string, AndroidAgentActivityObservedState>;
  readonly alerts: ReadonlyArray<AndroidAgentActivityAlert>;
} {
  const next = new Map<string, AndroidAgentActivityObservedState>();
  const alerts: AndroidAgentActivityAlert[] = [];

  for (const state of input.states) {
    const key = agentActivityStateKey(state);
    const observedState: AndroidAgentActivityObservedState = {
      phase: state.phase,
      updatedAt: state.updatedAt,
    };
    next.set(key, observedState);

    if (
      shouldEmitAndroidAgentActivityAlert({
        previous: input.previous.get(key) ?? null,
        state,
        canNotify: input.canNotify,
        nowMs: input.nowMs,
      })
    ) {
      alerts.push(alertForState(state));
    }
  }

  return { next, alerts };
}

export function visibleAndroidAgentActivityStates(input: {
  readonly states: ReadonlyArray<AgentAwarenessState>;
}): ReadonlyArray<AgentAwarenessState> {
  return input.states;
}

function shouldEmitAndroidAgentActivityAlert(input: {
  readonly previous: AndroidAgentActivityObservedState | null;
  readonly state: AgentAwarenessState;
  readonly canNotify: boolean;
  readonly nowMs: number;
}): boolean {
  if (!input.canNotify) {
    return false;
  }

  const previous = input.previous;
  if (previous?.phase === input.state.phase && previous.updatedAt === input.state.updatedAt) {
    return false;
  }

  if (input.state.phase === "waiting_for_approval" || input.state.phase === "waiting_for_input") {
    return previous?.phase !== input.state.phase;
  }

  if (input.state.phase === "failed" || input.state.phase === "completed") {
    if (!previous || isTerminalAgentAwarenessPhase(previous.phase)) {
      return false;
    }
    const updatedAtMs = timestampMs(input.state.updatedAt);
    return (
      updatedAtMs !== null &&
      input.nowMs - updatedAtMs <= ANDROID_AGENT_ACTIVITY_TERMINAL_ALERT_RETENTION_MS
    );
  }

  return false;
}

function alertForState(state: AgentAwarenessState): AndroidAgentActivityAlert {
  return {
    identifier: `android-agent-activity:${agentActivityStateKey(state)}:${state.phase}:${state.updatedAt}`,
    title: state.headline,
    body: alertBodyForState(state),
    data: {
      deepLink: state.deepLink,
      environmentId: state.environmentId,
      threadId: state.threadId,
      source: "android-local-agent-activity",
    },
  };
}

function alertBodyForState(state: AgentAwarenessState): string {
  if (state.detail) {
    return `${state.threadTitle}: ${state.detail}`;
  }
  return `${state.threadTitle} - ${state.projectTitle}`;
}

function notificationForState(input: {
  readonly state: AgentAwarenessState;
  readonly threadDetail: OrchestrationThread | null;
  readonly nowMs: number;
}): AndroidAgentActivityServiceNotification {
  const { state } = input;
  return {
    key: agentActivityStateKey(state),
    acknowledgementKey: agentActivityStateAcknowledgementKey(state),
    title: state.threadTitle,
    body: notificationBodyForState(input),
    chipText: serviceChipTextForState(state),
    phase: state.phase,
    deepLink: state.deepLink,
    subText: state.projectTitle,
    ongoing: !isTerminalAgentAwarenessPhase(state.phase),
    updatedAt: state.updatedAt,
  };
}

function notificationBodyForState(input: {
  readonly state: AgentAwarenessState;
  readonly threadDetail: OrchestrationThread | null;
  readonly nowMs: number;
}): string {
  const summary = notificationSummaryForState(input.state, input.nowMs);
  const detail = deriveAndroidAgentActivityDetailHint(input.threadDetail);
  if (detail) {
    return `${summary} - ${detail}`;
  }
  if (
    input.state.detail &&
    (input.state.phase === "completed" ||
      input.state.phase === "failed" ||
      input.state.phase === "waiting_for_approval" ||
      input.state.phase === "waiting_for_input")
  ) {
    return `${summary} - ${input.state.detail}`;
  }
  return summary;
}

function notificationSummaryForState(state: AgentAwarenessState, nowMs: number): string {
  const elapsed = workElapsedForState(state, nowMs);
  switch (state.phase) {
    case "waiting_for_approval":
      return elapsed ? `Waiting for approval for ${elapsed}` : "Waiting for approval";
    case "waiting_for_input":
      return elapsed ? `Waiting for input for ${elapsed}` : "Waiting for input";
    case "running":
      return elapsed ? `Working for ${elapsed}` : "Working";
    case "starting":
      return elapsed ? `Starting for ${elapsed}` : "Starting";
    case "failed":
      return elapsed ? `Failed after ${elapsed}` : "Failed";
    case "completed":
      return elapsed ? `Completed in ${elapsed}` : "Completed";
    case "stale":
      return elapsed ? `Update delayed for ${elapsed}` : "Update delayed";
  }
}

function workElapsedForState(state: AgentAwarenessState, nowMs: number): string | null {
  if (!state.workStartedAt) {
    return null;
  }
  const startedAtMs = timestampMs(state.workStartedAt);
  if (startedAtMs === null) {
    return null;
  }
  const endedAtMs = state.workEndedAt ? timestampMs(state.workEndedAt) : nowMs;
  if (endedAtMs === null || endedAtMs < startedAtMs) {
    return null;
  }
  return formatDuration(endedAtMs - startedAtMs);
}

function deriveAndroidAgentActivityDetailHint(
  threadDetail: OrchestrationThread | null,
): string | null {
  if (!threadDetail) {
    return null;
  }

  const latestTurnId = threadDetail.latestTurn?.turnId ?? null;
  const feed = buildThreadFeed(threadDetail);
  for (const entry of feed.toReversed()) {
    const hint = hintForThreadFeedEntry(entry, latestTurnId);
    if (hint) {
      return hint;
    }
  }

  return null;
}

function hintForThreadFeedEntry(
  entry: ThreadFeedEntry,
  latestTurnId: string | null,
): string | null {
  if (entry.type === "activity-group") {
    for (const activity of entry.activities.toReversed()) {
      if (latestTurnId !== null && activity.turnId !== latestTurnId) {
        continue;
      }
      const hint = hintForThreadFeedActivity(activity);
      if (hint) {
        return hint;
      }
    }
    return null;
  }

  if (entry.type !== "message") {
    return null;
  }
  if (entry.message.role !== "assistant") {
    return null;
  }
  if (latestTurnId !== null && entry.message.turnId !== latestTurnId) {
    return null;
  }
  return compactNotificationText(entry.message.text);
}

function hintForThreadFeedActivity(activity: ThreadFeedActivity): string | null {
  const summary = compactNotificationText(activity.summary);
  const detail = compactNotificationText(activity.detail ?? undefined);
  if (!summary) {
    return detail;
  }
  if (!detail || summary.toLowerCase() === detail.toLowerCase()) {
    return summary;
  }
  return compactNotificationText(`${summary}: ${detail}`);
}

function compactNotificationText(value: string | null | undefined): string | null {
  const trimmed = value
    ?.replace(/[`*_>#]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  if (!trimmed) {
    return null;
  }
  return trimmed.length > 96 ? `${trimmed.slice(0, 95).trimEnd()}...` : trimmed;
}

function serviceChipTextForState(state: AgentAwarenessState): string {
  switch (state.phase) {
    case "waiting_for_approval":
      return "Review";
    case "waiting_for_input":
      return "Input";
    case "running":
      return "Work";
    case "starting":
      return "Start";
    case "failed":
      return "Failed";
    case "completed":
      return "Done";
    case "stale":
      return "Stale";
  }
}

function compareAgentActivityStates(left: AgentAwarenessState, right: AgentAwarenessState): number {
  const phaseDelta = phasePriority(left.phase) - phasePriority(right.phase);
  if (phaseDelta !== 0) {
    return phaseDelta;
  }
  const updatedAtDelta = (timestampMs(right.updatedAt) ?? 0) - (timestampMs(left.updatedAt) ?? 0);
  if (updatedAtDelta !== 0) {
    return updatedAtDelta;
  }
  return left.threadTitle.localeCompare(right.threadTitle);
}

function phasePriority(phase: AgentAwarenessPhase): number {
  switch (phase) {
    case "waiting_for_approval":
      return 0;
    case "waiting_for_input":
      return 1;
    case "failed":
      return 2;
    case "running":
      return 3;
    case "starting":
      return 4;
    case "completed":
      return 5;
    case "stale":
      return 6;
  }
}

function agentActivityStateKey(state: AgentAwarenessState): string {
  return `${state.environmentId}:${state.threadId}`;
}

function agentActivityStateAcknowledgementKey(state: AgentAwarenessState): string {
  return `${agentActivityStateKey(state)}:${state.phase}:${state.updatedAt}`;
}

function timestampMs(value: string): number | null {
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}
