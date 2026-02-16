function fail(message) {
  const error = new Error(`ContractError: ${message}`);
  error.name = "ContractError";
  throw error;
}

function assertString(value, key) {
  if (typeof value !== "string" || value.trim() === "") {
    fail(`${key} must be a non-empty string`);
  }
}

function assertNumber(value, key) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    fail(`${key} must be a number`);
  }
}

function assertBoolean(value, key) {
  if (typeof value !== "boolean") {
    fail(`${key} must be a boolean`);
  }
}

function assertObject(value, key) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    fail(`${key} must be an object`);
  }
}

function assertOptionalString(value, key) {
  if (value === null || value === undefined) {
    return;
  }
  assertString(value, key);
}

function assertOptionalNumber(value, key) {
  if (value === null || value === undefined) {
    return;
  }
  assertNumber(value, key);
}

function assertOptionalBoolean(value, key) {
  if (value === null || value === undefined) {
    return;
  }
  assertBoolean(value, key);
}

export function assertConfig(config) {
  assertObject(config, "config");
  assertString(config.artifactRoot, "config.artifactRoot");
  assertNumber(config.retentionHours, "config.retentionHours");
  assertObject(config.chrome, "config.chrome");
  assertString(config.chrome.path, "config.chrome.path");
  assertBoolean(config.chrome.headless, "config.chrome.headless");
  assertObject(config.targets, "config.targets");
  assertObject(config.viewports, "config.viewports");
  for (const [name, vp] of Object.entries(config.viewports)) {
    assertObject(vp, `config.viewports.${name}`);
    assertNumber(vp.width, `config.viewports.${name}.width`);
    assertNumber(vp.height, `config.viewports.${name}.height`);
  }
}

export function assertSessionState(value) {
  assertObject(value, "sessionState");
  assertString(value.id, "sessionState.id");
  assertString(value.createdAt, "sessionState.createdAt");
  assertObject(value.chrome, "sessionState.chrome");
  assertOptionalNumber(value.chrome.pid, "sessionState.chrome.pid");
  assertNumber(value.chrome.port, "sessionState.chrome.port");
  assertString(value.chrome.host || "127.0.0.1", "sessionState.chrome.host");
  assertOptionalString(value.chrome.path, "sessionState.chrome.path");
  assertOptionalBoolean(value.chrome.headless, "sessionState.chrome.headless");
  assertOptionalString(value.chrome.userDataDir, "sessionState.chrome.userDataDir");
  assertBoolean(value.chrome.ephemeralProfile, "sessionState.chrome.ephemeralProfile");
  assertString(value.chrome.controlMode || "launched", "sessionState.chrome.controlMode");
  assertString(value.targetId, "sessionState.targetId");
  assertBoolean(value.readOnly, "sessionState.readOnly");
}

export function assertSnapshotPayload(value) {
  assertObject(value, "snapshotPayload");
  assertString(value.sessionId, "snapshotPayload.sessionId");
  assertString(value.url, "snapshotPayload.url");
  assertString(value.viewport, "snapshotPayload.viewport");
  assertObject(value.page, "snapshotPayload.page");
  assertObject(value.semantic, "snapshotPayload.semantic");
  if (!Array.isArray(value.console)) {
    fail("snapshotPayload.console must be an array");
  }
  if (!Array.isArray(value.network)) {
    fail("snapshotPayload.network must be an array");
  }
}

export function assertCompareResult(value) {
  assertObject(value, "compareResult");
  assertObject(value.summary, "compareResult.summary");
  assertNumber(value.summary.semanticFindingCount, "compareResult.summary.semanticFindingCount");
  assertNumber(value.summary.visualDiffRatio, "compareResult.summary.visualDiffRatio");
  if (!Array.isArray(value.findings)) {
    fail("compareResult.findings must be an array");
  }
}

export function assertArtifactManifest(value) {
  assertObject(value, "artifactManifest");
  assertString(value.runId, "artifactManifest.runId");
  assertString(value.createdAt, "artifactManifest.createdAt");
  assertString(value.kind, "artifactManifest.kind");
  if (!Array.isArray(value.artifacts)) {
    fail("artifactManifest.artifacts must be an array");
  }
}

export function isContractError(error) {
  return error?.name === "ContractError";
}
