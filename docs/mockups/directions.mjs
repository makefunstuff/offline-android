// Three visual directions for the owner to pick from, each on Today, Chat and Journal.
import { i, sb, gb } from "./mocks.mjs";

// An 8-lobed "cookie" shape (Material 3 Expressive style) as an SVG path.
const cookie = (() => {
  const pts = [];
  for (let k = 0; k < 96; k++) {
    const t = (k / 96) * Math.PI * 2;
    const r = 50 * (1 + 0.075 * Math.cos(8 * t)) * 0.93;
    pts.push(`${(50 + r * Math.cos(t)).toFixed(1)},${(50 + r * Math.sin(t)).toFixed(1)}`);
  }
  return `M${pts.join("L")}Z`;
})();
const blob = (cls, inner) => `<span class="blob ${cls}"><svg viewBox="0 0 100 100"><path d="${cookie}"/></svg><span>${inner}</span></span>`;
const squiggle = '<svg class="sq" viewBox="0 0 120 8" preserveAspectRatio="none"><path d="M1 5 Q 7 1 13 5 T 25 5 T 37 5 T 49 5 T 61 5 T 73 5 T 85 5 T 97 5 T 109 5 T 119 5"/></svg>';
const sun = '<svg class="doodle" viewBox="0 0 64 64"><circle cx="32" cy="33" r="11"/><path d="M32 8v8M32 50v7M8 33h8M49 33h8M15 16l5 5M44 45l5 5M15 50l5-5M44 21l5-5"/></svg>';
const sprout = '<svg class="doodle sm" viewBox="0 0 64 64"><path d="M32 58V30M32 38c-10 0-16-7-16-16 10 0 16 7 16 16zM32 32c0-10 7-17 17-17 0 10-7 17-17 17z"/></svg>';

const phone = (cls, body) => `<div class="dph ${cls}"><div class="scr">${body}</div></div>`;
const navd = (a, labels = true) => `<div class="dnav">${[["sun", "Today"], ["chat", "Chat"], ["book", "Library"], ["todo", "To-dos"]]
  .map(([ic, l]) => `<div class="dni ${l === a ? "on" : ""}"><span>${i(ic)}</span>${labels ? l : ""}</div>`).join("")}</div>`;

/* ---------- A. Signal: bold type, ink and one hot colour ---------- */
const aToday = phone("A", `${sb()}<div class="app">
  <div class="a-top"><span class="a-lab">FRI · 30 OCT</span><span class="a-av">${i("user", "s")}</span></div>
  <div class="a-h">Good<br>afternoon<span class="a-dot">.</span></div>
  <div class="pad">
    <div class="a-comp"><span>Ask, capture or do…</span>${i("camera")}<span class="a-mic">${i("mic")}</span></div>
    <div class="a-lab mt">TODAY — 3 THINGS</div>
    <div class="a-row"><span class="a-t">15:30</span><div><b>Design review</b><span>Room 4 · 45 min</span></div></div>
    <div class="a-row hot"><span class="a-t">18:00</span><div><b>Call the plumber</b><span>Reminder</span></div></div>
    <div class="a-row last"><span class="a-t">3</span><div><b>To-dos due</b><span>Oat milk, passport, invoice</span></div></div>
    <div class="a-lab mt">JUST CAPTURED</div>
    <div class="a-cap"><b>Weekend market stall for old prints</b><span>IDEA · 11:20</span></div>
  </div></div>${navd("Today")}${gb}`);

const aChat = phone("A", `${sb()}<div class="app">
  <div class="a-hdr">${i("list")}<b>ASSISTANT</b>${i("more")}</div>
  <div class="pad a-thread">
    <div class="a-me">${i("wave", "s")} 0:06 — Remind me to call the plumber at six, and add oat milk to my list</div>
    <div class="a-tool"><div class="a-tk">${i("check")}</div><div><span class="a-lab">REMINDER SET</span><b>Call the plumber</b><span>Today 18:00</span></div><span class="a-undo">UNDO</span></div>
    <div class="a-tool alt"><div class="a-tk">${i("plus")}</div><div><span class="a-lab">ADDED TO SHOPPING</span><b>Oat milk</b></div><span class="a-undo">UNDO</span></div>
    <div class="a-ai">Done. Anything else before six?<span class="a-meta">LFM3-1.7B · ON THIS PHONE</span></div>
  </div>
  <div class="a-comp in"><span>Message…</span>${i("camera")}<span class="a-mic">${i("mic")}</span></div></div>${gb}`);

