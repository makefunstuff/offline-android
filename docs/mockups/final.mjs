// Phone-screen mock-ups for the LFM3 on Pixel spec, in the chosen style. Flat: no gradients, glows or shadows.

const P = {
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
  mic: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
  camera: '<path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  send: '<path d="M5 12h13M13 6l6 6-6 6"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  cal: '<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M4 10h16M9 3v4M15 3v4"/>',
  alarm: '<circle cx="12" cy="13" r="7"/><path d="M12 9.5V13l2.5 2M5 4L3 6M19 4l2 2"/>',
  bell: '<path d="M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20a2 2 0 0 0 4 0"/>',
  note: '<path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 12h7M9 16h5"/>',
  bulb: '<path d="M9 18h6M10 21h4"/><path d="M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z"/>',
  book: '<path d="M5 4h10a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z"/><path d="M5 17a3 3 0 0 1 3-3h10"/>',
  todo: '<path d="M10 6h10M10 12h10M10 18h10"/><path d="M3.5 6l1.5 1.5L7.5 5M3.5 12l1.5 1.5L7.5 11"/><circle cx="5" cy="18" r="1.3"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  chat: '<path d="M4 5h16v11H9l-5 4z"/>',
  sliders: '<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
  more: '<path d="M12 5h.01M12 12h.01M12 19h.01" stroke-width="3"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  shield: '<path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z"/>',
  download: '<path d="M12 4v11M7 10l5 5 5-5M5 20h14"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/>',
  phone: '<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z"/>',
  stop: '<rect x="7" y="7" width="10" height="10" rx="1.5"/>',
  speaker: '<path d="M4 9h4l5-4v14l-5-4H4z"/><path d="M17 9a4 4 0 0 1 0 6"/>',
  x: '<path d="M6 6l12 12M18 6L6 18"/>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/>',
  image: '<rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="9" cy="10" r="1.5"/><path d="M21 16l-5-5-8 8"/>',
  video: '<rect x="3" y="6" width="13" height="12" rx="2"/><path d="M16 10l5-3v10l-5-3z"/>',
  flash: '<path d="M13 3L5 14h6l-1 7 8-11h-6z"/>',
  chip: '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>',
  wave: '<path d="M3 12h2M7 8v8M11 5v14M15 9v6M19 11v2"/>',
  warn: '<path d="M12 4l9 16H3z"/><path d="M12 10v4M12 17h.01"/>',
  flip: '<path d="M4 12a8 8 0 0 1 14-5l2 2M20 12a8 8 0 0 1-14 5l-2-2M20 4v5h-5M4 20v-5h5"/>',
  pin: '<path d="M12 21s-7-6.5-7-12a7 7 0 0 1 14 0c0 5.5-7 12-7 12z"/><circle cx="12" cy="9" r="2.5"/>',
  right: '<path d="M9 6l6 6-6 6"/>',
  folder: '<path d="M3 6h6l2 2h10v11H3z"/>',
  trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
  list: '<path d="M4 6h16M4 12h16M4 18h10"/>',
};
const i = (n, c = "") => `<svg class="i ${c}" viewBox="0 0 24 24">${P[n]}</svg>`;

const sb = (dark) => `<div class="sb"><span>14:08</span><span class="hole"></span><span class="sbi">
<svg viewBox="0 0 16 12"><path d="M1 11h2V8H1zM5 11h2V6H5zM9 11h2V3H9zM13 11h2V0h-2z" fill="currentColor"/></svg>
<svg viewBox="0 0 16 12"><path d="M8 11.5L.5 4a10.6 10.6 0 0 1 15 0z" fill="currentColor"/></svg>
<svg viewBox="0 0 22 12"><rect x=".5" y=".5" width="18" height="11" rx="2.5" fill="none" stroke="currentColor"/><rect x="2" y="2" width="13" height="8" rx="1.2" fill="currentColor"/><rect x="19.5" y="4" width="1.8" height="4" rx=".8" fill="currentColor"/></svg>
<b>82%</b></span></div>`;
const gb = '<div class="gb"><span></span></div>';

// Chosen style: calm Signal (A′) with the Ink + highlighter palette.
// The yellow highlighter is used only on the mic and the one item that matters most on each screen.
const hl = (t) => `<mark class="hl">${t}</mark>`;
const mic = (c = "") => `<span class="f-mic ${c}">${i("mic")}</span>`;
const nav = (a) => `<div class="f-nav">${[["sun", "Today"], ["chat", "Chat"], ["book", "Library"], ["todo", "To-dos"]]
  .map(([ic, l]) => `<div class="f-ni ${l === a ? "on" : ""}"><span>${i(ic)}</span>${l}</div>`).join("")}</div>`;
const hdr = (t, right = "", left = i("back")) => `<div class="f-hdr">${left}<b>${t}</b>${right}</div>`;
const comp = (ph, inside = false) => `<div class="f-comp ${inside ? "in" : ""}"><span>${ph}</span>${i("camera")}${mic()}</div>`;
const libTop = (on) => `<div class="f-top"><span class="f-h2">Library</span>${i("search")}</div><div class="pad">
  <div class="f-tabs">${["Notes", "Ideas", "Journal"].map((t) => `<span class="${t === on ? "on" : ""}">${t}</span>`).join("")}</div>`;
const screen = (n, title, body, note = "") =>
  `<figure><div class="dph"><div class="scr">${body}</div></div><figcaption><b>${n}</b> ${title}${note ? `<span>${note}</span>` : ""}</figcaption></figure>`;

