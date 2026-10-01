// The HyperEVM RPC guard every Playwright spec runs under.
//
// The HyperEVM balance poller reads https://rpc.hyperliquid.xyz/evm for any
// account the app shows on /trade or /portfolio (a connected wallet, a
// spectated address, a trader route). The public RPC allows 100 requests a
// minute per IP and serves live balances, so a spec must never reach it.
//
// `guarded_test.mjs` runs `createHyperEvmRpcGuard()` as an automatic fixture
// for every test. For the test's own browser context, and for every context
// the test opens through `browser.newContext()` / `browser.newPage()`, the
// guard:
//
// - routes the RPC host at the CONTEXT level, answering from an empty
//   fixture (live bridge state, no balances), so every page in it is
//   deterministic whether or not it went through `visitRoute`. A page-level
//   `routeHyperEvmRpc(page, fixture)` still takes precedence;
// - observes every request to the host with `context.on("request")`, apart
//   from any route, and at the end of the test fails it for each request no
//   mock handler answered (`wasHyperEvmRequestMocked`), for each JSON-RPC
//   method a mock could not answer, and for each batch over the public RPC's
//   20-entry cap.
//
// A request escapes the mock when a page-level handler registered after the
// mock calls `route.continue()`/`abort()` for it, when it comes from a
// context the guard never saw, or when it bypasses routing altogether; the
// guard reports all of these the same way because it never looks at routes.
//
// Nothing here imports Playwright, so node tests can drive it with fakes.
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

// Wait until `request` is answered or fails, at most `ms`, so a request
// still waiting for its route handler at teardown is not misreported.
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
    // Route the RPC host for every page of `context` and observe its
    // requests. Idempotent per context.
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

    // The context-level default state of `context` (its fixture and the
    // entries it answered), or null.
    stateFor(context) {
      return contextStates.find((entry) => entry.context === context)?.state || null;
    },

    // Every JSON-RPC entry the context-level default answered for `context`.
    contextEntries(context) {
      const state = guard.stateFor(context);
      return state ? rpcEntries(state) : [];
    },

    // Make `browser.newContext()` (and `browser.newPage()`, which calls it)
    // protect each context it opens. Returns the function that undoes it.
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

    // Everything the test did wrong so far: requests no mock answered,
    // methods a mock could not answer, and oversized batches.
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

    // Forget what has been recorded so far. Only the guard's own self-test
    // uses this, after proving a deliberate escape was caught.
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

    // Test-only view of the observed requests.
    observedUrls() {
      return observed.map(describeRequest);
    }
  };
  return guard;
}
