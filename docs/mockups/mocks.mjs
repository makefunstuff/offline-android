// Phone-screen mock-ups for the LFM3 on Pixel spec. Flat: no gradients, glows or shadows.

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
const nav = (a) => `<div class="nav">${[
  ["sun", "Today"], ["chat", "Chat"], ["book", "Library"], ["todo", "To-dos"],
].map(([ic, l]) => `<div class="ni ${l === a ? "on" : ""}"><span>${i(ic)}</span>${l}</div>`).join("")}</div>`;
const hdr = (t, right = "", left = i("back")) => `<div class="hdr">${left}<b>${t}</b>${right}</div>`;
const composer = (ph = "Ask, capture or do…") => `<div class="comp"><span class="cph">${ph}</span>${i("camera", "m")}<span class="micb">${i("mic")}</span></div>`;
const screen = (n, title, body) =>
  `<figure><div class="ph"><div class="scr">${body}</div></div><figcaption><b>${n}</b> ${title}</figcaption></figure>`;

const today = screen(1, "Today", `${sb()}
<div class="app">
  <div class="top"><div><div class="sub">Friday 30 October</div><div class="h1">Good afternoon</div></div><span class="av">${i("user")}</span></div>
  <div class="pad">
    ${composer()}
    <div class="chips"><span class="chip ok">${i("shield", "s")}On this phone</span><span class="chip">${i("globe", "s")}Search: ask first</span></div>
    <div class="sec">Today</div>
    <div class="list">
      <div class="li">${i("cal", "ac")}<div><b>Design review</b><span>15:30 – 16:15 · Room 4</span></div></div>
      <div class="li">${i("bell", "ac")}<div><b>Call the plumber</b><span>Reminder · 18:00</span></div></div>
      <div class="li">${i("todo", "ac")}<div><b>3 to-dos due</b><span>Oat milk, renew passport, send invoice</span></div>${i("right", "dim")}</div>
    </div>
    <div class="sec">Just captured</div>
    <div class="list">
      <div class="li">${i("bulb", "dim")}<div><b>Weekend market stall for old prints</b><span>Idea · 11:20 · voice</span></div></div>
      <div class="li">${i("book", "dim")}<div><b>Felt calmer after the walk</b><span>Journal · 08:05</span></div></div>
    </div>
  </div>
</div>${nav("Today")}${gb}`);

const chat = screen(2, "Chat", `${sb()}
<div class="app">
  ${hdr("Assistant", i("more"), i("list"))}
  <div class="pad thread">
    <div class="day">Today</div>
    <div class="me voice">${i("wave", "s")}<span>0:06</span> Remind me to call the plumber at six, and add oat milk to my list</div>
    <div class="tool">${i("bell", "ac")}<div><b>Reminder set</b><span>Call the plumber · today 18:00</span></div><span class="undo">Undo</span></div>
    <div class="tool">${i("todo", "ac")}<div><b>Added to Shopping</b><span>Oat milk</span></div><span class="undo">Undo</span></div>
    <div class="me">What does a plumber in Leeds usually charge for a call-out?</div>
    <div class="ai">I'd need the web for current prices. <span class="lnk">See search below</span>.<div class="meta">LFM3-1.7B · on this phone</div></div>
    <div class="q">${i("globe", "s")}<div><span>Search the web for</span><b>plumber call-out fee Leeds 2026</b></div><span class="qb">Edit</span><span class="qb pri">Send</span></div>
  </div>
  ${composer("Message…")}
</div>${gb}`);

const search = screen(3, "Search results", `${sb()}
<div class="app">
  ${hdr("Search", i("more"))}
  <div class="pad">
    <div class="sent">${i("globe", "s")}<div><span>Sent to your SearXNG · 14:08</span><b>plumber call-out fee Leeds 2026</b></div>${i("edit", "s dim")}</div>
    <div class="ans"><p>Most Leeds plumbers charge a <b>£60–£95 call-out</b> that covers the first hour <sup>1</sup> <sup>2</sup>. Evenings and weekends often cost 1.5× <sup>3</sup>. Ask for a fixed quote before work starts.</p>
    <div class="meta">Answer written on this phone from 3 pages</div></div>
    <div class="sec">Sources</div>
    <div class="src"><span class="n">1</span><div><b>Plumbing prices in Leeds, 2026 guide</b><span>checkatrade.com</span></div></div>
    <div class="src"><span class="n">2</span><div><b>How much does a plumber cost?</b><span>which.co.uk</span></div></div>
    <div class="src"><span class="n">3</span><div><b>Emergency plumber rates explained</b><span>mybuilder.com</span></div></div>
    <div class="note">${i("shield", "s ok")}Sent: the search words, then the 3 result pages were opened. Nothing from your notes, contacts or chats.</div>
  </div>
</div>${gb}`);