const aJournal = phone("A", `${sb()}<div class="app">
  <div class="a-top"><span class="a-lab">LIBRARY / JOURNAL</span>${i("search")}</div>
  <div class="pad">
    <div class="a-prompt"><span class="a-lab">TODAY'S PROMPT</span><b>What gave you energy this week?</b><div class="a-pa"><span>${i("mic", "s")} SPEAK</span><span>${i("edit", "s")} WRITE</span></div></div>
    <div class="a-day"><span class="a-big">30</span><span class="a-lab">FRIDAY</span></div>
    <div class="a-je"><span class="a-mood m1"></span><p>Slept well. Felt calmer after the walk; less worried about the review.</p></div>
    <div class="a-day"><span class="a-big">29</span><span class="a-lab">THURSDAY</span></div>
    <div class="a-je"><span class="a-mood m2"></span><p>Long day. Want to say no to the Saturday shift.</p></div>
    <div class="a-je"><span class="a-mood m1"></span><p>Lunch with Mia, laughed a lot.</p></div>
  </div></div>${navd("Library")}${gb}`);

/* ---------- B. Bloom: expressive shapes and soft colour blocks ---------- */
const bToday = phone("B", `${sb()}<div class="app">
  <div class="b-top"><div><span class="b-sub">Friday 30 October</span><div class="b-h">Hi there</div></div>${blob("bav", i("user"))}</div>
  <div class="pad">
    <div class="b-comp"><span>Ask, capture or do…</span>${i("camera")}${blob("mic", i("mic"))}</div>
    <div class="b-tiles">
      <div class="b-tile lil"><span class="b-ic">${i("cal")}</span><b>Design review</b><span>15:30</span></div>
      <div class="b-tile pea"><span class="b-ic">${i("bell")}</span><b>Call the plumber</b><span>18:00</span></div>
      <div class="b-tile min wide"><span class="b-ic">${i("todo")}</span><div><b>3 to-dos due</b><span>Oat milk · passport · invoice</span></div><span class="b-count">3</span></div>
    </div>
    <div class="b-sec">Just captured</div>
    <div class="b-cap sky">${i("bulb")}<div><b>Weekend market stall for old prints</b><span>Idea · 11:20 · voice</span></div></div>
  </div></div>${navd("Today")}${gb}`);

const bChat = phone("B", `${sb()}<div class="app">
  <div class="b-hdr">${i("list")}<b>Assistant</b>${i("more")}</div>
  <div class="pad b-thread">
    <div class="b-me"><span class="b-wave"><i></i><i></i><i></i><i></i><i></i><i></i></span>Remind me to call the plumber at six, and add oat milk to my list</div>
    <div class="b-tool min">${blob("ok", i("check"))}<div><b>Reminder set</b><span>Call the plumber · 18:00</span></div><span class="b-undo">Undo</span></div>
    <div class="b-tool pea">${blob("ok", i("plus"))}<div><b>Oat milk</b><span>Added to Shopping</span></div><span class="b-undo">Undo</span></div>
    <div class="b-ai">Done. Anything else before six?<span class="b-meta">LFM3-1.7B · on this phone</span></div>
    <div class="b-chips"><span>Set a timer</span><span>Show my list</span><span>What's next?</span></div>
  </div>
  <div class="b-comp in"><span>Message…</span>${i("camera")}${blob("mic", i("mic"))}</div></div>${gb}`);

