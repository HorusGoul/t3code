#!/usr/bin/env node
// @effect-diagnostics nodeBuiltinImport:off

import * as NodeChildProcess from "node:child_process";
import * as NodeCrypto from "node:crypto";
import * as NodeFS from "node:fs";
import * as NodePath from "node:path";
import * as NodeURL from "node:url";

const repoRoot = NodePath.resolve(NodePath.dirname(NodeURL.fileURLToPath(import.meta.url)), "..");
const mobileRoot = NodePath.join(repoRoot, "apps/mobile");
const androidRoot = NodePath.join(mobileRoot, "android");
const releaseApkPath = NodePath.join(androidRoot, "app/build/outputs/apk/release/app-release.apk");
const destinationPath = resolveDestinationPath();
const releaseArchitectures = process.env.T3CODE_ANDROID_PREVIEW_RELEASE_ARCHITECTURES?.trim();

function resolveDestinationPath(): string {
  const explicitPath = process.env.T3CODE_ANDROID_PREVIEW_RELEASE_APK_PATH?.trim();
  return explicitPath
    ? NodePath.resolve(repoRoot, explicitPath)
    : NodePath.join(repoRoot, "dist/mobile/android/t3code-preview-release.apk");
}

function log(message: string): void {
  process.stdout.write(`${message}\n`);
}

function runCommand(
  command: string,
  args: ReadonlyArray<string>,
  options: {
    readonly cwd: string;
    readonly env?: NodeJS.ProcessEnv;
  },
): void {
  log(`$ ${[command, ...args].join(" ")}`);
  const result = NodeChildProcess.spawnSync(command, args, {
    cwd: options.cwd,
    env: {
      ...process.env,
      ...options.env,
    },
    shell: false,
    stdio: "inherit",
  });

  if (result.error) {
    throw result.error;
  }

  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

function copyApk(): void {
  if (!NodeFS.existsSync(releaseApkPath)) {
    throw new Error(`Expected release APK was not created: ${releaseApkPath}`);
  }

  NodeFS.mkdirSync(NodePath.dirname(destinationPath), { recursive: true });
  NodeFS.copyFileSync(releaseApkPath, destinationPath);

  const size = NodeFS.statSync(destinationPath).size;
  const hash = NodeCrypto.createHash("sha256")
    .update(NodeFS.readFileSync(destinationPath))
    .digest("hex");

  log(`Copied APK to ${destinationPath}`);
  log(`Size: ${(size / 1024 / 1024).toFixed(1)} MiB`);
  log(`SHA-256: ${hash}`);
}

runCommand(
  "vp",
  [
    "exec",
    "--filter",
    "@t3tools/mobile",
    "--",
    "expo",
    "prebuild",
    "--clean",
    "--no-install",
    "--platform",
    "android",
  ],
  {
    cwd: repoRoot,
    env: {
      APP_VARIANT: "preview",
      EXPO_NO_GIT_STATUS: "1",
      NODE_ENV: "production",
    },
  },
);

runCommand(
  "./gradlew",
  [
    "--no-daemon",
    "--stacktrace",
    "--build-cache",
    "-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m",
    ...(releaseArchitectures ? [`-PreactNativeArchitectures=${releaseArchitectures}`] : []),
    "assembleRelease",
  ],
  {
    cwd: androidRoot,
    env: {
      APP_VARIANT: "preview",
      NODE_ENV: "production",
    },
  },
);

copyApk();