const confirm = screen(4, "Tool confirmation", `${sb()}
<div class="app">
  ${hdr("Assistant", i("more"), i("list"))}
  <div class="pad thread faded">
    <div class="me">Dinner with Ana next Friday at 8, at Ottolenghi</div>
    <div class="ai">I'll add it to your calendar.</div>
  </div>
  <div class="scrim"></div>
  <div class="sheet">
    <div class="grab"></div>
    <div class="sh">${i("cal", "ac")}<b>Add to calendar?</b></div>
    <div class="fld"><span>Title</span><b>Dinner with Ana</b></div>
    <div class="fld"><span>When</span><b>Fri 6 Nov · 20:00 – 22:00 (2 h)</b></div>
    <div class="fld"><span>Where</span><b>Ottolenghi</b></div>
    <div class="fld"><span>Calendar</span><b>On this phone</b></div>
    <div class="btns"><span class="btn">Edit</span><span class="btn">Cancel</span><span class="btn pri">Add</span></div>
    <div class="fine">Calendar changes always ask first. Change in Settings → Tools.</div>
  </div>
</div>${gb}`);

const libTabs = (on) => `<div class="tabs">${["Notes", "Ideas", "Journal"].map((t) => `<span class="${t === on ? "on" : ""}">${t}</span>`).join("")}</div>`;
const libTop = (on) => `<div class="top"><div class="h1">Library</div><span class="ib">${i("search")}</span></div><div class="pad">${libTabs(on)}`;

const notes = screen(5, "Notes", `${sb()}
<div class="app">
  ${libTop("Notes")}
    <div class="nl">
      <div class="nr"><div class="nh"><b>Standup, Thursday</b><span>Thu</span></div><p>Release moves to Monday; payment test fails on older phones. Review on Friday.</p><div class="tg"><span>${i("wave", "s")}4:12</span><span>work</span><span>3 to-dos</span></div></div>
      <div class="nr"><div class="nh"><b>Boiler service</b><span>Tue</span></div><p>Model Vaillant ecoTEC 832. Last service March. Pressure drops below 1 bar overnight.</p><div class="tg"><span>home</span></div></div>
      <div class="nr"><div class="nh"><b>Lease, pages 1–6</b><span>Mon</span></div><p>12 months from 1 Dec, rent 1,450. Clause 9: tenant pays repairs under 200.</p><div class="tg"><span>${i("image", "s")}6 pages</span><span>home</span></div></div>
      <div class="nr"><div class="nh"><b>Book list from Mia</b><span>Sun</span></div><p>Piranesi; The Overstory; Tomorrow, and Tomorrow, and Tomorrow.</p><div class="tg"><span>reading</span></div></div>
    </div>
  </div>
  <span class="fab">${i("plus")}</span>
</div>${nav("Library")}${gb}`);

const ideas = screen(6, "Ideas", `${sb()}
<div class="app">
  ${libTop("Ideas")}
    <div class="sec">Side projects</div>
    <div class="ig">
      <div class="ic"><b>Weekend market stall for old prints</b><span>Today · voice</span></div>
      <div class="ic"><b>Map of every bakery I've tried</b><span>Oct 21</span></div>
    </div>
    <div class="sec">Home</div>
    <div class="ig">
      <div class="ic"><b>Shelf above the radiator</b><span>Oct 27</span></div>
      <div class="ic"><b>Herb box on the balcony</b><span>Oct 12</span></div>
    </div>
    <div class="sec">Writing</div>
    <div class="ig"><div class="ic wide"><b>Essay: why small models feel more personal</b><span>Oct 29 · 3 linked notes</span><em>Expand this idea</em></div></div>
  </div>
  <span class="fab">${i("plus")}</span>
</div>${nav("Library")}${gb}`);