const bJournal = phone("B", `${sb()}<div class="app">
  <div class="b-top"><div class="b-h">Journal</div>${blob("bav sm", i("search"))}</div>
  <div class="pad">
    <div class="b-prompt"><span>Today's prompt</span><b>What gave you energy this week?</b><div class="b-pa"><span class="pri">${i("mic", "s")}Speak</span><span>${i("edit", "s")}Write</span></div></div>
    <div class="b-week">${["M", "T", "W", "T", "F", "S", "S"].map((d, k) => `<span class="${k === 4 ? "on" : ""} ${[0, 2, 3, 4].includes(k) ? "has" : ""} ${k === 3 ? "wm" : ""}">${d}</span>`).join("")}</div>
    <div class="b-je lil"><div class="b-jh"><b>Friday</b><span class="b-mood">calm</span></div><p>Slept well. Felt calmer after the walk; less worried about the review.</p></div>
    <div class="b-je pea"><div class="b-jh"><b>Thursday</b><span class="b-mood">tired</span></div><p>Long day. Want to say no to the Saturday shift.</p></div>
    <div class="b-je sky"><div class="b-jh"><b>Thursday</b><span class="b-mood">happy</span></div><p>Lunch with Mia, laughed a lot.</p></div>
  </div></div>${navd("Library")}${gb}`);

/* ---------- C. Paper: warm, personal, a little hand-made ---------- */
const cToday = phone("C", `${sb()}<div class="app">
  <div class="c-top"><div><span class="c-sub">Friday, 30 October</span><div class="c-h">Good<br>afternoon</div>${squiggle}</div>${sun}</div>
  <div class="pad">
    <div class="c-comp"><span>Ask, capture or do…</span>${i("camera")}<span class="c-mic">${i("mic")}</span></div>
    <div class="c-sec">On today</div>
    <div class="c-li"><span class="c-time">15:30</span><div><b>Design review</b><span>Room 4</span></div></div>
    <div class="c-li"><span class="c-time t2">18:00</span><div><b>Call the plumber</b><span>Reminder</span></div></div>
    <div class="c-li"><span class="c-time t3">3</span><div><b>To-dos due</b><span>Oat milk, passport, invoice</span></div></div>
    <div class="c-sec">Caught today</div>
    <div class="c-note"><b>Weekend market stall for old prints</b><span>an idea, 11:20</span></div>
  </div></div>${navd("Today")}${gb}`);

const cChat = phone("C", `${sb()}<div class="app">
  <div class="c-hdr">${i("list")}<b>Assistant</b>${i("more")}</div>
  <div class="pad c-thread">
    <div class="c-me">${i("wave", "s")} Remind me to call the plumber at six, and add oat milk to my list</div>
    <div class="c-ticket"><div class="c-stub">${i("bell")}<span>18:00</span></div><div class="c-tb"><b>Reminder set</b><span>Call the plumber, today</span><em>Undo</em></div></div>
    <div class="c-ticket"><div class="c-stub s2">${i("todo")}<span>list</span></div><div class="c-tb"><b>Oat milk</b><span>added to Shopping</span><em>Undo</em></div></div>
    <div class="c-ai">Done. Anything else before six?<span>written on this phone</span></div>
  </div>
  <div class="c-comp in"><span>Message…</span>${i("camera")}<span class="c-mic">${i("mic")}</span></div></div>${gb}`);

const cJournal = phone("C", `${sb()}<div class="app">
  <div class="c-top"><div><div class="c-h">Journal</div>${squiggle}</div>${sprout}</div>
  <div class="pad">
    <div class="c-prompt"><span>Today's prompt</span><b>What gave you energy this week?</b><div class="c-pa"><span>${i("mic", "s")}Speak</span><span>${i("edit", "s")}Write</span></div></div>
    <div class="c-entry"><div class="c-stamp"><b>30</b><span>Fri</span></div><p>Slept well. Felt calmer after the walk; less worried about the review.<span>8:05 in the morning</span></p></div>
    <div class="c-entry"><div class="c-stamp s2"><b>29</b><span>Thu</span></div><p>Long day. Want to say no to the Saturday shift.<span>late evening, by voice</span></p></div>
    <div class="c-entry"><div class="c-stamp s3"><b>29</b><span>Thu</span></div><p>Lunch with Mia, laughed a lot.<span>lunchtime</span></p></div>
  </div></div>${navd("Library")}${gb}`);