const today = screen(1, "Today", `${sb()}<div class="app">
  <div class="f-top"><span class="f-lab">Friday 30 October</span><span class="f-av">${i("user", "s")}</span></div>
  <div class="f-h">Good afternoon</div>
  <div class="pad">
    ${comp("Ask, capture or do…")}
    <div class="f-chips"><span>${i("shield", "s")}On this phone</span><span>${i("globe", "s")}Search: ask first</span></div>
    <div class="f-lab mt">Today · 3 things</div>
    <div class="f-row"><span class="f-t">${hl("15:30")}</span><div><b>Design review</b><span>Room 4 · 45 min</span></div><span class="f-next">Next</span></div>
    <div class="f-row"><span class="f-t">18:00</span><div><b>Call the plumber</b><span>Reminder</span></div></div>
    <div class="f-row last"><span class="f-t">3</span><div><b>To-dos due</b><span>Oat milk, passport, invoice</span></div>${i("right", "dim")}</div>
    <div class="f-lab mt">Just captured</div>
    <div class="f-card"><b>Weekend market stall for old prints</b><span>Idea · 11:20 · voice</span></div>
  </div></div>${nav("Today")}${gb}`, "yellow: mic, next item");

const chat = screen(2, "Chat", `${sb()}<div class="app">
  ${hdr("Assistant", i("more"), i("list"))}
  <div class="pad f-thread">
    <div class="f-me"><span class="f-vt">${i("wave", "s")}0:06</span>Remind me to call the plumber at six, and add oat milk to my list</div>
    <div class="f-tool"><div class="f-tk">${i("check")}</div><div><span class="f-lab">Reminder set</span><b>Call the plumber</b><span>Today 18:00</span></div><span class="f-undo">Undo</span></div>
    <div class="f-tool"><div class="f-tk">${i("plus")}</div><div><span class="f-lab">Added to Shopping</span><b>Oat milk</b></div><span class="f-undo">Undo</span></div>
    <div class="f-ai">Done. Anything else before six?<span class="f-meta">LFM3-1.7B · on this phone</span></div>
    <div class="f-me">What does a plumber in Leeds usually charge for a call-out?</div>
    <div class="f-q">${i("globe", "s")}<div><span class="f-lab">Search the web for</span><b>plumber call-out fee Leeds 2026</b></div><span class="f-btn sm">Edit</span><span class="f-btn sm pri">Send</span></div>
  </div>
  ${comp("Message…", true)}</div>${gb}`, "yellow: mic");

const search = screen(3, "Search results", `${sb()}<div class="app">
  ${hdr("Search", i("more"))}
  <div class="pad">
    <div class="f-sent">${i("globe", "s")}<div><span class="f-lab">Sent to your SearXNG · 14:08</span><b>plumber call-out fee Leeds 2026</b></div></div>
    <p class="f-ans">Most Leeds plumbers charge ${hl("£60–£95 for a call-out")}, which covers the first hour <sup>1</sup><sup>2</sup>. Evenings and weekends often cost 1.5× <sup>3</sup>. Ask for a fixed quote before work starts.</p>
    <span class="f-meta">Written on this phone from 3 pages</span>
    <div class="f-lab mt">Sources</div>
    <div class="f-src"><span class="n">1</span><div><b>Plumbing prices in Leeds, 2026 guide</b><span>checkatrade.com</span></div></div>
    <div class="f-src"><span class="n">2</span><div><b>How much does a plumber cost?</b><span>which.co.uk</span></div></div>
    <div class="f-src last"><span class="n">3</span><div><b>Emergency plumber rates explained</b><span>mybuilder.com</span></div></div>
    <div class="f-note">${i("shield", "s")}Sent: the search words, then the 3 result pages were opened. Nothing from your notes, contacts or chats.</div>
  </div></div>${gb}`, "yellow: the answer");