const journal = screen(7, "Journal", `${sb()}
<div class="app">
  ${libTop("Journal")}
    <div class="prompt"><span>Today's prompt</span><b>What gave you energy this week?</b><div class="pa"><span class="btn sm">${i("mic", "s")}Speak</span><span class="btn sm">${i("edit", "s")}Write</span></div></div>
    <div class="jd">Friday 30</div>
    <div class="je"><span class="mood m1"></span><div><p>Slept well. Felt calmer after the walk; less worried about the review.</p><span>08:05 · written</span></div></div>
    <div class="jd">Thursday 29</div>
    <div class="je"><span class="mood m2"></span><div><p>Long day. Want to say no to the Saturday shift.</p><span>22:40 · voice 1:10</span></div></div>
    <div class="je"><span class="mood m1"></span><div><p>Lunch with Mia, laughed a lot.</p><span>13:15 · written</span></div></div>
    <div class="fine c">${i("lock", "s")}Journal is locked with your fingerprint</div>
  </div>
</div>${nav("Library")}${gb}`);

const todos = screen(8, "To-dos", `${sb()}
<div class="app">
  <div class="top"><div class="h1">To-dos</div><span class="ib">${i("more")}</span></div>
  <div class="pad">
    <div class="tabs"><span class="on">Today</span><span>Upcoming</span><span>Someday</span></div>
    <div class="td"><span class="cb"></span><div><b>Buy oat milk</b><span>Shopping · from voice, 14:05</span></div></div>
    <div class="td"><span class="cb"></span><div><b>Renew passport</b><span>Due today · Personal</span></div></div>
    <div class="td"><span class="cb"></span><div><b>Send invoice to Studio K</b><span>Due today · Work</span></div></div>
    <div class="td"><span class="cb"></span><div><b>Fix payment test on Android 14</b><span>Work · from Standup note</span></div></div>
    <div class="sec">Done</div>
    <div class="td done"><span class="cb on">${i("check")}</span><div><b>Book Friday review</b><span>Work</span></div></div>
    <div class="td done"><span class="cb on">${i("check")}</span><div><b>Water the herbs</b><span>Home</span></div></div>
  </div>
  <span class="fab">${i("plus")}</span>
</div>${nav("To-dos")}${gb}`);

const camera = screen(9, "Ask about this", `<div class="cam">${sb()}
  <div class="vf">
    <div class="plant"><i class="l1"></i><i class="l2"></i><i class="l3"></i><i class="pot"></i></div>
    <div class="tb">${i("x")}<span class="seg"><span class="on">Photo</span><span>Video</span></span>${i("flash")}</div>
  </div>
  <div class="cq">${i("mic", "s")}<span>What plant is this, and does it need more light?</span></div>
  <div class="cb2"><span class="thumbb">${i("image")}</span><span class="shut"></span><span class="thumbb">${i("flip")}</span></div>
${gb}</div>`);

const talk = screen(10, "Talk", `${sb()}
<div class="app">
  ${hdr("Talk", '<span class="lang">English</span>', i("x"))}
  <div class="pad talk">
    <div class="orb"><div class="bars"><i style="height:22px"></i><i style="height:46px"></i><i style="height:70px"></i><i style="height:40px"></i><i style="height:58px"></i><i style="height:28px"></i><i style="height:18px"></i></div></div>
    <div class="state">Speaking</div>
    <div class="tl"><span>You</span>How long should I boil an egg for a runny yolk?</div>
    <div class="tl ai2"><span>LFM</span>About six minutes from boiling, then straight into cold water.</div>
    <div class="warm">${i("warn", "s")}Phone is warm, answers may be slower</div>
  </div>
  <div class="tctl"><span class="ib2">${i("speaker")}</span><span class="stopb">${i("stop")}</span><span class="ib2">${i("chat")}</span></div>
</div>${gb}`);

const settings = screen(11, "Settings", `${sb()}
<div class="app">
  ${hdr("Settings")}
  <div class="pad">
    <div class="grp">
      <div class="sr">${i("chip", "ac")}<div><b>Models</b><span>Omni-2B, LFM3-1.7B in memory · 2.5 GB</span></div>${i("right", "dim")}</div>
      <div class="sr">${i("globe", "ac")}<div><b>Web search</b><span>Your SearXNG + Wikipedia · ask first</span></div>${i("right", "dim")}</div>
      <div class="sr">${i("sliders", "ac")}<div><b>Tools</b><span>6 run directly · 6 always ask</span></div>${i("right", "dim")}</div>
    </div>
    <div class="grp">
      <div class="sr">${i("shield", "ac")}<div><b>Privacy</b><span>Network log, export, delete</span></div>${i("right", "dim")}</div>
      <div class="sr">${i("lock", "ac")}<div><b>Lock</b><span>Journal only</span></div>${i("right", "dim")}</div>
    </div>
    <div class="grp">
      <div class="sr">${i("speaker", "ac")}<div><b>Voice</b><span>Female · English (UK)</span></div>${i("right", "dim")}</div>
      <div class="sr">${i("flash", "ac")}<div><b>Battery &amp; heat</b><span>Slow down when warm</span></div>${i("right", "dim")}</div>
    </div>
    <div class="sec">Web search</div>
    <div class="grp">
      <div class="seg3"><span>Off</span><span class="on">Ask first</span><span>Automatic</span></div>
      <div class="tg2"><div><b>Warn about personal words</b><span>Names, places, numbers, e-mails</span></div><span class="sw on"><i></i></span></div>
    </div>
  </div>
</div>${gb}`);

