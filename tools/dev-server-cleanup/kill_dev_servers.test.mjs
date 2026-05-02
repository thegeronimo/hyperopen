import assert from "node:assert/strict";
import test from "node:test";

import {
  collectKillTargets,
  parseProcessTable,
  uniqueProcessGroups
} from "./kill_dev_servers.mjs";

test("parseProcessTable reads pid metadata and preserves command text", () => {
  const processes = parseProcessTable(`  PID  PPID  PGID COMMAND
  101     1   101 npm run dev
  102   101   101 node ./node_modules/.bin/concurrently -k
`);

  assert.deepEqual(processes, [
    { pid: 101, ppid: 1, pgid: 101, command: "npm run dev" },
    { pid: 102, ppid: 101, pgid: 101, command: "node ./node_modules/.bin/concurrently -k" }
  ]);
});

test("collectKillTargets selects repo dev server roots and descendants", () => {
  const cwd = "/repo/hyperopen";
  const processes = [
    { pid: 201, ppid: 1, pgid: 201, command: `npm run dev INIT_CWD=${cwd}` },
    { pid: 202, ppid: 201, pgid: 201, command: "npm run css:watch" },
    { pid: 203, ppid: 201, pgid: 201, command: "npm exec shadow-cljs watch app" },
    { pid: 204, ppid: 203, pgid: 201, command: "/usr/bin/java clojure.main -m shadow.cljs.devtools.cli watch app" },
    { pid: 301, ppid: 1, pgid: 301, command: "npm run dev INIT_CWD=/other/hyperopen" },
    { pid: 302, ppid: 301, pgid: 301, command: "npm exec shadow-cljs watch app" }
  ];

  const targets = collectKillTargets(processes, { cwd, selfPid: 999 });

  assert.deepEqual(targets.map((process) => process.pid), [201, 202, 203, 204]);
});

test("collectKillTargets includes shadow server ports for the same process group", () => {
  const cwd = "/repo/hyperopen";
  const processes = [
    { pid: 401, ppid: 1, pgid: 401, command: `node ${cwd}/node_modules/.bin/shadow-cljs server status` },
    { pid: 402, ppid: 401, pgid: 401, command: "/usr/bin/java clojure.main -m shadow.cljs.devtools.cli --npm server status" }
  ];

  const targets = collectKillTargets(processes, { cwd, selfPid: 999 });

  assert.deepEqual(targets.map((process) => process.pid), [401, 402]);
});

test("uniqueProcessGroups returns negative process group ids once", () => {
  const targets = [
    { pid: 201, ppid: 1, pgid: 201, command: "npm run dev" },
    { pid: 202, ppid: 201, pgid: 201, command: "npm run css:watch" },
    { pid: 501, ppid: 1, pgid: 501, command: "npm run portfolio" }
  ];

  assert.deepEqual(uniqueProcessGroups(targets), [-201, -501]);
});