const sheetScreen = (n, title, warn, note) => screen(n, title, `${sb()}<div class="app">
  ${hdr("Assistant", i("more"), i("list"))}
  <div class="pad f-thread">
    ${warn ? `<div class="f-me">What's a good place for dinner near Leeds station on Friday?</div><div class="f-ai sm">Ottolenghi has tables at 20:00, says their page.<span class="f-meta">From 2 web pages</span></div>`
    : `<div class="f-me">Dinner with Ana next Friday at 8, at Ottolenghi</div><div class="f-ai sm">I'll add it to your calendar.</div>`}
  </div>
  <div class="f-scrim"></div>
  <div class="f-sheet"><div class="f-grab"></div>
    <div class="f-sh">${i("cal")}<b>Add to calendar?</b></div>
    ${warn ? `<div class="f-warn">${i("warn", "s")}<span>${hl("Suggested after reading web pages.")} Check it before adding.</span></div>` : ""}
    <div class="f-fld"><span>Title</span><b>${warn ? "Dinner at Ottolenghi" : "Dinner with Ana"}</b></div>
    <div class="f-fld"><span>When</span><b>${warn ? "Fri 6 Nov · 20:00 – 22:00" : hl("Fri 6 Nov · 20:00 – 22:00")}</b></div>
    <div class="f-fld"><span>Where</span><b>Ottolenghi</b></div>
    <div class="f-fld"><span>Calendar</span><b>On this phone</b></div>
    <div class="f-btns"><span class="f-btn">Edit</span><span class="f-btn">Cancel</span><span class="f-btn pri">Add</span></div>
    <div class="f-fine">${warn ? "Actions suggested by web text always ask, even ones set to run directly." : "Calendar changes always ask first. Change in Settings → Tools."}</div>
  </div></div>${gb}`, note);

const confirm = sheetScreen(4, "Tool confirmation", false, "yellow: the date it worked out");

const notes = screen(5, "Notes", `${sb()}<div class="app">
  ${libTop("Notes")}
    <div class="f-nr"><div class="f-nh"><b>Standup, Thursday</b><span>Thu</span></div><p>Release moves to Monday; payment test fails on older phones. Review on Friday.</p><div class="f-tg"><span>${i("wave", "s")}4:12</span><span>work</span><span>3 to-dos</span></div></div>
    <div class="f-nr"><div class="f-nh"><b>Boiler service</b><span>Tue</span></div><p>Model Vaillant ecoTEC 832. Last service March. Pressure drops below 1 bar overnight.</p><div class="f-tg"><span>home</span></div></div>
    <div class="f-nr"><div class="f-nh"><b>Lease, pages 1–6</b><span>Mon</span></div><p>12 months from 1 Dec, rent 1,450. Clause 9: tenant pays repairs under 200.</p><div class="f-tg"><span>${i("image", "s")}6 pages</span><span>home</span></div></div>
    <div class="f-nr"><div class="f-nh"><b>Book list from Mia</b><span>Sun</span></div><p>Piranesi; The Overstory; Tomorrow, and Tomorrow, and Tomorrow.</p><div class="f-tg"><span>reading</span></div></div>
  </div>${mic("fab")}</div>${nav("Library")}${gb}`, "yellow: mic");

const ideas = screen(6, "Ideas", `${sb()}<div class="app">
  ${libTop("Ideas")}
    <div class="f-lab">Side projects</div>
    <div class="f-ig"><div class="f-ic"><b>Weekend market stall for old prints</b><span>Today · voice</span></div><div class="f-ic"><b>Map of every bakery I've tried</b><span>Oct 21</span></div></div>
    <div class="f-lab mt">Home</div>
    <div class="f-ig"><div class="f-ic"><b>Shelf above the radiator</b><span>Oct 27</span></div><div class="f-ic"><b>Herb box on the balcony</b><span>Oct 12</span></div></div>
    <div class="f-lab mt">Writing</div>
    <div class="f-ic wide"><b>Essay: why small models feel more personal</b><span>Oct 29 · 3 linked notes</span><em>Expand this idea ${i("right", "s")}</em></div>
  </div>${mic("fab")}</div>${nav("Library")}${gb}`, "yellow: mic");

const journal = screen(7, "Journal", `${sb()}<div class="app">
  ${libTop("Journal")}
    <div class="f-prompt"><span class="f-lab">Today's prompt</span><b>What gave you energy this week?</b><div class="f-pa">${mic()}<span class="f-btn sm">${i("edit", "s")}Write</span><em>or tap the mic to speak</em></div></div>
    <div class="f-day"><span class="f-big">30</span><span class="f-lab">Friday</span></div>
    <div class="f-je"><span class="f-mood m1"></span><p>Slept well. Felt calmer after the walk; less worried about the review.</p></div>
    <div class="f-day"><span class="f-big">29</span><span class="f-lab">Thursday</span></div>
    <div class="f-je"><span class="f-mood m2"></span><p>Long day. Want to say no to the Saturday shift.</p></div>
    <div class="f-je"><span class="f-mood m1"></span><p>Lunch with Mia, laughed a lot.</p></div>
    <div class="f-fine c">${i("lock", "s")}Journal is locked with your fingerprint</div>
  </div></div>${nav("Library")}${gb}`, "yellow: mic");

const todos = screen(8, "To-dos", `${sb()}<div class="app">
  <div class="f-top"><span class="f-h2">To-dos</span>${i("more")}</div>
  <div class="pad">
    <div class="f-tabs"><span class="on">Today</span><span>Upcoming</span><span>Someday</span></div>
    <div class="f-td"><span class="f-cb"></span><div><b>${hl("Renew passport")}</b><span>Due today · expires in 3 weeks</span></div></div>
    <div class="f-td"><span class="f-cb"></span><div><b>Buy oat milk</b><span>Shopping · from voice, 14:05</span></div></div>
    <div class="f-td"><span class="f-cb"></span><div><b>Send invoice to Studio K</b><span>Due today · Work</span></div></div>
    <div class="f-td"><span class="f-cb"></span><div><b>Fix payment test on Android 14</b><span>Work · from Standup note</span></div></div>
    <div class="f-lab mt">Done</div>
    <div class="f-td done"><span class="f-cb on">${i("check")}</span><div><b>Book Friday review</b><span>Work</span></div></div>
    <div class="f-td done"><span class="f-cb on">${i("check")}</span><div><b>Water the herbs</b><span>Home</span></div></div>
  </div>${mic("fab")}</div>${nav("To-dos")}${gb}`, "yellow: mic, most urgent to-do");

const camera = screen(9, "Ask about this", `<div class="f-cam">${sb()}
  <div class="f-vf"><div class="plant"><i class="l1"></i><i class="l2"></i><i class="l3"></i><i class="pot"></i></div>
    <div class="f-tb">${i("x")}<span class="f-seg"><span class="on">Photo</span><span>Video</span></span>${i("flash")}</div></div>
  <div class="f-cq">${mic("sm")}<span>What plant is this, and does it need more light?</span></div>
  <div class="f-cb2"><span class="f-round">${i("image")}</span><span class="f-shut"></span><span class="f-round">${i("flip")}</span></div>