const privacy = screen(12, "Privacy and network log", `${sb()}
<div class="app">
  ${hdr("Privacy")}
  <div class="pad">
    <div class="big">Your data stays on this phone.</div>
    <div class="pr">${i("check", "ok")}<div><b>Assistant has no network access</b><span>Android blocks it: no internet permission</span></div></div>
    <div class="pr">${i("check", "ok")}<div><b>No account, no telemetry</b><span>Nothing reports back to anyone</span></div></div>
    <div class="pr">${i("globe", "ac")}<div><b>LFM Net handles search</b><span>Sends only search words, result addresses and model downloads</span></div><span class="sw on"><i></i></span></div>
    <div class="sec">Network log · today</div>
    <div class="nlg"><span>14:08</span><div><b>searxng.home.arpa</b><span>“plumber call-out fee Leeds 2026”</span></div><em>4 KB</em></div>
    <div class="nlg"><span>14:08</span><div><b>checkatrade.com</b><span>Opened source 1</span></div><em>38 KB</em></div>
    <div class="nlg"><span>14:08</span><div><b>which.co.uk</b><span>Opened source 2</span></div><em>51 KB</em></div>
    <div class="nlg"><span>14:08</span><div><b>mybuilder.com</b><span>Opened source 3</span></div><em>44 KB</em></div>
    <div class="nlg"><span>13:58</span><div><b>huggingface.co</b><span>Model: LFM3-VL-2B (downloading)</span></div><em>0.8 GB</em></div>
    <div class="btns"><span class="btn">Export…</span><span class="btn red">Delete all</span></div>
  </div>
</div>${gb}`);

const models = screen(13, "Models and import", `${sb()}
<div class="app">
  ${hdr("Models", i("more"))}
  <div class="pad">
    <div class="stor"><div class="bar"><span style="width:2%"></span></div><span>3.3 GB used · 198 GB free</span></div>
    <div class="md on"><div class="mh"><b>LFM3-Omni-2B</b><span class="pill">In memory</span></div><span>Q4_K_M · 1.4 GB · ${i("check", "s ok")}verified</span><span class="for">Voice, camera, talk</span></div>
    <div class="md on"><div class="mh"><b>LFM3-1.7B</b><span class="pill">In memory</span></div><span>Q4_0 · 1.05 GB · ${i("check", "s ok")}verified</span><span class="for">Chat, tools, writing</span></div>
    <div class="md"><div class="mh"><b>LFM3-VL-2B</b><span class="pct">64%</span></div><span>Downloading through LFM Net · 0.8 of 1.25 GB</span><div class="bar"><span style="width:64%"></span></div><span class="for">Documents · checked against SHA-256 when done</span></div>
    <div class="btns"><span class="btn">${i("folder", "s")}Import a file</span><span class="btn pri">${i("download", "s")}Download</span></div>
    <div class="fine">Downloads come from huggingface.co with no login. Import works fully offline.</div>
  </div>
</div>${gb}`);

export const html = `<h2 id="mockups">Mock-ups</h2>
<p class="cap">Pixel 10 Pro proportions (20:9; frames 360×800). Flat: no gradients, glows or shadows. Numbers and names are illustrative.</p>
<div class="themes" id="themes13" role="group"><button data-t="dark">Dark</button><button data-t="light">Light</button></div>
<div class="phones" id="phones">
${[today, chat, search, confirm, notes, ideas, journal, todos, camera, talk, settings, privacy, models].join("\n")}
</div>`;

export const script = `(function(){var p=document.getElementById('phones'),b=document.querySelectorAll('#themes13 button');
function set(t){p.className='phones '+t;b.forEach(function(x){x.className=x.dataset.t===t?'on':''})}
b.forEach(function(x){x.addEventListener('click',function(){set(x.dataset.t)})});
set(document.documentElement.classList.contains('shepherdr-light')?'light':'dark');})();`;

