function fail(message) {
  const error = new Error(`ScenarioContractError: ${message}`);
  error.name = "ScenarioContractError";
  throw error;
}

function assertString(value, key) {
  if (typeof value !== "string" || value.trim() === "") {
    fail(`${key} must be a non-empty string`);
  }
}

function assertArray(value, key) {
  if (!Array.isArray(value)) {
    fail(`${key} must be an array`);
  }
}

function assertObject(value, key) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    fail(`${key} must be an object`);
  }
}

function assertOptionalString(value, key) {
  if (value === undefined || value === null) {
    return;
  }
  assertString(value, key);
}

function assertStep(step, index) {
  assertObject(step, `scenario.steps[${index}]`);
  assertString(step.type, `scenario.steps[${index}].type`);
  if (step.saveAs !== undefined) {
    assertString(step.saveAs, `scenario.steps[${index}].saveAs`);
  }

  switch (step.type) {
    case "navigate":
      assertOptionalString(step.url, `scenario.steps[${index}].url`);
      break;
    case "dispatch":
      assertArray(step.action, `scenario.steps[${index}].action`);
      break;
    case "dispatch_many":
      assertArray(step.actions, `scenario.steps[${index}].actions`);
      break;
    case "wait_for_idle":
      break;
    case "debug_call":
      assertString(step.method, `scenario.steps[${index}].method`);
      if (step.args !== undefined) {
        assertArray(step.args, `scenario.steps[${index}].args`);
      }
      break;
    case "eval":
      assertString(step.expression, `scenario.steps[${index}].expression`);
      break;
    case "wait_for_eval":
      assertString(step.expression, `scenario.steps[${index}].expression`);
      break;
    case "oracle":
      assertString(step.name, `scenario.steps[${index}].name`);
      break;
    case "wait_for_oracle":
      assertString(step.name, `scenario.steps[${index}].name`);
      break;
    case "capture":
      break;
    case "compare":
      assertOptionalString(step.leftUrl, `scenario.steps[${index}].leftUrl`);
      assertOptionalString(step.rightUrl, `scenario.steps[${index}].rightUrl`);
      break;
    case "sleep":
      break;
    default:
      fail(`unsupported scenario step type: ${step.type}`);
  }
}

export function assertScenarioManifest(value) {
  assertObject(value, "scenario");
  assertString(value.id, "scenario.id");
  assertString(value.title, "scenario.title");
  assertArray(value.tags, "scenario.tags");
  if (value.tags.length === 0) {
    fail("scenario.tags must not be empty");
  }
  value.tags.forEach((tag, index) => assertString(tag, `scenario.tags[${index}]`));
  assertArray(value.viewports, "scenario.viewports");
  if (value.viewports.length === 0) {
    fail("scenario.viewports must not be empty");
  }
  value.viewports.forEach((viewport, index) =>
    assertString(viewport, `scenario.viewports[${index}]`)
  );
  assertString(value.url, "scenario.url");
  assertArray(value.steps, "scenario.steps");
  if (value.steps.length === 0) {
    fail("scenario.steps must not be empty");
  }
  value.steps.forEach(assertStep);
  if (value.route !== undefined) {
    assertString(value.route, "scenario.route");
  }
  if (value.severity !== undefined) {
    assertString(value.severity, "scenario.severity");
  }
}

export function assertScenarioRouting(value) {
  assertObject(value, "scenarioRouting");
  assertArray(value.defaultTags || [], "scenarioRouting.defaultTags");
  assertArray(value.fullCriticalGlobs || [], "scenarioRouting.fullCriticalGlobs");
  assertArray(value.tagRules || [], "scenarioRouting.tagRules");
  for (const [index, rule] of (value.tagRules || []).entries()) {
    assertObject(rule, `scenarioRouting.tagRules[${index}]`);
    assertString(rule.glob, `scenarioRouting.tagRules[${index}].glob`);
    assertArray(rule.tags, `scenarioRouting.tagRules[${index}].tags`);
    if (rule.tags.length === 0) {
      fail(`scenarioRouting.tagRules[${index}].tags must not be empty`);
    }
  }
}

export function isScenarioContractError(error) {
  return error?.name === "ScenarioContractError";
}