${gb}</div>`, "yellow: mic");

const talk = screen(10, "Talk", `${sb()}<div class="app">
  ${hdr("Talk", '<span class="f-pill">English</span>', i("x"))}
  <div class="pad f-talk">
    <div class="f-orb"><div class="f-bars"><i style="height:22px"></i><i style="height:46px"></i><i style="height:70px"></i><i style="height:40px"></i><i style="height:58px"></i><i style="height:28px"></i><i style="height:18px"></i></div></div>
    <div class="f-state">Speaking</div>
    <div class="f-tl"><span class="f-lab">You</span>How long should I boil an egg for a runny yolk?</div>
    <div class="f-tl"><span class="f-lab">LFM</span>About six minutes from boiling, then straight into cold water.</div>
    <div class="f-warm">${i("warn", "s")}Phone is warm, answers may be slower</div>
  </div>
  <div class="f-tctl"><span class="f-round">${i("speaker")}</span><span class="f-stop">${i("stop")}</span><span class="f-round">${i("chat")}</span></div></div>${gb}`, "yellow: the live voice");

const sr = (ic, t, d) => `<div class="f-sr">${i(ic)}<div><b>${t}</b><span>${d}</span></div>${i("right", "dim")}</div>`;
const settings = screen(11, "Settings", `${sb()}<div class="app">
  ${hdr("Settings")}
  <div class="pad">
    <div class="f-grp">${sr("chip", "Models", "Omni-2B, LFM3-1.7B in memory · 2.5 GB")}${sr("globe", "Web search", "Your SearXNG + Wikipedia · ask first")}${sr("sliders", "Tools", "6 run directly · 6 always ask")}</div>
    <div class="f-grp">${sr("shield", "Privacy", "Network log, export, delete")}${sr("lock", "Lock", "Journal only")}</div>
    <div class="f-grp">${sr("speaker", "Voice", "Female · English (UK)")}${sr("flash", "Battery & heat", "Slow down when warm")}</div>
    <div class="f-lab mt">Web search</div>
    <div class="f-seg3"><span>Off</span><span class="on">Ask first</span><span>Automatic</span></div>
    <div class="f-tg2"><div><b>Warn about personal words</b><span>Names, places, numbers, e-mails</span></div><span class="f-sw on"><i></i></span></div>
  </div></div>${gb}`, "no yellow: nothing urgent");

const privacy = screen(12, "Privacy and network log", `${sb()}<div class="app">
  ${hdr("Privacy")}
  <div class="pad">
    <div class="f-big2">Your data stays on this phone.</div>
    <div class="f-pr">${i("check")}<div><b>${hl("Assistant has no network access")}</b><span>Android blocks it: no internet permission</span></div></div>
    <div class="f-pr">${i("check")}<div><b>No account, no telemetry</b><span>Nothing reports back to anyone</span></div></div>
    <div class="f-pr">${i("globe")}<div><b>LFM Net handles search</b><span>Search words, result addresses, model downloads</span></div><span class="f-sw on"><i></i></span></div>
    <div class="f-lab mt">Network log · today</div>
    <div class="f-nlg"><span>14:08</span><div><b>searxng.home.arpa</b><span>“plumber call-out fee Leeds 2026”</span></div><em>4 KB</em></div>
    <div class="f-nlg"><span>14:08</span><div><b>checkatrade.com</b><span>Opened source 1</span></div><em>38 KB</em></div>
    <div class="f-nlg"><span>14:08</span><div><b>which.co.uk</b><span>Opened source 2</span></div><em>51 KB</em></div>
    <div class="f-nlg"><span>13:58</span><div><b>huggingface.co</b><span>Model: LFM3-VL-2B (downloading)</span></div><em>0.8 GB</em></div>
    <div class="f-btns"><span class="f-btn">Export…</span><span class="f-btn">${i("trash", "s")}Delete all</span></div>
  </div></div>${gb}`, "yellow: the key guarantee");

const models = screen(13, "Models and import", `${sb()}<div class="app">
  ${hdr("Models", i("more"))}
  <div class="pad">
    <div class="f-stor"><div class="f-bar"><span style="width:2%"></span></div><span>3.3 GB used · 198 GB free</span></div>
    <div class="f-md"><div class="f-mh"><b>LFM3-Omni-2B</b><span class="f-pill">In memory</span></div><span>Q4_K_M · 1.4 GB · ${i("check", "s")}verified</span><span class="dim">Voice, camera, talk</span></div>
    <div class="f-md"><div class="f-mh"><b>LFM3-1.7B</b><span class="f-pill">In memory</span></div><span>Q4_0 · 1.05 GB · ${i("check", "s")}verified</span><span class="dim">Chat, tools, writing</span></div>
    <div class="f-md"><div class="f-mh"><b>LFM3-VL-2B</b><span>${hl("64%")}</span></div><span>Downloading through LFM Net · 0.8 of 1.25 GB</span><div class="f-bar"><span style="width:64%"></span></div><span class="dim">Documents · checked against SHA-256 when done · Pause</span></div>
    <div class="f-btns"><span class="f-btn">${i("folder", "s")}Import a file</span><span class="f-btn pri">${i("download", "s")}Download</span></div>
    <div class="f-fine">Downloads come from Hugging Face with no login. Import works fully offline.</div>
  </div></div>${gb}`, "yellow: what is in progress");

/* ---------- States ---------- */
const firstRun = screen("S1", "First run", `${sb()}<div class="app">
  <div class="pad f-first">
    <div class="f-steps"><span class="on"></span><span></span><span></span></div>
    <div class="f-h">Your assistant,<br>on this phone</div>
    <p class="f-lead">Everything it thinks, hears and keeps stays here. No account. You decide if it may search the web.</p>
    <div class="f-lab mt">Step 1 of 3 · Models (2.5 GB)</div>
    <div class="f-opt on">${i("download")}<div><b>Download</b><span>Omni-2B and LFM3-1.7B from Hugging Face, checked on arrival</span></div><span class="f-radio on"></span></div>
    <div class="f-opt">${i("folder")}<div><b>Import files</b><span>Already on this phone or copied over USB; no network needed</span></div><span class="f-radio"></span></div>
    <div class="f-lab mt">Web search</div>
    <div class="f-seg3"><span>Off</span><span class="on">Ask first</span><span>Automatic</span></div>
    <div class="f-fine">Permissions (calendar, contacts, microphone) are asked only when a tool first needs one.</div>
  </div>
  <div class="f-cta"><span class="f-btn go wide">Continue</span></div></div>${gb}`, "yellow: the next step");

const importV = screen("S2", "Import and verify", `${sb()}<div class="app">
  ${hdr("Import model")}
  <div class="pad">
    <div class="f-file">${i("folder")}<div><b>LFM3-VL-2B-QAD-Q4_0.gguf</b><span>Downloads · 1.05 GB</span></div></div>
    <div class="f-file">${i("folder")}<div><b>mmproj-LFM3-VL-2B-Q8_0.gguf</b><span>Downloads · 203 MB</span></div></div>
    <div class="f-lab mt">Checks</div>
    <div class="f-ck">${i("check")}<div><b>Copied into the app</b><span>1.25 GB</span></div></div>
    <div class="f-ck">${i("check")}<div><b>Known model: LFM3-VL-2B, Q4_0</b><span>Matches a pinned name and size</span></div></div>
    <div class="f-ck">${i("check")}<div><b>${hl("SHA-256 verified")}</b><span>5f2c…9a71 matches the pinned hash</span></div></div>
    <div class="f-ck">${i("check")}<div><b>Loads and answers a test prompt</b><span>0.9 s</span></div></div>
    <div class="f-opt">${i("trash")}<div><b>Delete the downloaded originals?</b><span>Frees 1.25 GB in Downloads</span></div><span class="f-btn sm">Delete</span></div>
  </div>
  <div class="f-cta"><span class="f-btn pri wide">Done</span></div></div>${gb}`, "yellow: the result");

const piiWarn = screen("S3", "Search query with a personal word", `${sb()}<div class="app">
  ${hdr("Assistant", i("more"), i("list"))}
  <div class="pad f-thread">
    <div class="f-me">Is Ana Pereira's new café any good?</div>
    <div class="f-ai sm">I'd need the web for reviews.</div>
    <div class="f-qw">
      <div class="f-qh">${i("warn", "s")}<b>This search has a name from your contacts</b></div>
      <div class="f-qq"><span class="f-lab">Search the web for</span><b>${hl("Ana Pereira")} café Leeds reviews</b></div>
      <p>The provider would see this name. Search without it, or send it anyway.</p>
      <div class="f-btns"><span class="f-btn">Edit</span><span class="f-btn">Send anyway</span><span class="f-btn pri">Remove name</span></div>
    </div>
  </div>
  ${comp("Message…", true)}</div>${gb}`, "yellow: the personal word");

const webConfirm = sheetScreen("S4", "Action suggested by web text", true, "yellow: why it asks");

const SCREENS = [today, chat, search, confirm, notes, ideas, journal, todos, camera, talk, settings, privacy, models];
const STATES = [firstRun, importV, piiWarn, webConfirm];

export const html = `<h2 id="mockups">Mock-ups</h2>
<p class="cap">Style: calm Signal with the Ink + highlighter palette (section 7). Pixel 10 Pro proportions (20:9; frames 360×800). Flat: no gradients, glows or shadows. Yellow appears only on the mic and the one thing that matters most on each screen (named under each). Numbers and names are illustrative.</p>
<div class="themes" id="themesF" role="group"><button data-t="dark">Dark</button><button data-t="light">Light</button></div>
<div class="fphones" id="fphones">
${SCREENS.join("\n")}
</div>
<h3>States</h3>
<div class="fphones" id="fstates">
${STATES.join("\n")}
</div>`;

export const script = `(function(){var p=[document.getElementById('fphones'),document.getElementById('fstates')],b=document.querySelectorAll('#themesF button');
function set(t){p.forEach(function(x){x.className='fphones '+t});b.forEach(function(x){x.className=x.dataset.t===t?'on':''})}
b.forEach(function(x){x.addEventListener('click',function(){set(x.dataset.t)})});
set(document.documentElement.classList.contains('shepherdr-light')?'light':'dark');})();`;

export const css = `
.themes{display:flex;gap:6px;margin:8px 0 4px}
.themes button{font:inherit;font-size:13px;padding:6px 14px;border-radius:16px;border:1px solid var(--line,#d4d4d8);background:none;color:var(--fg,#18181b);cursor:pointer}
.themes button.on{background:var(--fg,#18181b);color:var(--bg,#fff);border-color:var(--fg,#18181b)}
.fphones{display:flex;flex-wrap:wrap;gap:26px 18px;justify-content:center;margin:12px 0;
 --bg:#ffffff;--t1:#0b0b0c;--t2:#5c5c62;--t3:#8b8b92;--ln:#e4e4e7;--s1:#f4f4f5;--ctl:#8b8b92;--hl:#ffd400;--onhl:#0b0b0c;--scrim:#0b0b0c}
.fphones.dark{--bg:#09090a;--t1:#f2f2f3;--t2:#9d9da4;--t3:#6c6c74;--ln:#27272a;--s1:#161618;--ctl:#6a6a72;--hl:#ffd400;--onhl:#09090a;--scrim:#000000}
.fphones figure{margin:0;width:374px}.fphones figcaption{text-align:center;color:var(--fg2,#6b6d74);font-size:13px;margin-top:8px}
.fphones figcaption b{color:var(--fg,#18181b);font-weight:600;margin-right:4px}.fphones figcaption span{display:block;font-size:12px;font-style:italic}
@media (max-width:400px){.fphones figure{zoom:.92}}
.dph{width:360px;height:800px;border:7px solid #1d1e21;border-radius:42px;overflow:hidden;background:var(--bg);color:var(--t1)}
.scr{position:relative;height:100%;display:flex;flex-direction:column;font:14px/1.4 Roboto,"Google Sans",system-ui,-apple-system,sans-serif;-webkit-font-smoothing:antialiased}
.scr b{font-weight:600}
.i{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round;flex:none}
.i.s{width:16px;height:16px}.i.dim{color:var(--t3)}
.sb{height:34px;display:flex;align-items:center;justify-content:space-between;padding:0 22px;font-size:13px;font-weight:500;position:relative;flex:none}
.hole{position:absolute;left:50%;top:9px;width:14px;height:14px;margin-left:-7px;border-radius:50%;background:#000}
.sbi{display:flex;align-items:center;gap:5px}.sbi svg{height:11px;width:auto}.sbi b{font-weight:500;font-size:12px}
.gb{height:22px;display:flex;justify-content:center;align-items:center;flex:none}.gb span{width:108px;height:4px;border-radius:2px;background:var(--t1);opacity:.85}
.app{flex:1;display:flex;flex-direction:column;overflow:hidden;position:relative}
.pad{padding:0 16px;flex:1;overflow:hidden}
mark.hl{background:var(--hl);color:var(--onhl);padding:0 3px;margin:0 -1px;border-radius:2px}
.f-lab{font-size:12.5px;font-weight:500;color:var(--t2)}.f-lab.mt{display:block;margin:20px 0 6px}
.f-top{display:flex;justify-content:space-between;align-items:center;padding:14px 16px 0;color:var(--t2)}
.f-av{width:34px;height:34px;border:1px solid var(--ln);border-radius:6px;display:flex;align-items:center;justify-content:center}
.f-h{font-size:32px;line-height:1.1;font-weight:600;letter-spacing:-.025em;padding:6px 16px 16px}
.f-h2{font-size:28px;font-weight:600;letter-spacing:-.02em;color:var(--t1)}
.f-comp{display:flex;align-items:center;gap:12px;border:1px solid var(--ctl);background:var(--s1);border-radius:8px;height:52px;padding:0 5px 0 14px;font-size:15px}
.f-comp>span:first-child{flex:1;color:var(--t2)}.f-comp>.i{color:var(--t2)}.f-comp.in{margin:8px 12px 10px}
.f-mic{width:42px;height:42px;flex:none;border-radius:6px;background:var(--hl);color:var(--onhl);display:flex;align-items:center;justify-content:center;box-sizing:border-box;border:1px solid var(--t1)}
.fphones.dark .f-mic{border-color:var(--hl)}
.f-mic.fab{position:absolute;right:16px;bottom:16px;width:56px;height:56px;border-radius:10px}.f-mic.sm{width:34px;height:34px}
.f-chips{display:flex;gap:8px;margin-top:10px}.f-chips span{display:inline-flex;align-items:center;gap:6px;font-size:12px;color:var(--t2);border:1px solid var(--ln);border-radius:6px;padding:4px 8px}
.f-row{display:flex;gap:14px;align-items:center;padding:11px 0;border-top:1px solid var(--ln)}.f-row.last{border-bottom:1px solid var(--ln)}
.f-row div{flex:1;display:flex;flex-direction:column}.f-row span{color:var(--t2);font-size:12.5px}.f-row b{font-size:15.5px}
.f-t{font-size:24px!important;font-weight:500;letter-spacing:-.02em;width:76px;color:var(--t1)!important;font-variant-numeric:tabular-nums}
.f-next{font-size:12px!important;font-weight:500}
.f-card{border:1px solid var(--ln);border-radius:6px;padding:12px 14px;display:flex;flex-direction:column;gap:3px}.f-card b{font-size:16px}.f-card span{color:var(--t2);font-size:12.5px}
.f-nav{display:flex;height:70px;flex:none;border-top:1px solid var(--ln)}
.f-ni{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;font-size:12px;color:var(--t2)}
.f-ni span{width:56px;height:30px;border-radius:6px;display:flex;align-items:center;justify-content:center}
.f-ni.on{color:var(--t1);font-weight:700}.f-ni.on span{background:var(--s1);border:1px solid var(--ln)}
.f-hdr{display:flex;align-items:center;gap:14px;padding:10px 16px 12px;border-bottom:1px solid var(--ln);flex:none}.f-hdr b{flex:1;font-size:18px}.f-hdr .i{color:var(--t2)}
.f-pill{font-size:12px;color:var(--t2);border:1px solid var(--ln);border-radius:6px;padding:2px 8px}
.f-thread{display:flex;flex-direction:column;gap:10px;padding-top:14px}
.f-me{align-self:flex-end;max-width:84%;background:var(--s1);border:1px solid var(--ln);border-radius:8px 8px 2px 8px;padding:10px 14px}
.f-vt{display:flex;align-items:center;gap:6px;font-size:12px;color:var(--t2);margin-bottom:4px}
.f-tool{display:flex;align-items:center;gap:12px;border:1px solid var(--ln);border-radius:8px;padding:10px 12px}.f-tool>div:nth-child(2){flex:1;display:flex;flex-direction:column}
.f-tool b{font-size:16px}.f-tool span{color:var(--t2);font-size:12.5px}
.f-tk{width:36px;height:36px;border-radius:6px;border:1px solid var(--ln);display:flex;align-items:center;justify-content:center}
.f-undo{font-size:13px!important;font-weight:600;color:var(--t1)!important;text-decoration:underline;text-underline-offset:3px;text-decoration-thickness:1px}
.f-ai{font-size:19px;font-weight:500;letter-spacing:-.01em;line-height:1.3}.f-ai.sm{font-size:16px}.f-meta{display:block;font-size:12px;font-weight:400;color:var(--t2);margin-top:4px;letter-spacing:0}
.f-q{display:flex;align-items:center;gap:8px;border:1px solid var(--ctl);border-radius:8px;padding:10px}.f-q>div{flex:1;display:flex;flex-direction:column}.f-q b{font-size:14px}
.f-btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;height:40px;padding:0 14px;border:1px solid var(--ctl);border-radius:6px;font-weight:500;font-size:14px;flex:1;box-sizing:border-box}
.f-btn.sm{height:32px;flex:none;padding:0 10px;font-size:13px}.f-btn.pri{background:var(--t1);color:var(--bg);border-color:var(--t1)}.f-btn.wide{width:100%}
.f-btn.go{background:var(--hl);color:var(--onhl);border-color:var(--t1)}.fphones.dark .f-btn.go{border-color:var(--hl)}
.f-btns{display:flex;gap:8px;margin-top:14px}
.f-sent{display:flex;align-items:center;gap:10px;border:1px solid var(--ln);border-radius:8px;padding:10px 12px;margin:14px 0 14px}.f-sent div{display:flex;flex-direction:column}
.f-ans{margin:0;font-size:16.5px;line-height:1.5}.f-ans sup{font-size:10px;font-weight:600;border:1px solid var(--ctl);border-radius:3px;padding:0 4px;margin-left:2px;vertical-align:3px}
.f-src{display:flex;gap:12px;align-items:center;padding:10px 0;border-top:1px solid var(--ln)}.f-src.last{border-bottom:1px solid var(--ln)}.f-src div{display:flex;flex-direction:column}.f-src span{color:var(--t2);font-size:12.5px}
.f-src .n{width:22px;height:22px;border:1px solid var(--ctl);border-radius:4px;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:600;color:var(--t1);flex:none}
.f-note{display:flex;gap:8px;align-items:flex-start;color:var(--t2);font-size:12.5px;margin-top:14px}
.f-scrim{position:absolute;inset:0;background:var(--scrim);opacity:.45}
.f-sheet{position:absolute;left:0;right:0;bottom:0;background:var(--bg);border-top:1px solid var(--ln);border-radius:14px 14px 0 0;padding:8px 20px 18px}
.f-grab{width:36px;height:4px;border-radius:2px;background:var(--t3);margin:4px auto 14px}
.f-sh{display:flex;align-items:center;gap:10px;font-size:19px;margin-bottom:8px}
.f-warn{display:flex;gap:8px;align-items:flex-start;font-size:13px;border:1px solid var(--ctl);border-radius:6px;padding:8px 10px;margin:4px 0 6px}
.f-fld{display:flex;justify-content:space-between;padding:11px 0;border-bottom:1px solid var(--ln)}.f-fld span{color:var(--t2)}
.f-fine{color:var(--t2);font-size:12px;margin-top:12px}.f-fine.c{display:flex;gap:6px;align-items:center;justify-content:center;margin-top:16px}
.f-tabs{display:flex;gap:6px;margin:12px 0 14px}.f-tabs span{padding:6px 14px;border-radius:6px;border:1px solid var(--ln);font-size:13.5px;color:var(--t2)}
.f-tabs .on{background:var(--t1);color:var(--bg);border-color:var(--t1);font-weight:500}
.f-nr{padding:12px 0;border-bottom:1px solid var(--ln)}.f-nh{display:flex;justify-content:space-between}.f-nh span{color:var(--t2);font-size:12px}
.f-nr p{margin:3px 0 7px;color:var(--t2);font-size:13px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.f-tg{display:flex;gap:6px}.f-tg span{display:inline-flex;align-items:center;gap:4px;font-size:11.5px;color:var(--t2);border:1px solid var(--ln);border-radius:4px;padding:1px 7px}
.f-ig{display:grid;grid-template-columns:1fr 1fr;gap:8px}.f-ic{border:1px solid var(--ln);border-radius:6px;padding:12px;min-height:76px;display:flex;flex-direction:column;justify-content:space-between;gap:8px}
.f-ic span{color:var(--t2);font-size:12px}.f-ic em{font-style:normal;font-weight:500;font-size:13px;display:flex;align-items:center;gap:2px;text-decoration:underline;text-underline-offset:3px}
.f-prompt{background:var(--s1);border:1px solid var(--ln);border-radius:8px;padding:16px}.f-prompt b{display:block;font-size:21px;line-height:1.2;letter-spacing:-.015em;margin:6px 0 14px}
.f-pa{display:flex;gap:8px;align-items:center}.f-pa .f-btn{background:var(--bg)}.f-pa em{font-style:normal;font-size:12.5px;color:var(--t2)}
.f-day{display:flex;align-items:baseline;gap:10px;margin:18px 0 4px;padding-bottom:4px;border-bottom:1px solid var(--ln)}.f-big{font-size:30px;font-weight:500;letter-spacing:-.03em;line-height:1}
.f-je{display:flex;gap:12px;padding:8px 0}.f-je p{margin:0;font-size:15px}.f-mood{width:9px;height:9px;flex:none;margin-top:6px;border-radius:2px}.f-mood.m1{background:var(--t1)}.f-mood.m2{border:1px solid var(--t2)}
.f-td{display:flex;gap:14px;align-items:flex-start;padding:11px 0;border-bottom:1px solid var(--ln)}.f-td div{display:flex;flex-direction:column}.f-td span{color:var(--t2);font-size:12.5px}
.f-cb{width:18px;height:18px;border:1px solid var(--ctl);border-radius:4px;flex:none;margin-top:1px;display:flex;align-items:center;justify-content:center}
.f-cb.on{background:var(--t1);border-color:var(--t1);color:var(--bg)}.f-cb .i{width:13px;height:13px;stroke-width:2.5}
.f-td.done b{color:var(--t3);text-decoration:line-through;font-weight:400}
.f-cam{height:100%;display:flex;flex-direction:column;background:#000;color:#fff}
.f-vf{flex:1;position:relative;background:#3d4a3a;border-radius:0 0 14px 14px;overflow:hidden}
.plant{position:absolute;left:0;right:0;top:120px;height:330px}.plant i{position:absolute;display:block;background:#6f9a57}
.l1{left:110px;top:30px;width:120px;height:140px;border-radius:0 100% 0 100%}.l2{left:60px;top:110px;width:100px;height:110px;border-radius:100% 0 100% 0;background:#5d8749!important}
.l3{left:180px;top:120px;width:100px;height:100px;border-radius:0 100% 0 100%;background:#7aa862!important}.pot{left:105px;top:225px;width:130px;height:110px;border-radius:0 0 18px 18px;background:#8c8c8c!important}
.f-tb{position:absolute;top:10px;left:16px;right:16px;display:flex;justify-content:space-between;align-items:center}
.f-seg{display:flex;background:#000a;border-radius:6px;padding:3px}.f-seg span{padding:5px 12px;border-radius:4px;font-size:13px}.f-seg .on{background:#fff;color:#000}
.f-cq{display:flex;align-items:center;gap:10px;margin:12px 14px 0;background:#161618;border:1px solid #3a3a3f;border-radius:8px;padding:8px 12px 8px 8px;font-size:14px}
.f-cam .f-mic{border-color:#ffd400}
.f-cb2{display:flex;justify-content:space-around;align-items:center;padding:18px 0 14px}
.f-shut{width:70px;height:70px;border-radius:50%;background:#fff;border:5px solid #000;outline:2px solid #fff}
.f-round{width:46px;height:46px;border-radius:8px;border:1px solid var(--ln);display:flex;align-items:center;justify-content:center;color:var(--t2)}
.f-cam .f-round{border-color:#3a3a3f;color:#fff}
.f-talk{display:flex;flex-direction:column;align-items:center}
.f-orb{width:190px;height:190px;border-radius:24px;background:var(--hl);display:flex;align-items:center;justify-content:center;margin:32px 0 12px}
.f-bars{display:flex;gap:6px;align-items:center}.f-bars i{display:block;width:8px;border-radius:2px;background:var(--onhl)}
.f-state{font-weight:600;margin-bottom:22px}
.f-tl{align-self:stretch;padding:10px 0;border-top:1px solid var(--ln)}.f-tl .f-lab{display:block;margin-bottom:2px}
.f-warm{display:flex;gap:8px;align-items:center;align-self:stretch;margin-top:12px;border:1px solid var(--ctl);border-radius:6px;padding:9px 12px;font-size:13px}
.f-tctl{display:flex;justify-content:center;align-items:center;gap:28px;padding:10px 0 18px}
.f-stop{width:64px;height:64px;border-radius:10px;background:var(--t1);color:var(--bg);display:flex;align-items:center;justify-content:center}
.f-grp{border:1px solid var(--ln);border-radius:8px;margin-top:12px}
.f-sr,.f-tg2{display:flex;align-items:center;gap:14px;padding:11px 14px;border-top:1px solid var(--ln)}.f-grp>:first-child{border-top:0}
.f-sr>.i:first-child{color:var(--t2)}.f-sr div,.f-tg2 div{flex:1;display:flex;flex-direction:column}.f-sr span,.f-tg2 span{color:var(--t2);font-size:12.5px}
.f-tg2{border:1px solid var(--ln);border-radius:8px;margin-top:8px}
.f-sw{width:40px;height:22px;border-radius:6px;border:1px solid var(--ctl);position:relative;flex:none;box-sizing:border-box}
.f-sw i{position:absolute;top:3px;left:3px;width:14px;height:14px;border-radius:3px;background:var(--t3)}.f-sw.on{background:var(--t1);border-color:var(--t1)}.f-sw.on i{left:21px;background:var(--bg)}
.f-seg3{display:flex;border:1px solid var(--ln);border-radius:6px;overflow:hidden}.f-seg3 span{flex:1;text-align:center;padding:8px 0;font-size:13px;color:var(--t2);border-left:1px solid var(--ln)}
.f-seg3 span:first-child{border-left:0}.f-seg3 .on{background:var(--t1);color:var(--bg);font-weight:500}
.f-big2{font-size:22px;font-weight:600;letter-spacing:-.015em;margin:14px 0 10px}
.f-pr{display:flex;gap:12px;align-items:center;padding:8px 0}.f-pr div{flex:1;display:flex;flex-direction:column}.f-pr span{color:var(--t2);font-size:12.5px}
.f-pr>.i:first-child{width:30px;height:30px;padding:6px;border:1px solid var(--ln);border-radius:6px;box-sizing:border-box}
.f-nlg{display:flex;gap:12px;align-items:center;padding:9px 0;border-top:1px solid var(--ln)}.f-nlg>span{color:var(--t2);font-size:12px;width:36px}
.f-nlg div{flex:1;display:flex;flex-direction:column;min-width:0}.f-nlg div span{color:var(--t2);font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.f-nlg em{font-style:normal;color:var(--t2);font-size:12px}
.f-stor{margin:14px 0 12px}.f-stor>span{color:var(--t2);font-size:12.5px}
.f-bar{height:4px;border-radius:2px;background:var(--ln);margin:6px 0}.f-bar span{display:block;height:4px;border-radius:2px;background:var(--t1)}
.f-md{border:1px solid var(--ln);border-radius:8px;padding:12px 14px;margin-bottom:8px;display:flex;flex-direction:column;gap:2px}
.f-md>span{color:var(--t2);font-size:12.5px;display:flex;align-items:center;gap:4px}.f-md .dim{color:var(--t3)}
.f-mh{display:flex;justify-content:space-between;align-items:center}
.f-first{display:flex;flex-direction:column;padding-top:18px}.f-first .f-h{padding:8px 0 10px}
.f-steps{display:flex;gap:6px}.f-steps span{width:28px;height:4px;border-radius:2px;background:var(--ln)}.f-steps .on{background:var(--t1)}
.f-lead{margin:0;color:var(--t2);font-size:15px}
.f-opt{display:flex;gap:12px;align-items:center;border:1px solid var(--ln);border-radius:8px;padding:11px 12px;margin-bottom:8px}.f-opt.on{border-color:var(--t1)}
.f-opt div{flex:1;display:flex;flex-direction:column}.f-opt span{color:var(--t2);font-size:12.5px}
.f-radio{width:18px;height:18px;border-radius:50%;border:1px solid var(--ctl);flex:none;box-sizing:border-box}.f-radio.on{border:6px solid var(--t1)}
.f-cta{padding:10px 16px 14px;flex:none}
.f-file{display:flex;gap:12px;align-items:center;padding:12px 0;border-bottom:1px solid var(--ln)}.f-file div{display:flex;flex-direction:column;min-width:0}.f-file b{font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.f-file span{color:var(--t2);font-size:12.5px}
.f-ck{display:flex;gap:12px;align-items:flex-start;padding:8px 0}.f-ck div{display:flex;flex-direction:column}.f-ck span{color:var(--t2);font-size:12.5px}
.f-qw{border:1px solid var(--t1);border-radius:8px;padding:12px}.f-qh{display:flex;gap:8px;align-items:center;font-size:14px}
.f-qq{display:flex;flex-direction:column;border-top:1px solid var(--ln);border-bottom:1px solid var(--ln);padding:8px 0;margin:10px 0}.f-qw p{margin:0;font-size:13px;color:var(--t2)}
.f-qw .f-btns{margin-top:10px}.f-qw .f-btn{font-size:12.5px;padding:0 8px;height:36px}
`;