const DIRS = [
  ["A", "Signal", "Bold type, ink black and one hot tomato colour. Big numbers, square edges, thick lines. Confident and fast to scan.", ["#0a0a0a", "#ff4f1f", "#ffffff"], "Motion: snappy, short slides; numbers tick.", [aToday, aChat, aJournal]],
  ["B", "Bloom", "Material 3 Expressive: playful shapes (the mic and avatar are soft 'cookies'), colour blocks per kind of thing, big rounded type. Friendly and easy to tap.", ["#6b4eff", "#e9ddff", "#c9f0d8", "#ffdcc7"], "Motion: springy; buttons squish on press, shapes morph.", [bToday, bChat, bJournal]],
  ["C", "Paper", "A warm notebook: dotted paper, serif headings, terracotta and olive, small hand-drawn doodles, entries stamped like a diary. The most personal.", ["#f6efe4", "#c4532d", "#6b7a3a", "#d9a23b"], "Motion: gentle fades; ink draws the squiggles in.", [cToday, cChat, cJournal]],
];

export const html = `<h2 id="directions">Visual directions: pick one</h2>
<p class="cap">Three directions, each on Today, Chat with a tool card, and Journal. All flat (no gradients or glows), dark and light. The chosen one is then used to redraw all 13 screens.</p>
<div class="themes" id="themesDir" role="group"><button data-t="dark">Dark</button><button data-t="light">Light</button></div>
<div class="dirs" id="dirs">${DIRS.map(([k, name, desc, sw, motion, screens]) => `
<section class="dcol"><div class="dhead"><span class="dk">${k}</span><div><b>${name}</b><p>${desc}</p><div class="sws">${sw.map((c) => `<i style="background:${c}"></i>`).join("")}</div><p class="mo">${motion}</p></div></div>
${screens.map((s, n) => `<figure>${s}<figcaption>${k}${n + 1} · ${["Today", "Chat with a tool card", "Journal"][n]}</figcaption></figure>`).join("\n")}
</section>`).join("")}
</div>`;

export const script = `(function(){var p=document.getElementById('dirs'),b=document.querySelectorAll('#themesDir button');
function set(t){p.className='dirs '+t;b.forEach(function(x){x.className=x.dataset.t===t?'on':''})}
b.forEach(function(x){x.addEventListener('click',function(){set(x.dataset.t)})});
set(document.documentElement.classList.contains('shepherdr-light')?'light':'dark');})();`;

const dots = (c) => `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='18' height='18'%3E%3Ccircle cx='3' cy='3' r='1.1' fill='%23${c}'/%3E%3C/svg%3E")`;

