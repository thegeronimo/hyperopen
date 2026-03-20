import net from "node:net";
import { getBrowserWsUrl } from "./cdp_client.mjs";
import { classifyErrorMessage, remediationForClassification } from "./failure_classification.mjs";
import { safeNowIso } from "./util.mjs";

function toCheck({ id, ok, required = true, message, error = null, details = null }) {
  return {
    id,
    ok,
    required,
    message,
    errorMessage: error ? String(error?.message || error) : null,
    details: details || null
  };
}

function probeLoopbackBind(host = "127.0.0.1") {
  return new Promise((resolve) => {
    const server = net.createServer();
    let finished = false;

    const finalize = (result) => {
      if (finished) {
        return;
      }
      finished = true;
      resolve(result);
    };

    server.once("error", (error) => {
      finalize(toCheck({
        id: "loopback-bind",
        ok: false,
        required: true,
        message: `Unable to bind a probe socket on ${host}.`,
        error
      }));
    });

    server.listen({ host, port: 0, exclusive: true }, () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : null;
      server.close(() => {
        finalize(toCheck({
          id: "loopback-bind",
          ok: true,
          required: true,
          message: `Loopback bind probe succeeded on ${host}.`,
          details: { host, port }
        }));
      });
    });
  });
}

async function probeUrl(url, timeoutMs = 2500) {
  const timeout = AbortSignal.timeout(timeoutMs);
  try {
    const response = await fetch(url, { signal: timeout, redirect: "follow" });
    return toCheck({
      id: "local-url-reachable",
      ok: response.status >= 200 && response.status < 300,
      required: false,
      message: `Local URL probe returned HTTP ${response.status}.`,
      details: { url, status: response.status }
    });
  } catch (error) {
    return toCheck({
      id: "local-url-reachable",
      ok: false,
      required: false,
      message: `Local URL probe failed for ${url}.`,
      error,
      details: { url }
    });
  }
}

async function probeAttachEndpoint(host, port, timeoutMs = 2500) {
  try {
    const wsUrl = await getBrowserWsUrl({ host, port, timeoutMs, pollIntervalMs: 150 });
    return toCheck({
      id: "attach-endpoint",
      ok: true,
      required: true,
      message: `Attach endpoint is reachable at ${host}:${port}.`,
      details: { host, port, wsUrl }
    });
  } catch (error) {
    return toCheck({
      id: "attach-endpoint",
      ok: false,
      required: true,
      message: `Attach endpoint is not reachable at ${host}:${port}.`,
      error,
      details: { host, port }
    });
  }
}

export async function runPreflightChecks(config, options = {}) {
  const attachPort =
    options.attachPort === undefined || options.attachPort === null || options.attachPort === ""
      ? null
      : Number(options.attachPort);
  const attachHost = options.attachHost || "127.0.0.1";
  const mode = attachPort ? "attach" : "local";

  const checks = [];
  const loopbackCheck = await probeLoopbackBind(options.probeHost || "127.0.0.1");
  if (mode === "attach") {
    loopbackCheck.required = false;
    loopbackCheck.message = `${loopbackCheck.message} (informational in attach mode)`;
  }
  checks.push(loopbackCheck);

  if (mode === "local") {
    const localUrl = options.localUrl || config?.localApp?.url || "http://localhost:8080/trade";
    checks.push(await probeUrl(localUrl, options.urlTimeoutMs || 2500));
  } else {
    checks.push(await probeAttachEndpoint(attachHost, attachPort, options.attachTimeoutMs || 2500));
  }

  const failedRequired = checks.filter((check) => check.required && !check.ok);
  const ok = failedRequired.length === 0;

  const classifications = failedRequired
    .map((check) => classifyErrorMessage(check.errorMessage || check.message))
    .filter(Boolean);

  const primaryClassification = classifications[0] || null;
  const remediation = primaryClassification ? remediationForClassification(primaryClassification) : null;

  return {
    generatedAt: safeNowIso(),
    mode,
    ok,
    checks,
    summary: {
      failedRequiredCheckIds: failedRequired.map((check) => check.id),
      failedOptionalCheckIds: checks.filter((check) => !check.required && !check.ok).map((check) => check.id)
    },
    classification: primaryClassification,
    remediation
  };
}
