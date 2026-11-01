"""Writes app/src/test/resources/d1_golden.tsv: d1-omni-600M request ids from the provider's prompt.encode() for D1PromptTest.

    python -I d1_golden.py D1_REPO_DIR OUT_TSV

D1_REPO_DIR = a download of litert-community/d1-omni-600M-LiteRT (host/d1_prompt.py, host/d1_host.py, contract.json,
tokenizer.json). Lines (tab-separated; text escaped as \\t \\n \\r \\\\):
    T key temperature                                   contract.json temperatures
    Q name type instructions [option description]...    Wren's questions (Decide.kt must match)
    E tok max_len qname state ids markers               encode(tok, state, q, max_len); tok = real (tokenizer.json) or
                                                        fake (one id per character, so the rules run without the file)
    X tok max_len qname state                           encode() refuses: the options do not fit
    R qname calibrate z probs                           readout_f64 of option scores z (text mode: / temperature)
"""
import json
import random
import sys
from pathlib import Path

repo = Path(sys.argv[1])
sys.path.insert(0, str(repo / "host"))
import d1_host as H  # noqa: E402
import d1_prompt as Pm  # noqa: E402

CAPTURE_STATE = "A line the owner wrote in their notebook: "
# Kept in step with Decide.kt (D1PromptTest checks the Q lines against it).
QUESTIONS = {
    "kind": {"type": "choice", "instructions": "What kind of line did the owner write in their notebook?",
             "criteria": {"todo": "an action to do (buy, call, fix, send)",
                          "event": "happens at a set day or time (appointment, meeting, party)",
                          "idea": "a possibility or \"what if\"", "note": "a fact to keep",
                          "journal": "how they feel or what happened to them"}},
    "urgency": {"type": "choice", "instructions": "How urgent is it?",
                "criteria": {"now": "today or blocking", "soon": "this week", "later": "no hurry"}},
    "energy": {"type": "choice", "instructions": "How much energy does it take?",
               "criteria": {"low": "quick or easy", "high": "long, hard or emotionally heavy"}},
    "sensitive": {"type": "choice", "instructions": "What is it about?",
                  "criteria": {"health": "health, medicine, doctors or therapy", "money": "money, bills, bank or taxes",
                               "passwords": "passwords, PINs or logins", "legal": "legal trouble, courts or fines",
                               "intimate": "intimate matters or relationships", "everyday": "everyday things"}},
    "intent": {"type": "choice",
               "instructions": "The owner is talking to their assistant while the phone's camera is on. What do they want?",
               "criteria": {"look": "a question about what the camera sees right now (what is this, read this, how many, what colour)",
                            "act": "asks to add, remind, note, plan, set or change something", "chat": "anything else"}},
    # Not Wren's, but the other forms encode() has: a yes/no with and without its own wording, a score, bare options.
    "t_noul": {"type": "noul", "instructions": "Is it about health, money, passwords, legal trouble or intimate matters?"},
    "t_noul_words": {"type": "noul", "instructions": "Does the owner sound worried?",
                     "criteria": {"true": "they sound worried or anxious", "false": ""}},
    "t_score": {"type": "score", "instructions": "How hard is it?", "criteria": ["trivial", "easy", "some effort", "hard"]},
    "t_bare": {"type": "choice", "instructions": "Pick one.", "criteria": {"red": "", "green": None, "blue": "the sky"}},
}

STATES = [
    "buy oat milk and bananas", "dentist tuesday at 3", "what if the app had a dark mode for night writing",
    "wifi password at the cabin is pinetree42", "felt anxious all afternoon after the call with my manager",
    "pay the electricity bill today it's overdue", "Leo's wedding on 14 June", "", " ", "what am I holding",
    "  two  spaces and\ttab\nnew line ", "café naïve 東京 🙂 Привет", "a <|mask|> and <|im_end|> and <|reserved_7|> in text",
    "<|startoftext|>", "numbers 0176 5550123, 142/91, 1150€", "remind me in 10 minutes to check the oven",
    "x" * 300, "word " * 200,
]


class Fake:
    """One id per character; the delimiters are the real ids. prompt.encode() only needs these three calls."""
    bos_token_id = 1
    SPECIAL = {"<|startoftext|>": 1, "<|mask|>": 16, "<|reserved_7|>": 17, "<|reserved_8|>": 18, "<|reserved_9|>": 19,
               "<|reserved_10|>": 20, "<|reserved_11|>": 21}

    def __call__(self, text, add_special_tokens=True):
        return {"input_ids": [100 + (ord(c) % 60000) for c in text]}

    def convert_tokens_to_ids(self, token):
        return self.SPECIAL[token]


def esc(s):
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")


def ints(xs):
    return ",".join(str(x) for x in xs)


contract = json.loads((repo / "contract.json").read_text())
toks = {"real": H.Tokenizer(repo / contract["tokenizer"]["file"]), "fake": Fake()}
out = []
for k, v in contract["temperatures"].items():
    out.append(f"T\t{k}\t{v!r}")
for name, q in QUESTIONS.items():
    crit = q.get("criteria")
    if isinstance(crit, dict):
        parts = [p for k, v in crit.items() for p in (k, "" if v is None else v)]
    elif isinstance(crit, list):
        parts = crit
    else:
        parts = []
    out.append("\t".join(["Q", name, q["type"], esc(q["instructions"])] + [esc(p) for p in parts]))
for tname, tok in toks.items():
    for max_len in (128, 16384, 64):
        for st in STATES:
            for qname, qd in QUESTIONS.items():
                state = (CAPTURE_STATE + st) if qname in ("kind", "urgency", "energy", "sensitive") and st.strip() else st
                q = Pm.as_question(qd)
                try:
                    ids, markers = Pm.encode(tok, state, q, max_len)
                except ValueError:
                    out.append(f"X\t{tname}\t{max_len}\t{qname}\t{esc(state)}")
                    continue
                out.append(f"E\t{tname}\t{max_len}\t{qname}\t{esc(state)}\t{ints(ids)}\t{ints(markers)}")
rnd = random.Random(7)
for qname, qd in QUESTIONS.items():
    q = Pm.as_question(qd)
    for calibrate in (True, False):
        for _ in range(4):
            z = [round(rnd.uniform(-8, 8), 4) for _ in range(q.options)]
            p = H.readout_f64(z, 0, list(range(q.options)), q, calibrate, contract["temperatures"])
            out.append(f"R\t{qname}\t{int(calibrate)}\t{ints(z)}\t{','.join(repr(x) for x in p)}")
Path(sys.argv[2]).write_text("\n".join(out) + "\n", encoding="utf-8")
print(len(out), "lines")
