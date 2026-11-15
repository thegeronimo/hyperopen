import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);

export const upstreamPath = path.join(
  repoRoot,
  "node_modules/osqp/dist/osqp.min.js",
);

export const vendoredPath = path.join(
  repoRoot,
  "vendor/osqp/osqp_patched.mjs",
);

export const edits = [
  {
    name: "o() collects the pointers it allocates",
    from:
      "function o(A,I,g){const C=D._malloc(8*A.data.length);D.HEAPF64.set(A.data,C/8);" +
      "const Q=D._malloc(4*A.row_indices.length);D.HEAP32.set(A.row_indices,Q/4);" +
      "const B=D._malloc(4*A.column_pointers.length);" +
      "return D.HEAP32.set(A.column_pointers,B/4),D._create_csc_matrix(I,g,A.data.length,C,Q,B)}",
    to:
      "function o(A,I,g,$csc){const C=D._malloc(8*A.data.length);D.HEAPF64.set(A.data,C/8);" +
      "const Q=D._malloc(4*A.row_indices.length);D.HEAP32.set(A.row_indices,Q/4);" +
      "const B=D._malloc(4*A.column_pointers.length);" +
      "return D.HEAP32.set(A.column_pointers,B/4),$csc&&$csc.push(C,Q,B)," +
      "D._create_csc_matrix(I,g,A.data.length,C,Q,B)}",
  },
  {
    name: "setup() threads one collector through both matrices",
    from:
      "const{P:C,A:B,q:i,l:R,u:s}=I,F=i.length,h=R.length,K=o(C,F,F),y=o(B,h,F),",
    to:
      "const $csc=[],{P:C,A:B,q:i,l:R,u:s}=I,F=i.length,h=R.length," +
      "K=o(C,F,F,$csc),y=o(B,h,F,$csc),",
  },
  {
    name: "the collector is reachable from _state",
    from: "pointer_to_u:S,l:R,u:s,num_variables:F}",
    to: "pointer_to_u:S,l:R,u:s,num_variables:F,csc_array_pointers:$csc}",
  },
  {
    name: "setup() passes eps_prim_inf rather than eps_dual_inf twice",
    from: "c.eps_abs,c.eps_rel,c.eps_dual_inf,c.eps_dual_inf,c.alpha",
    to: "c.eps_abs,c.eps_rel,c.eps_prim_inf,c.eps_dual_inf,c.alpha",
  },
  {
    name: "cleanup() frees the six leaked arrays",
    from:
      "D._osqp_cleanup(A),D._cleanup_data(I),D._cleanup_settings(g)," +
      "D._free(C),D._free(Q),D._free(B),D._free(E),D._free(i)}}",
    to:
      "D._osqp_cleanup(A),D._cleanup_data(I),D._cleanup_settings(g)," +
      "D._free(C),D._free(Q),D._free(B),D._free(E),D._free(i);" +
      "const $p=this._state.csc_array_pointers;" +
      "if($p){for(let $k=0;$k<$p.length;$k++)D._free($p[$k]);$p.length=0}}}",
  },
];

const banner = [
  "// GENERATED -- do not edit by hand.",
  "// `osqp@0.0.2` with its per-solve WebAssembly heap leak fixed.",
  "// Regenerate with: node tools/optimizer/patch_osqp.mjs --write",
  "// Rationale and the exact edits: tools/optimizer/patch_osqp.mjs",
  "",
].join("\n");

export function patchOsqpSource(source) {
  let out = source.replace(/\n?\/\/# sourceMappingURL=osqp\.min\.js\.map\s*$/, "\n");
  for (const edit of edits) {
    const occurrences = out.split(edit.from).length - 1;
    if (occurrences !== 1) {
      throw new Error(
        `osqp patch anchor "${edit.name}" matched ${occurrences} times, expected exactly 1. ` +
          "The upstream package has changed shape; re-derive the patch before trusting it.",
      );
    }
    out = out.replace(edit.from, edit.to);
  }
  return banner + out;
}

export function readUpstream() {
  return fs.readFileSync(upstreamPath, "utf8");
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const patched = patchOsqpSource(readUpstream());
  if (process.argv.includes("--write")) {
    fs.mkdirSync(path.dirname(vendoredPath), { recursive: true });
    fs.writeFileSync(vendoredPath, patched);
    console.log(`wrote ${path.relative(repoRoot, vendoredPath)} (${patched.length} bytes)`);
  } else {
    const current = fs.existsSync(vendoredPath)
      ? fs.readFileSync(vendoredPath, "utf8")
      : null;
    if (current === patched) {
      console.log("vendor/osqp/osqp-patched.mjs is up to date");
    } else {
      console.error(
        "vendor/osqp/osqp-patched.mjs is stale. Run: node tools/optimizer/patch_osqp.mjs --write",
      );
      process.exit(1);
    }
  }
}
