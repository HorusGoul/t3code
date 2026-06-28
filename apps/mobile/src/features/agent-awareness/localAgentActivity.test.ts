import { describe, expect, it } from "@effect/vitest";
import type {
  EnvironmentId,
  OrchestrationThread,
  OrchestrationProjectShell,
  OrchestrationShellSnapshot,
  OrchestrationThreadShell,
  ProjectId,
  ThreadId,
  TurnId,
} from "@t3tools/contracts";
import { EventId, ProviderInstanceId } from "@t3tools/contracts";

import {
  agentActivityStatesFromShell,
  buildAndroidAgentActivityServiceStatus,
  reconcileAndroidAgentActivityAlerts,
} from "./localAgentActivity";

const NOW = "2026-06-28T10:00:00.000Z";
const NOW_MS = Date.parse(NOW);
const ENVIRONMENT_ID = "env-1" as EnvironmentId;
const PROJECT_ID = "project-1" as ProjectId;
const THREAD_ID = "thread-1" as ThreadId;

function project(overrides: Partial<OrchestrationProjectShell> = {}): OrchestrationProjectShell {
  return {
    id: PROJECT_ID,
    title: "t3code",
    workspaceRoot: "/repo/t3code",
    repositoryIdentity: null,
    defaultModelSelection: null,
    scripts: [],
    createdAt: NOW,
    updatedAt: NOW,
    ...overrides,
  };
}

function thread(overrides: Partial<OrchestrationThreadShell> = {}): OrchestrationThreadShell {
  return {
    id: THREAD_ID,
    projectId: PROJECT_ID,
    title: "Fix Android notifications",
    modelSelection: { instanceId: ProviderInstanceId.make("codex"), model: "gpt-5" },
    runtimeMode: "full-access",
    interactionMode: "default",
    branch: null,
    worktreePath: null,
    latestTurn: null,
    createdAt: NOW,
    updatedAt: NOW,
    archivedAt: null,
    session: null,
    latestUserMessageAt: NOW,
    hasPendingApprovals: false,
    hasPendingUserInput: false,
    hasActionableProposedPlan: false,
    ...overrides,
  };
}

function snapshot(threads: ReadonlyArray<OrchestrationThreadShell>): OrchestrationShellSnapshot {
  return {
    snapshotSequence: 1,
    projects: [project()],
    threads,
    updatedAt: NOW,
  };
}

function threadDetail(overrides: Partial<OrchestrationThread> = {}): OrchestrationThread {
  return {
    ...thread(),
    deletedAt: null,
    messages: [],
    proposedPlans: [],
    activities: [],
    checkpoints: [],
    ...overrides,
  };
}