export const css = `
.dirs{display:flex;flex-wrap:wrap;gap:28px 20px;justify-content:center;margin:12px 0 8px}
.dcol{display:flex;flex-direction:column;gap:18px;align-items:center;width:374px}
.dhead{display:flex;gap:12px;align-self:stretch;min-height:150px}
.dk{width:34px;height:34px;border-radius:17px;background:var(--fg,#18181b);color:var(--bg,#fff);display:flex;align-items:center;justify-content:center;font-weight:700;flex:none}
.dhead b{font-size:18px}.dhead p{margin:4px 0;font-size:13.5px;color:var(--fg2,#6b6d74)}.dhead .mo{font-style:italic}
.sws{display:flex;gap:6px;margin:6px 0}.sws i{width:22px;height:22px;border-radius:6px;border:1px solid var(--line,#d4d4d8)}
.dph{width:360px;height:800px;border:7px solid #1d1e21;border-radius:42px;overflow:hidden;background:var(--bg);color:var(--t1)}
.dph .app{position:relative}
.dnav{display:flex;height:70px;flex:none;border-top:1px solid var(--ln)}
.dni{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;font-size:12px;color:var(--t2)}
.dni span{width:56px;height:30px;border-radius:15px;display:flex;align-items:center;justify-content:center}
.dni.on{color:var(--t1);font-weight:700}.dni.on span{background:var(--acb);color:var(--acf)}

/* A Signal */
.dph.A{--bg:#ffffff;--t1:#0a0a0a;--t2:#55565c;--ln:#0a0a0a;--ac:#ff4f1f;--acb:#0a0a0a;--acf:#ffffff;--s2:#f1f1ee}
.dirs.dark .dph.A{--bg:#0b0b0b;--t1:#f5f5f0;--t2:#a3a39c;--ln:#f5f5f0;--ac:#ff6a3d;--acb:#f5f5f0;--acf:#0b0b0b;--s2:#1b1b1a}
.A .dnav{border-top:2px solid var(--ln)}
.a-lab{font:600 11px/1.2 ui-monospace,"Roboto Mono",Menlo,monospace;letter-spacing:.08em;color:var(--t2)}.a-lab.mt{display:block;margin:20px 0 8px}
.a-top{display:flex;justify-content:space-between;align-items:center;padding:12px 16px 0}
.a-av{width:36px;height:36px;border:2px solid var(--t1);display:flex;align-items:center;justify-content:center;font-weight:800;font-size:13px}
.a-h{font-size:46px;line-height:.95;font-weight:800;letter-spacing:-.04em;padding:10px 16px 16px}.a-dot{color:var(--ac)}
.a-comp{display:flex;align-items:center;gap:12px;border:2px solid var(--t1);height:52px;padding:0 4px 0 14px;font-size:15px}
.a-comp>span:first-child{flex:1;color:var(--t2)}.a-comp.in{margin:8px 12px 10px}
.a-mic{width:42px;height:42px;background:var(--ac);color:#0a0a0a;display:flex;align-items:center;justify-content:center}
.a-row{display:flex;gap:14px;align-items:center;padding:10px 0;border-top:2px solid var(--t1)}.a-row.last{border-bottom:2px solid var(--t1)}
.a-row div{display:flex;flex-direction:column}.a-row span{color:var(--t2);font-size:12.5px}.a-row b{font-size:16px}
.a-t{font-size:26px!important;font-weight:800;letter-spacing:-.03em;width:84px;color:var(--t1)!important}.a-row.hot .a-t{color:var(--ac)!important}
.a-cap{background:var(--ac);color:#0a0a0a;padding:14px;display:flex;flex-direction:column;gap:6px}.a-cap b{font-size:17px;font-weight:800;line-height:1.15}.a-cap span{font:600 11px ui-monospace,monospace;letter-spacing:.08em}
.a-hdr{display:flex;align-items:center;gap:14px;padding:10px 16px;border-bottom:2px solid var(--t1)}.a-hdr b{flex:1;font:700 13px ui-monospace,monospace;letter-spacing:.1em}
.a-thread{display:flex;flex-direction:column;gap:12px;padding-top:14px}
.a-me{align-self:flex-end;max-width:84%;background:var(--t1);color:var(--bg);padding:12px 14px;font-weight:500}
.a-tool{display:flex;align-items:center;gap:12px;border:2px solid var(--t1);padding:10px}.a-tool div:nth-child(2){flex:1;display:flex;flex-direction:column}
.a-tool b{font-size:17px;font-weight:800}.a-tool span{color:var(--t2);font-size:12.5px}
.a-tk{width:40px;height:40px;background:var(--ac);color:#0a0a0a;display:flex;align-items:center;justify-content:center}.a-tool.alt .a-tk{background:var(--t1);color:var(--bg)}
.a-undo{font:700 12px ui-monospace,monospace!important;letter-spacing:.08em;color:var(--t1)!important;text-decoration:underline;text-underline-offset:3px}
.a-ai{font-size:22px;font-weight:700;letter-spacing:-.02em;line-height:1.2;margin-top:4px}.a-meta{display:block;font:600 10.5px ui-monospace,monospace;letter-spacing:.08em;color:var(--t2);margin-top:8px}
.a-prompt{background:var(--t1);color:var(--bg);padding:16px;margin-top:12px}.a-prompt .a-lab{color:var(--ac)}.dirs.dark .a-prompt .a-lab{color:#c2350b}.a-prompt b{display:block;font-size:24px;font-weight:800;line-height:1.1;letter-spacing:-.02em;margin:8px 0 14px}
.a-pa{display:flex;gap:8px}.a-pa span{display:flex;align-items:center;gap:6px;border:2px solid var(--bg);padding:7px 12px;font:700 11.5px ui-monospace,monospace;letter-spacing:.08em}.a-pa span:first-child{background:var(--ac);border-color:var(--ac);color:#0a0a0a}
.a-day{display:flex;align-items:baseline;gap:10px;margin:18px 0 4px;border-bottom:2px solid var(--t1)}.a-big{font-size:40px;font-weight:800;letter-spacing:-.04em;line-height:1}
.a-je{display:flex;gap:12px;padding:8px 0}.a-je p{margin:0;font-size:15px}.a-mood{width:12px;height:12px;flex:none;margin-top:5px}.a-mood.m1{background:var(--ac)}.a-mood.m2{background:var(--t1)}

/* B Bloom */
.dph.B{--bg:#fbf8ff;--t1:#1b1a21;--t2:#5c5868;--ln:#e7e0f2;--ac:#6b4eff;--acb:#e9ddff;--acf:#3b22c9;--lil:#e9ddff;--min:#c9f0d8;--pea:#ffdcc7;--sky:#d3e4ff;--on:#1b1a21}
.dirs.dark .dph.B{--bg:#141218;--t1:#ede7f6;--t2:#b3abc3;--ln:#2b2734;--ac:#b9a6ff;--acb:#3a2e5c;--acf:#e4dbff;--lil:#3a2e5c;--min:#1f3d2c;--pea:#4a2e1f;--sky:#1e3150;--on:#ede7f6}
.B .dnav{border-top:0}.B .dni span{height:32px;border-radius:16px}
.blob{position:relative;display:inline-flex;align-items:center;justify-content:center;flex:none}.blob>svg{position:absolute;inset:0;width:100%;height:100%}.blob span{position:relative;display:flex}
.blob.bav{width:48px;height:48px}.blob.bav path{fill:var(--min)}.blob.bav.sm path{fill:var(--lil)}
.blob.mic{width:46px;height:46px;color:#fff}.blob.mic path{fill:var(--ac)}.dirs.dark .blob.mic{color:#141218}
.blob.ok{width:40px;height:40px}.blob.ok path{fill:var(--bg)}
.b-top{display:flex;justify-content:space-between;align-items:flex-end;padding:12px 16px 14px}
.b-sub{color:var(--t2);font-size:13px}.b-h{font-size:34px;font-weight:700;letter-spacing:-.02em;line-height:1.1}
.b-comp{display:flex;align-items:center;gap:12px;background:var(--lil);border-radius:30px;height:56px;padding:0 5px 0 20px;font-size:15px;margin-bottom:14px}
.b-comp>span:first-child{flex:1;color:var(--t2)}.b-comp.in{margin:8px 12px 10px}
.b-tiles{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.b-tile{border-radius:28px 28px 28px 10px;padding:14px;display:flex;flex-direction:column;gap:2px;min-height:110px}.b-tile b{font-size:16px;line-height:1.2;margin-top:auto}.b-tile span{color:var(--t2);font-size:13px}
.b-tile.wide{grid-column:span 2;flex-direction:row;align-items:center;gap:12px;min-height:0;border-radius:10px 28px 28px 28px}.b-tile.wide div{flex:1;display:flex;flex-direction:column}.b-tile.wide b{margin:0}
.lil{background:var(--lil)}.pea{background:var(--pea)}.min{background:var(--min)}.sky{background:var(--sky)}
.b-ic{width:36px;height:36px;border-radius:12px;background:var(--bg);display:flex;align-items:center;justify-content:center;color:var(--t1)!important}
.b-count{font-size:34px!important;font-weight:700;color:var(--t1)!important}
.b-sec{font-size:15px;font-weight:700;margin:20px 0 8px}
.b-cap{display:flex;gap:12px;align-items:center;border-radius:24px;padding:14px}.b-cap div{display:flex;flex-direction:column}.b-cap span{color:var(--t2);font-size:12.5px}
.b-hdr{display:flex;align-items:center;gap:14px;padding:10px 16px}.b-hdr b{flex:1;font-size:20px;font-weight:700}
.b-thread{display:flex;flex-direction:column;gap:10px}
.b-me{align-self:flex-end;max-width:84%;background:var(--ac);color:#fff;border-radius:24px 24px 8px 24px;padding:12px 16px}.dirs.dark .b-me{color:#141218}
.b-wave{display:flex;gap:3px;align-items:center;height:16px;margin-bottom:6px}.b-wave i{display:block;width:4px;border-radius:2px;background:currentColor;height:8px}.b-wave i:nth-child(2n){height:14px}.b-wave i:nth-child(3){height:16px}
.b-tool{display:flex;align-items:center;gap:12px;border-radius:24px;padding:10px 14px 10px 10px}.b-tool div{flex:1;display:flex;flex-direction:column}.b-tool span{color:var(--t2);font-size:12.5px}.b-tool b{font-size:16px}
.b-undo{background:var(--bg);border-radius:16px;padding:6px 12px;font-weight:600;color:var(--t1)!important;font-size:13px!important}
.b-ai{align-self:flex-start;max-width:84%;background:var(--acb);border-radius:24px 24px 24px 8px;padding:12px 16px;font-size:15px}
.b-meta{display:block;font-size:11.5px;color:var(--t2);margin-top:4px}
.b-chips{display:flex;gap:8px;flex-wrap:wrap}.b-chips span{border:1.5px solid var(--ac);color:var(--acf);border-radius:18px;padding:6px 12px;font-size:13px;font-weight:500}
.b-prompt{background:var(--sky);border-radius:32px 32px 32px 12px;padding:18px}.b-prompt>span{font-size:12.5px;color:var(--t2)}.b-prompt b{display:block;font-size:21px;line-height:1.2;margin:4px 0 14px}
.b-pa{display:flex;gap:8px}.b-pa span{display:flex;gap:6px;align-items:center;background:var(--bg);border-radius:20px;padding:8px 14px;font-weight:600;font-size:13.5px}.b-pa .pri{background:var(--ac);color:#fff}.dirs.dark .b-pa .pri{color:#141218}
.b-week{display:flex;justify-content:space-between;margin:18px 0 14px}.b-week span{width:38px;height:38px;border-radius:19px;display:flex;align-items:center;justify-content:center;font-size:13px;color:var(--t2);border:1.5px dashed var(--t2)}
.b-week .has{background:var(--min);border:0;color:var(--t1)}.b-week .wm{background:var(--pea)}.b-week .on{background:var(--ac);color:#fff;border-radius:12px}.dirs.dark .b-week .on{color:#141218}
.b-je{border-radius:24px;padding:14px;margin-bottom:10px}.b-je p{margin:6px 0 0}.b-jh{display:flex;justify-content:space-between;align-items:center}
.b-mood{background:var(--bg);border-radius:12px;padding:2px 10px;font-size:12px;color:var(--t2)}

/* C Paper */
.dph.C{--bg:#f6efe4;--t1:#2b2118;--t2:#6e5f50;--ln:#e2d6c4;--ac:#a9431f;--ol:#5d6a2f;--oc:#d9a23b;--oct:#8a5f12;--card:#fbf7f0;--acb:#efd9c9;--acf:#9c3e1e;background-image:${dots("d9cdb8")}}
.dirs.dark .dph.C{--bg:#1e1915;--t1:#f1e6d6;--t2:#bba893;--ln:#3a3029;--ac:#e07a55;--ol:#a5b46a;--oc:#e3b55b;--oct:#e3b55b;--card:#28211c;--acb:#3d2a20;--acf:#f0a283;background-image:${dots("2f2822")}}
.C .dnav{background:var(--bg)}
.c-top{display:flex;justify-content:space-between;align-items:flex-start;padding:14px 18px 10px}
.c-sub{color:var(--t2);font-size:13px;font-style:italic}.c-h{font:500 31px/1.12 "Iowan Old Style","Palatino Linotype",Palatino,Georgia,serif;letter-spacing:-.01em;margin-top:2px}
.sq{display:block;width:120px;height:8px;margin-top:6px}.sq path{fill:none;stroke:var(--ac);stroke-width:2;stroke-linecap:round}
.doodle{width:64px;height:64px;fill:none;stroke:var(--oc);stroke-width:2.5;stroke-linecap:round}.doodle.sm{stroke:var(--ol)}
.c-comp{display:flex;align-items:center;gap:12px;background:var(--card);border:1.5px solid var(--t1);border-radius:16px;height:54px;padding:0 6px 0 16px;font-size:15px}
.c-comp>span:first-child{flex:1;color:var(--t2);font-style:italic}.c-comp.in{margin:8px 12px 10px}
.c-mic{width:42px;height:42px;border-radius:12px;background:var(--ac);color:#fff8f0;display:flex;align-items:center;justify-content:center}.dirs.dark .c-mic{color:#1e1915}
.c-sec{font:italic 500 17px "Iowan Old Style",Palatino,Georgia,serif;color:var(--ac);margin:18px 0 6px}
.c-li{display:flex;gap:14px;align-items:center;padding:8px 0;border-bottom:1.5px dashed var(--ln)}.c-li div{display:flex;flex-direction:column}.c-li span{color:var(--t2);font-size:12.5px}
.c-time{width:58px;text-align:center;font:600 15px "Iowan Old Style",Georgia,serif;color:var(--t1)!important;background:var(--acb);border-radius:10px;padding:6px 0;transform:rotate(-2deg)}
.c-time.t2{background:var(--oc);color:#2b2118!important;transform:rotate(1.5deg)}.c-time.t3{background:var(--ol);color:#fbf7f0!important;transform:rotate(-1deg)}.dirs.dark .c-time.t3{color:#1e1915!important}
.c-note{background:var(--card);border:1.5px solid var(--t1);border-radius:4px 16px 16px 16px;padding:12px 14px;display:flex;flex-direction:column;transform:rotate(-.6deg)}.c-note b{font:500 17px "Iowan Old Style",Georgia,serif}.c-note span{color:var(--t2);font-size:12.5px;font-style:italic}
.c-hdr{display:flex;align-items:center;gap:14px;padding:12px 16px}.c-hdr b{flex:1;font:500 21px "Iowan Old Style",Georgia,serif}
.c-thread{display:flex;flex-direction:column;gap:12px}
.c-me{align-self:flex-end;max-width:84%;background:var(--t1);color:var(--bg);border-radius:18px 18px 4px 18px;padding:12px 14px}
.c-ticket{display:flex;background:var(--card);border:1.5px solid var(--t1);border-radius:14px;overflow:hidden}
.c-stub{width:70px;flex:none;background:var(--ac);color:#fff8f0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;border-right:2px dashed var(--bg);font:600 14px "Iowan Old Style",Georgia,serif}.c-stub.s2{background:var(--ol)}
.dirs.dark .c-stub{color:#1e1915}
.c-tb{flex:1;padding:10px 12px;display:flex;flex-direction:column;position:relative}.c-tb span{color:var(--t2);font-size:12.5px}.c-tb b{font-size:15.5px}
.c-tb em{position:absolute;right:12px;top:50%;margin-top:-10px;font-style:normal;color:var(--acf);font-weight:600;font-size:13px;text-decoration:underline;text-underline-offset:3px}
.c-ai{align-self:flex-start;max-width:86%;font:500 20px/1.3 "Iowan Old Style",Palatino,Georgia,serif}.c-ai span{display:block;font:italic 12px system-ui,sans-serif;color:var(--t2);margin-top:4px}
.c-prompt{background:var(--card);border:1.5px solid var(--t1);border-radius:18px;padding:16px;margin-top:6px}.c-prompt>span{font-style:italic;color:var(--ac);font-size:13px}
.c-prompt b{display:block;font:500 22px/1.2 "Iowan Old Style",Georgia,serif;margin:4px 0 12px}
.c-pa{display:flex;gap:8px}.c-pa span{display:flex;gap:6px;align-items:center;border:1.5px solid var(--t1);border-radius:12px;padding:7px 12px;font-size:13.5px;font-weight:500}.c-pa span:first-child{background:var(--ac);border-color:var(--ac);color:#fff8f0}.dirs.dark .c-pa span:first-child{color:#1e1915}
.c-entry{display:flex;gap:14px;padding:12px 0;border-bottom:1.5px dashed var(--ln)}.c-entry p{margin:0;font:400 16px/1.4 "Iowan Old Style",Palatino,Georgia,serif}.c-entry p span{display:block;font:italic 12px system-ui,sans-serif;color:var(--t2);margin-top:4px}
.c-stamp{width:52px;height:52px;flex:none;border:2px solid var(--ac);border-radius:26px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:var(--ac);transform:rotate(-8deg);line-height:1}
.c-stamp b{font:600 19px "Iowan Old Style",Georgia,serif}.c-stamp span{font-size:10px;text-transform:uppercase;letter-spacing:.08em}.c-stamp.s2{border-color:var(--ol);color:var(--ol);transform:rotate(5deg)}.c-stamp.s3{border-color:var(--oc);color:var(--oct);transform:rotate(-3deg)}
`;
