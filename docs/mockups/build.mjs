// Builds the spec page with mock-ups: bun add marked && bun build.mjs ../spec.md index.html
import { marked } from "marked";
import { readFileSync, writeFileSync } from "fs";
import * as M from "./mocks.mjs";
import * as D from "./directions.mjs";

const [spec, out] = process.argv.slice(2);
let md = readFileSync(spec, "utf8");
// Link bare source URLs.
md = md.replace(/ — (https:\/\/\S+)/g, " — [$1]($1)");
const cut = md.indexOf("## 1.");
const body = D.html + marked.parse(md.slice(0, cut)) + M.html + marked.parse(md.slice(cut));

const css = `
body{font-size:15px;line-height:1.5}
h1{font-size:22px;margin:4px 0 8px}h2{font-size:18px;margin:28px 0 8px;padding-top:12px;border-top:1px solid var(--line,#d4d4d8)}
h3{font-size:16px;margin:20px 0 6px}
a{color:var(--accent,#2a5fd4);word-break:break-all}
code{background:var(--well,#f1f1f3);padding:1px 4px;border-radius:4px;font-size:13px}
table{border-collapse:collapse;width:100%;font-size:13px;display:block;overflow-x:auto;margin:8px 0}
th,td{border:1px solid var(--line,#d4d4d8);padding:6px 8px;text-align:left;vertical-align:top}
th{background:var(--well,#f1f1f3)}
.cap{color:var(--fg2,#6b6d74);font-size:13px}
` + M.css + D.css;

writeFileSync(out, `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LFM3 on Pixel: spec</title><style>${css}</style></head><body>${body}<script>${M.script}${D.script}</script></body></html>`);