describe("Android local agent activity", () => {
  it("projects agent states from the existing shell snapshot", () => {
    const states = agentActivityStatesFromShell({
      environmentId: ENVIRONMENT_ID,
      snapshot: snapshot([
        thread({
          session: {
            threadId: THREAD_ID,
            status: "running",
            providerName: "Codex",
            runtimeMode: "full-access",
            activeTurnId: "turn-1" as TurnId,
            lastError: null,
            updatedAt: NOW,
          },
        }),
      ]),
    });

    expect(states).toHaveLength(1);
    expect(states[0]).toMatchObject({
      environmentId: ENVIRONMENT_ID,
      threadId: THREAD_ID,
      phase: "running",
      headline: "Agent is working",
      deepLink: "/threads/env-1/thread-1",
    });
  });

  it("keeps the foreground service connected when updates are enabled", () => {
    const status = buildAndroidAgentActivityServiceStatus({
      enabled: true,
      environmentCount: 2,
      states: [],
      nowMs: NOW_MS,
    });

    expect(status).toEqual({
      type: "running",
      fallbackTitle: "T3 Code",
      fallbackChipText: "T3Code",
      fallbackBody: "Agent updates are connected to 2 environments.",
    });
  });

  it("builds a thread notification with compact chip text for a single active agent", () => {
    const startedAt = "2026-06-28T09:54:20.000Z";
    const [running] = agentActivityStatesFromShell({
      environmentId: ENVIRONMENT_ID,
      snapshot: snapshot([
        thread({
          latestTurn: {
            turnId: "turn-1" as TurnId,
            state: "running",
            requestedAt: startedAt,
            startedAt,
            completedAt: null,
            assistantMessageId: null,
          },
          session: {
            threadId: THREAD_ID,
            status: "running",
            providerName: "Codex",
            runtimeMode: "full-access",
            activeTurnId: "turn-1" as TurnId,
            lastError: null,
            updatedAt: NOW,
          },
        }),
      ]),
    });

    const status = buildAndroidAgentActivityServiceStatus({
      enabled: true,
      environmentCount: 1,
      states: running ? [running] : [],
      threadDetails: new Map(
        running
          ? [
              [
                "env-1:thread-1",
                threadDetail({
                  latestTurn: {
                    turnId: "turn-1" as TurnId,
                    state: "running",
                    requestedAt: startedAt,
                    startedAt,
                    completedAt: null,
                    assistantMessageId: null,
                  },
                  activities: [
                    {
                      id: EventId.make("tool-1"),
                      kind: "tool.updated",
                      tone: "tool",
                      summary: "Run tests",
                      payload: {
                        title: "Run tests",
                        itemType: "command_execution",
                        detail: "/bin/zsh -lc 'vp test'",
                      },
                      turnId: "turn-1" as TurnId,
                      sequence: 1,
                      createdAt: "2026-06-28T09:55:00.000Z",
                    },
                  ],
                }),
              ],
            ]
          : [],
      ),
      nowMs: NOW_MS,
    });

    expect(status).toMatchObject({
      type: "running",
      fallbackChipText: "Work",
      notifications: [
        {
          key: "env-1:thread-1",
          title: "Fix Android notifications",
          body: "Working for 5m 40s - Run tests: vp test",
          chipText: "Work",
          phase: "running",
          deepLink: "/threads/env-1/thread-1",
          ongoing: true,
        },
      ],
    });
  });

  it("builds one compact notification for each active thread", () => {
    const secondThreadId = "thread-2" as ThreadId;
    const states = agentActivityStatesFromShell({
      environmentId: ENVIRONMENT_ID,
      snapshot: snapshot([
        thread({
          session: {
            threadId: THREAD_ID,
            status: "running",
            providerName: "Codex",
            runtimeMode: "full-access",
            activeTurnId: "turn-1" as TurnId,
            lastError: null,
            updatedAt: NOW,
          },
        }),
        thread({
          id: secondThreadId,
          title: "Ship live update chip",
          session: {
            threadId: secondThreadId,
            status: "running",
            providerName: "Codex",
            runtimeMode: "full-access",
            activeTurnId: "turn-2" as TurnId,
            lastError: null,
            updatedAt: NOW,
          },
        }),
      ]),
    });

    const status = buildAndroidAgentActivityServiceStatus({
      enabled: true,
      environmentCount: 1,
      states,
      nowMs: NOW_MS,
    });

    expect(status).toMatchObject({
      type: "running",
      fallbackChipText: "W2/2",
      notifications: [
        {
          key: "env-1:thread-1",
          title: "Fix Android notifications",
          chipText: "Work",
          ongoing: true,
        },
        {
          key: "env-1:thread-2",
          title: "Ship live update chip",
          chipText: "Work",
          ongoing: true,
        },
      ],
    });
  });

  it("prioritizes completed work in the aggregate live update chip", () => {
    const states = agentActivityStatesFromShell({
      environmentId: ENVIRONMENT_ID,
      snapshot: snapshot([
        thread({
          latestTurn: {
            turnId: "turn-completed" as TurnId,
            state: "completed",
            requestedAt: NOW,
            startedAt: NOW,
            completedAt: NOW,
            assistantMessageId: null,
          },
        }),
        thread({
          id: "thread-2" as ThreadId,
          title: "Running one",
          session: {
            threadId: "thread-2" as ThreadId,
            status: "running",
            providerName: "Codex",
            runtimeMode: "full-access",
            activeTurnId: "turn-2" as TurnId,
            lastError: null,
            updatedAt: NOW,
          },
        }),
        thread({
          id: "thread-3" as ThreadId,
          title: "Running two",
          session: {
            threadId: "thread-3" as ThreadId,
            status: "running",
            providerName: "Codex",
            runtimeMode: "full-access",
            activeTurnId: "turn-3" as TurnId,
            lastError: null,
            updatedAt: NOW,
          },
        }),
        thread({
          id: "thread-4" as ThreadId,
          title: "Running three",
          session: {
            threadId: "thread-4" as ThreadId,
            status: "running",
            providerName: "Codex",
            runtimeMode: "full-access",
            activeTurnId: "turn-4" as TurnId,
            lastError: null,
            updatedAt: NOW,
          },
        }),
      ]),
    });

    const status = buildAndroidAgentActivityServiceStatus({
      enabled: true,
      environmentCount: 1,
      states,
      nowMs: NOW_MS,
    });

    expect(status).toMatchObject({
      type: "running",
      fallbackChipText: "D1/4",
    });
  });

  it("keeps generated live update chip labels shorter than 7 characters", () => {
    const states = agentActivityStatesFromShell({
      environmentId: ENVIRONMENT_ID,
      snapshot: snapshot([
        thread({
          latestTurn: {
            turnId: "turn-completed" as TurnId,
            state: "completed",
            requestedAt: NOW,
            startedAt: NOW,
            completedAt: NOW,
            assistantMessageId: null,
          },
        }),
        thread({
          id: "thread-2" as ThreadId,
          title: "Running one",
          session: {
            threadId: "thread-2" as ThreadId,
            status: "running",
            providerName: "Codex",
            runtimeMode: "full-access",
            activeTurnId: "turn-2" as TurnId,
            lastError: null,
            updatedAt: NOW,
          },
        }),
      ]),
    });

    const status = buildAndroidAgentActivityServiceStatus({
      enabled: true,
      environmentCount: 1,
      states,
      nowMs: NOW_MS,
    });

    expect(status.fallbackChipText).toHaveLength(4);
    expect(status.fallbackChipText?.length).toBeLessThan(7);
    for (const notification of status.notifications ?? []) {
      expect(notification.chipText.length).toBeLessThan(7);
    }
  });

  it("keeps recently completed threads as clearable status notifications", () => {
    const [completed] = agentActivityStatesFromShell({
      environmentId: ENVIRONMENT_ID,
      snapshot: snapshot([
        thread({
          latestTurn: {
            turnId: "turn-1" as TurnId,
            state: "completed",
            requestedAt: NOW,
            startedAt: NOW,
            completedAt: NOW,
            assistantMessageId: null,
          },
        }),
      ]),
    });

    const status = buildAndroidAgentActivityServiceStatus({
      enabled: true,
      environmentCount: 1,
      states: completed ? [completed] : [],
      nowMs: NOW_MS,
    });

    expect(status).toMatchObject({
      type: "running",
      fallbackChipText: "Done",
      notifications: [
        {
          key: "env-1:thread-1",
          acknowledgementKey: `env-1:thread-1:completed:${NOW}`,
          title: "Fix Android notifications",
          body: "Completed in 1ms - Review the completed task.",
          chipText: "Done",
          phase: "completed",
          ongoing: false,
          updatedAt: NOW,
        },
      ],
    });
  });

  it("keeps stale completed threads visible until native click or dismissal acknowledgement", () => {
    const completedAt = "2026-06-28T09:45:00.000Z";
    const [completed] = agentActivityStatesFromShell({
      environmentId: ENVIRONMENT_ID,
      snapshot: snapshot([
        thread({
          updatedAt: completedAt,
          latestTurn: {
            turnId: "turn-1" as TurnId,
            state: "completed",
            requestedAt: completedAt,
            startedAt: completedAt,
            completedAt,
            assistantMessageId: null,
          },
        }),
      ]),
    });

    const status = buildAndroidAgentActivityServiceStatus({
      enabled: true,
      environmentCount: 1,
      states: completed ? [completed] : [],
      nowMs: NOW_MS,
    });

    expect(status).toMatchObject({
      type: "running",
      notifications: [
        {
          key: "env-1:thread-1",
          acknowledgementKey: `env-1:thread-1:completed:${completedAt}`,
          chipText: "Done",
          ongoing: false,
          updatedAt: completedAt,
        },
      ],
    });
  });

  it("emits background alerts when a running thread completes", () => {
    const [running] = agentActivityStatesFromShell({
      environmentId: ENVIRONMENT_ID,
      snapshot: snapshot([
        thread({
          session: {
            threadId: THREAD_ID,
            status: "running",
            providerName: "Codex",
            runtimeMode: "full-access",
            activeTurnId: "turn-1" as TurnId,
            lastError: null,
            updatedAt: NOW,
          },
        }),
      ]),
    });
    const seeded = reconcileAndroidAgentActivityAlerts({
      previous: new Map(),
      states: running ? [running] : [],
      canNotify: false,
      nowMs: NOW_MS,
    });
    const [completed] = agentActivityStatesFromShell({
      environmentId: ENVIRONMENT_ID,
      snapshot: snapshot([
        thread({
          latestTurn: {
            turnId: "turn-1" as TurnId,
            state: "completed",
            requestedAt: NOW,
            startedAt: NOW,
            completedAt: NOW,
            assistantMessageId: null,
          },
        }),
      ]),
    });

    const next = reconcileAndroidAgentActivityAlerts({
      previous: seeded.next,
      states: completed ? [completed] : [],
      canNotify: true,
      nowMs: NOW_MS,
    });

    expect(next.alerts).toHaveLength(1);
    expect(next.alerts[0]).toMatchObject({
      title: "Agent finished",
      data: {
        deepLink: "/threads/env-1/thread-1",
        source: "android-local-agent-activity",
      },
    });
  });

  it("does not emit stale completed alerts on first observation", () => {
    const [completed] = agentActivityStatesFromShell({
      environmentId: ENVIRONMENT_ID,
      snapshot: snapshot([
        thread({
          latestTurn: {
            turnId: "turn-1" as TurnId,
            state: "completed",
            requestedAt: NOW,
            startedAt: NOW,
            completedAt: NOW,
            assistantMessageId: null,
          },
        }),
      ]),
    });

    const result = reconcileAndroidAgentActivityAlerts({
      previous: new Map(),
      states: completed ? [completed] : [],
      canNotify: true,
      nowMs: NOW_MS,
    });

    expect(result.alerts).toEqual([]);
  });
});
