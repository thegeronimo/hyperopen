import {
  createHyperEvmRpcState,
  emptyHyperEvmFixture,
  fulfillHyperEvmRoute,
  hyperEvmRpcController,
  isHyperEvmRpcUrl,
  rpcEntries,
  wasHyperEvmRequestMocked
} from "./hyperevm_fixtures.mjs";

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function describeRequest(request) {
  return `${request.method()} ${request.url()}`;
}

async function settled(request, ms) {
  await Promise.race([
    Promise.resolve()
      .then(() => request.response())
      .catch(() => null),
    sleep(ms)
  ]);
}

export function createHyperEvmRpcGuard({ settleMs = 1_500 } = {}) {
  const protectedContexts = new WeakSet();
  const contextStates = [];
  const pages = [];
  const observed = [];
  const acknowledged = new WeakSet();

  const guard = {
    async protectContext(context, fixture = emptyHyperEvmFixture()) {
      if (protectedContexts.has(context)) return guard.stateFor(context);
      protectedContexts.add(context);
      const state = createHyperEvmRpcState(fixture);
      contextStates.push({ context, state });
      context.on("request", (request) => {
        if (isHyperEvmRpcUrl(request.url())) observed.push(request);
      });
      context.on("page", (page) => pages.push(page));
      for (const page of context.pages()) pages.push(page);
      await context.route(isHyperEvmRpcUrl, (route) => fulfillHyperEvmRoute(route, state));
      return state;
    },

    stateFor(context) {
      return contextStates.find((entry) => entry.context === context)?.state || null;
    },

    contextEntries(context) {
      const state = guard.stateFor(context);
      return state ? rpcEntries(state) : [];
    },

    patchBrowser(browser) {
      const ownNewContext = Object.prototype.hasOwnProperty.call(browser, "newContext");
      const previous = browser.newContext;
      browser.newContext = async function newGuardedContext(...args) {
        const context = await previous.apply(this, args);
        await guard.protectContext(context);
        return context;
      };
      return () => {
        if (ownNewContext) {
          browser.newContext = previous;
        } else {
          delete browser.newContext;
        }
      };
    },

    async violations() {
      const pending = observed.filter(
        (request) => !wasHyperEvmRequestMocked(request) && !acknowledged.has(request)
      );
      await Promise.all(pending.map((request) => settled(request, settleMs)));
      const unmocked = observed
        .filter((request) => !wasHyperEvmRequestMocked(request) && !acknowledged.has(request))
        .map(describeRequest);
      const states = [
        ...contextStates.map((entry) => entry.state),
        ...pages.map((page) => hyperEvmRpcController(page)?.state).filter(Boolean)
      ];
      const unique = [...new Set(states)];
      return {
        unmocked,
        unknown: unique.flatMap((state) => state.unknown),
        oversizedBatches: unique.flatMap((state) => state.oversizedBatches || [])
      };
    },

    acknowledge() {
      for (const request of observed) acknowledged.add(request);
      const states = [
        ...contextStates.map((entry) => entry.state),
        ...pages.map((page) => hyperEvmRpcController(page)?.state).filter(Boolean)
      ];
      for (const state of states) {
        state.unknown.length = 0;
        if (state.oversizedBatches) state.oversizedBatches.length = 0;
      }
    },

    async assertClean() {
      const { unmocked, unknown, oversizedBatches } = await guard.violations();
      const problems = [];
      if (unmocked.length > 0) {
        problems.push(`requests that reached the HyperEVM RPC host without the mock answering them: ${JSON.stringify(unmocked)}`);
      }
      if (unknown.length > 0) {
        problems.push(`JSON-RPC methods the HyperEVM mock could not answer: ${JSON.stringify(unknown)}`);
      }
      if (oversizedBatches.length > 0) {
        problems.push(`JSON-RPC batches over the public RPC's 20-entry cap (sizes): ${JSON.stringify(oversizedBatches)}`);
      }
      if (problems.length > 0) {
        throw new Error(`HyperEVM RPC guard: ${problems.join("; ")}`);
      }
    },

    observedUrls() {
      return observed.map(describeRequest);
    }
  };
  return guard;
}