export const css = `
.themes{display:flex;gap:6px;margin:8px 0 4px}
.themes button{font:inherit;font-size:13px;padding:6px 14px;border-radius:16px;border:1px solid var(--line,#d4d4d8);background:none;color:var(--fg,#18181b);cursor:pointer}
.themes button.on{background:var(--fg,#18181b);color:var(--bg,#ffffff);border-color:var(--fg,#18181b)}
.phones{display:flex;flex-wrap:wrap;gap:22px 18px;justify-content:center;margin:12px 0;
 --bg:#0e0f11;--s1:#18191c;--s2:#222327;--ln:#2a2b30;--t1:#ececee;--t2:#a2a3aa;--t3:#8a8b92;--ac:#7aa7ff;--acb:#1c2638;--act:#0e0f11;--ok:#5fd38d;--okb:#16291f;--red:#ff7b72;--amb:#f2c46d;--ambb:#2e2614}
.phones.light{--bg:#f7f7f8;--s1:#ffffff;--s2:#eeeff1;--ln:#e2e3e7;--t1:#121316;--t2:#5d5f66;--t3:#6b6d74;--ac:#2a5fd4;--acb:#e5edfc;--act:#ffffff;--ok:#1d8048;--okb:#e2f3e8;--red:#c4382d;--amb:#8a5c00;--ambb:#fbf1dc}
figure{margin:0}figcaption{text-align:center;color:var(--fg2,#6b6d74);font-size:13px;margin-top:8px}figcaption b{color:var(--fg,#18181b);font-weight:600;margin-right:4px}
.ph{width:360px;height:800px;border:7px solid #1d1e21;border-radius:42px;overflow:hidden;background:var(--bg,#ffffff);color:var(--t1)}
.scr{position:relative;height:100%;display:flex;flex-direction:column;font:14px/1.4 Roboto,"Google Sans",system-ui,-apple-system,sans-serif;-webkit-font-smoothing:antialiased}
.scr b{font-weight:600}
.i{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round;flex:none}
.i.s{width:16px;height:16px}.i.m{width:20px;height:20px;color:var(--t2)}.i.ac{color:var(--ac)}.i.dim{color:var(--t3)}.i.ok{color:var(--ok)}
.sb{height:34px;display:flex;align-items:center;justify-content:space-between;padding:0 22px;font-size:13px;font-weight:500;position:relative;flex:none}
.hole{position:absolute;left:50%;top:9px;width:14px;height:14px;margin-left:-7px;border-radius:50%;background:#000}
.sbi{display:flex;align-items:center;gap:5px}.sbi svg{height:11px;width:auto}.sbi b{font-weight:500;font-size:12px}
.gb{height:22px;display:flex;justify-content:center;align-items:center;flex:none}.gb span{width:108px;height:4px;border-radius:2px;background:var(--t1);opacity:.85}
.app{flex:1;display:flex;flex-direction:column;overflow:hidden;position:relative}
.pad{padding:0 16px;flex:1;overflow:hidden}
.top{display:flex;justify-content:space-between;align-items:flex-end;padding:12px 16px 14px}
.h1{font-size:26px;font-weight:500;letter-spacing:-.01em}.sub{color:var(--t2);font-size:13px;margin-bottom:2px}
.av,.ib{width:40px;height:40px;border-radius:50%;background:var(--s2);display:flex;align-items:center;justify-content:center;color:var(--t2)}
.hdr{display:flex;align-items:center;gap:14px;padding:10px 16px;height:36px}.hdr b{flex:1;font-size:18px;font-weight:500}.hdr .i{color:var(--t2)}
.comp{display:flex;align-items:center;gap:12px;background:var(--s1);border:1px solid var(--ln);border-radius:28px;height:52px;padding:0 6px 0 18px;margin:0 0 12px}
.app>.comp{margin:8px 12px 10px}
.cph{flex:1;color:var(--t3);font-size:15px}.micb{width:40px;height:40px;border-radius:50%;background:var(--ac);color:var(--act);display:flex;align-items:center;justify-content:center}
.chips{display:flex;gap:8px;flex-wrap:wrap}.chip{display:inline-flex;align-items:center;gap:6px;font-size:12px;padding:5px 10px;border-radius:14px;background:var(--s2);color:var(--t2)}
.chip.ok{background:var(--okb);color:var(--ok)}
.sec{color:var(--t2);font-size:12px;font-weight:500;letter-spacing:.04em;text-transform:uppercase;margin:20px 0 8px}
.list{background:var(--s1);border-radius:18px;border:1px solid var(--ln)}
.li{display:flex;align-items:center;gap:14px;padding:12px 14px;border-top:1px solid var(--ln)}.li:first-child{border-top:0}
.li div{flex:1;display:flex;flex-direction:column;min-width:0}.li span{color:var(--t2);font-size:12.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.nav{display:flex;height:72px;border-top:1px solid var(--ln);background:var(--bg,#ffffff);flex:none}
.ni{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;font-size:12px;color:var(--t2)}
.ni span{width:56px;height:30px;border-radius:15px;display:flex;align-items:center;justify-content:center}
.ni.on{color:var(--t1);font-weight:600}.ni.on span{background:var(--acb);color:var(--ac)}
.thread{display:flex;flex-direction:column;gap:10px}.day{align-self:center;color:var(--t3);font-size:12px;margin:2px 0}
.me{align-self:flex-end;max-width:82%;background:var(--ac);color:var(--act);border-radius:20px 20px 6px 20px;padding:10px 14px}
.me.voice .i{vertical-align:-3px;margin-right:4px}.me.voice span{font-size:12px;opacity:.8;margin-right:6px}
.ai{align-self:flex-start;max-width:86%;background:var(--s1);border:1px solid var(--ln);border-radius:20px 20px 20px 6px;padding:10px 14px}
.meta{color:var(--t3);font-size:11.5px;margin-top:6px}.lnk{color:var(--ac)}
.tool{display:flex;align-items:center;gap:12px;background:var(--s1);border:1px solid var(--ln);border-radius:16px;padding:10px 12px;max-width:92%}
.tool div{flex:1;display:flex;flex-direction:column}.tool span{color:var(--t2);font-size:12.5px}.tool .undo{color:var(--ac);font-weight:500;font-size:13px}
.q{display:flex;align-items:center;gap:10px;border:1px solid var(--ac);border-radius:16px;padding:10px 12px;background:var(--acb)}
.q .i{color:var(--ac)}.q div{flex:1;display:flex;flex-direction:column}.q span{font-size:12px;color:var(--t2)}
.qb{font-size:13px;font-weight:500;color:var(--ac);padding:6px 8px}.qb.pri{background:var(--ac);color:var(--act);border-radius:14px;padding:6px 12px}
.sent{display:flex;align-items:center;gap:10px;background:var(--s2);border-radius:14px;padding:10px 12px;margin:4px 0 14px}
.sent div{flex:1;display:flex;flex-direction:column}.sent span{font-size:12px;color:var(--t2)}
.ans p{margin:0;font-size:15.5px;line-height:1.5}.ans sup{display:inline-block;min-width:16px;height:16px;line-height:16px;text-align:center;border-radius:8px;background:var(--acb);color:var(--ac);font-size:10px;font-weight:600;vertical-align:2px}
.src{display:flex;gap:12px;align-items:center;padding:10px 0;border-top:1px solid var(--ln)}.src div{display:flex;flex-direction:column}.src span{color:var(--t2);font-size:12.5px}
.n{width:24px;height:24px;border-radius:12px;background:var(--s2);display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:600;flex:none}
.note{display:flex;gap:8px;align-items:flex-start;color:var(--t2);font-size:12.5px;margin-top:14px;background:var(--okb);padding:10px 12px;border-radius:12px}
.faded{opacity:.9}.scrim{position:absolute;inset:0;background:#000;opacity:.45}
.sheet{position:absolute;left:0;right:0;bottom:0;background:var(--s1);border-radius:28px 28px 0 0;padding:8px 20px 18px}
.grab{width:36px;height:4px;border-radius:2px;background:var(--t3);margin:4px auto 14px}
.sh{display:flex;align-items:center;gap:10px;font-size:19px;margin-bottom:10px}
.fld{display:flex;justify-content:space-between;padding:11px 0;border-bottom:1px solid var(--ln)}.fld span{color:var(--t2)}
.btns{display:flex;gap:8px;margin-top:16px}.btn{flex:1;display:flex;align-items:center;justify-content:center;gap:6px;height:42px;border-radius:21px;border:1px solid var(--ln);color:var(--ac);font-weight:500}
.btn.pri{background:var(--ac);border-color:var(--ac);color:var(--act)}.btn.red{color:var(--red)}.btn.sm{height:34px;flex:none;padding:0 14px;font-size:13px}
.fine{color:var(--t3);font-size:12px;margin-top:12px}.fine.c{display:flex;gap:6px;align-items:center;justify-content:center;margin-top:18px}
.tabs{display:flex;gap:8px;margin:0 0 14px}.tabs span{padding:7px 16px;border-radius:18px;border:1px solid var(--ln);font-size:13.5px;color:var(--t2)}
.tabs .on{background:var(--t1);color:var(--bg,#ffffff);border-color:var(--t1);font-weight:500}
.nl .nr{padding:12px 0;border-bottom:1px solid var(--ln)}.nh{display:flex;justify-content:space-between}.nh span{color:var(--t3);font-size:12px}
.nr p{margin:3px 0 7px;color:var(--t2);font-size:13px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.tg{display:flex;gap:6px}.tg span{display:inline-flex;align-items:center;gap:4px;font-size:11.5px;color:var(--t2);background:var(--s2);border-radius:8px;padding:2px 8px}
.fab{position:absolute;right:16px;bottom:16px;width:56px;height:56px;border-radius:18px;background:var(--ac);color:var(--act);display:flex;align-items:center;justify-content:center}
.ig{display:grid;grid-template-columns:1fr 1fr;gap:10px}.ic{background:var(--s1);border:1px solid var(--ln);border-radius:16px;padding:12px;min-height:76px;display:flex;flex-direction:column;justify-content:space-between;gap:8px}
.ic span{color:var(--t3);font-size:12px}.ic.wide{grid-column:span 2}.ic em{font-style:normal;color:var(--ac);font-size:13px;font-weight:500}
.prompt{background:var(--acb);border-radius:18px;padding:14px}.prompt>span{color:var(--ac);font-size:12px;font-weight:500}.prompt b{display:block;font-size:17px;font-weight:500;margin:4px 0 12px}
.pa{display:flex;gap:8px}.pa .btn{background:var(--s1)}
.jd{color:var(--t2);font-size:13px;font-weight:500;margin:18px 0 6px}
.je{display:flex;gap:12px;padding:8px 0}.je p{margin:0}.je span{color:var(--t3);font-size:12px}
.mood{width:10px;height:10px;border-radius:5px;margin-top:6px;flex:none}.m1{background:var(--ok)}.m2{background:var(--amb)}
.td{display:flex;gap:14px;align-items:flex-start;padding:11px 0;border-bottom:1px solid var(--ln)}.td div{display:flex;flex-direction:column}.td span{color:var(--t2);font-size:12.5px}
.cb{width:20px;height:20px;border:2px solid var(--t3);border-radius:6px;flex:none;margin-top:1px;display:flex;align-items:center;justify-content:center}
.cb.on{background:var(--ac);border-color:var(--ac);color:var(--act)}.cb .i{width:14px;height:14px;stroke-width:2.5}
.td.done b{color:var(--t3);text-decoration:line-through;font-weight:400}
.cam{height:100%;display:flex;flex-direction:column;background:#000;color:#fff}
.vf{flex:1;position:relative;background:#46523f;margin:0 0 0;border-radius:0 0 24px 24px;overflow:hidden}
.plant{position:absolute;left:0;right:0;top:120px;height:330px}
.plant i{position:absolute;display:block;background:#6f9a57}
.l1{left:110px;top:30px;width:120px;height:140px;border-radius:0 100% 0 100%}
.l2{left:60px;top:110px;width:100px;height:110px;border-radius:100% 0 100% 0;background:#5d8749!important}
.l3{left:180px;top:120px;width:100px;height:100px;border-radius:0 100% 0 100%;background:#7aa862!important}
.pot{left:105px;top:225px;width:130px;height:110px;border-radius:0 0 26px 26px;background:#b57658!important}
.tb{position:absolute;top:10px;left:16px;right:16px;display:flex;justify-content:space-between;align-items:center}
.seg{display:flex;background:#0009;border-radius:16px;padding:3px}.seg span{padding:5px 12px;border-radius:13px;font-size:13px}.seg .on{background:#fff;color:#000}
.cq{display:flex;align-items:center;gap:10px;margin:12px 14px 0;background:#1c1d20;border-radius:22px;padding:12px 14px;font-size:14px}
.cb2{display:flex;justify-content:space-around;align-items:center;padding:18px 0 14px}
.shut{width:70px;height:70px;border-radius:50%;background:#fff;border:5px solid #000;outline:3px solid #fff}
.thumbb{width:46px;height:46px;border-radius:50%;background:#1c1d20;display:flex;align-items:center;justify-content:center}
.lang{font-size:13px;color:var(--t2);border:1px solid var(--ln);border-radius:14px;padding:4px 10px}
.talk{display:flex;flex-direction:column;align-items:center}
.orb{width:200px;height:200px;border-radius:50%;background:var(--acb);display:flex;align-items:center;justify-content:center;margin:34px 0 12px}
.bars{display:flex;gap:6px;align-items:center}.bars i{display:block;width:8px;border-radius:4px;background:var(--ac)}
.state{color:var(--ac);font-weight:500;margin-bottom:26px}
.tl{align-self:stretch;padding:10px 0;border-top:1px solid var(--ln)}.tl span{display:block;font-size:11.5px;color:var(--t3);text-transform:uppercase;letter-spacing:.05em;margin-bottom:2px}
.warm{display:flex;gap:8px;align-items:center;align-self:stretch;margin-top:12px;background:var(--ambb);color:var(--amb);border-radius:12px;padding:10px 12px;font-size:13px}
.tctl{display:flex;justify-content:center;align-items:center;gap:28px;padding:10px 0 18px}
.ib2{width:48px;height:48px;border-radius:50%;background:var(--s2);display:flex;align-items:center;justify-content:center;color:var(--t2)}
.stopb{width:68px;height:68px;border-radius:50%;background:var(--red);color:var(--act);display:flex;align-items:center;justify-content:center}
.grp{background:var(--s1);border:1px solid var(--ln);border-radius:18px;margin-bottom:10px}
.sr,.tg2{display:flex;align-items:center;gap:14px;padding:11px 14px;border-top:1px solid var(--ln)}.grp>:first-child{border-top:0}
.sr div,.tg2 div{flex:1;display:flex;flex-direction:column}.sr span,.tg2 span{color:var(--t2);font-size:12.5px}
.sw{width:42px;height:24px;border-radius:12px;background:var(--s2);position:relative;flex:none;border:1px solid var(--ln)}
.sw i{position:absolute;top:3px;left:3px;width:16px;height:16px;border-radius:8px;background:var(--t3)}.sw.on{background:var(--ac);border-color:var(--ac)}.sw.on i{left:21px;background:var(--act)}
.seg3{display:flex;margin:4px 14px 10px;border:1px solid var(--ln);border-radius:18px;overflow:hidden}.seg3 span{flex:1;text-align:center;padding:7px 0;font-size:13px;color:var(--t2);border-left:1px solid var(--ln)}
.seg3 span:first-child{border-left:0}.seg3 .on{background:var(--acb);color:var(--ac);font-weight:500}
.big{font-size:22px;font-weight:500;margin:6px 0 14px}
.pr{display:flex;gap:12px;align-items:center;padding:8px 0}.pr div{flex:1;display:flex;flex-direction:column}.pr span{color:var(--t2);font-size:12.5px}
.pr>.i{width:30px;height:30px;padding:6px;border-radius:15px;background:var(--okb);box-sizing:border-box}.pr>.i.ac{background:var(--acb)}
.nlg{display:flex;gap:12px;align-items:center;padding:9px 0;border-top:1px solid var(--ln)}.nlg>span{color:var(--t3);font-size:12px;width:36px}
.nlg div{flex:1;display:flex;flex-direction:column;min-width:0}.nlg div span{color:var(--t2);font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.nlg em{font-style:normal;color:var(--t3);font-size:12px}
.stor{margin:2px 0 14px}.stor>span{color:var(--t2);font-size:12.5px}
.bar{height:6px;border-radius:3px;background:var(--s2);margin:6px 0}.bar span{display:block;height:6px;border-radius:3px;background:var(--ac)}
.md{border:1px solid var(--ln);background:var(--s1);border-radius:18px;padding:12px 14px;margin-bottom:10px;display:flex;flex-direction:column;gap:2px}
.md.on{border-color:var(--ac)}.md>span{color:var(--t2);font-size:12.5px;display:flex;align-items:center;gap:4px}.md .for{color:var(--t3)}.md.dim b{color:var(--t2)}
.mh{display:flex;justify-content:space-between;align-items:center}.pill{font-size:11.5px;background:var(--acb);color:var(--ac);border-radius:10px;padding:2px 8px}
@media (max-width:400px){.phones figure{zoom:.92}}
.pct{color:var(--ac);font-size:13px;font-weight:500}.get{color:var(--ac)}
`;

export { i, sb, gb };
