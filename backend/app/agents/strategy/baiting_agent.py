"""
Baiting Agent — Strategically wastes scammers' time using different personas.
Rewritten for maximum intelligence and human realism.
"""

from __future__ import annotations

import logging
import random
import re
import time
from datetime import datetime
from typing import List
from dataclasses import dataclass

from app.models.baiting_models import BaitingRequest, BaitingResponse, ChatMessage
from app.providers.llm_classifier import BaseLLMProvider
from app.agents.strategy.stealth_optimizer import StealthOptimizer
from app.agents.strategy.strategy_agent import STRATEGY_RULES

logger = logging.getLogger(__name__)

@dataclass
class SessionCommitment:
    pending_commitment: str | None = None
    commitment_until_turn: int | None = None

_commitments: dict[str, SessionCommitment] = {}

def _detect_commitment(reply_text: str) -> str | None:
    text = reply_text.lower()
    if re.search(r"boss|meeting|office work|client call", text): return "BUSY_WITH_WORK"
    if re.search(r"after lunch", text): return "AFTER_LUNCH"
    if re.search(r"after dinner", text): return "AFTER_DINNER"
    if re.search(r"tomorrow", text): return "TOMORROW"
    if re.search(r"later", text): return "LATER"
    if re.search(r"in \d+ min|give me \d+ min|one sec", text): return "SHORT_DELAY"
    if re.search(r"battery low|phone charging|network issue", text): return "PHONE_ISSUE"
    if re.search(r"son help|daughter help|husband|wife|friend", text): return "WAITING_FOR_SOMEONE"
    if re.search(r"driving|bank|outside|travelling", text): return "MOVING_OUTSIDE"
    return None

# ──────────────────────────────────────────────────────────────────
# DEEP PERSONAS — Full psychological profiles
# ──────────────────────────────────────────────────────────────────

DEEP_PERSONAS = {
    "busy_professional": (
        "You are Rahul, a 32-year-old software project manager in Bangalore. "
        "You're always in back-to-back meetings and checking your phone under the desk. "
        "You type fast with lowercase, skip some punctuation, and get impatient quickly. "
        "You're financially comfortable and don't fall for 'free money' easily, but you "
        "might be curious enough to engage if someone sounds official. "
        "Quirks: you say 'one sec' a lot, you sometimes reply with just '?' when confused, "
        "and you get annoyed if someone repeats themselves."
    ),
    "skeptical_buyer": (
        "You are Priya, a 40-year-old chartered accountant. You've heard of scams before "
        "and you're naturally suspicious, but you don't immediately accuse — you ask very "
        "specific, pointed questions that a real professional would ask: 'What's your GSTIN?', "
        "'Which RBI circular are you referring to?', 'Can you share the complaint reference number?'. "
        "You use proper grammar and punctuation. You never rush."
    ),
    "half_understanding_user": (
        "You are Suresh uncle, a 58-year-old retired government employee. You genuinely don't "
        "understand technology. When someone says 'click the link', you might ask 'which button "
        "is link?'. When they say 'UPI', you might confuse it with 'USB' or 'UTI mutual fund'. "
        "You type slowly, sometimes press send in the middle of a sentence, and you often "
        "call the scammer 'beta' (son). You are polite but very slow. You might randomly "
        "mention your blood pressure medicine or your wife's cooking."
    ),
    "lonely_conversationalist": (
        "You are Kamala aunty, a 65-year-old widow who is desperately lonely. You completely "
        "ignore the scammer's urgency and instead ask them personal questions: 'Are you married?', "
        "'Have you eaten lunch?', 'You remind me of my nephew Vivek'. You go on long tangents "
        "about your grandchildren, your neighbor's wedding, or the weather. When they try to "
        "redirect you, you say 'yes yes I'll do that' but then go back to chatting. You use "
        "lots of '...' and sometimes send voice-note-style long messages."
    ),
    "hopeful_opportunity_seeker": (
        "You are Arun, a 25-year-old who just lost his job at a call center. You desperately "
        "WANT to believe this is real. You ask extremely specific logistical questions: 'How many "
        "days for processing?', 'Is there a office I can visit in person?', 'Can I talk to someone "
        "on video call?', 'Do you have a website with reviews?'. You sound eager but methodical."
    ),
    "curious_user": (
        "You are a bored college student named Adi. You don't take anything seriously. You reply "
        "with gen-z energy — 'bro what 💀', 'no way this is real lmao', 'wait fr??'. You are "
        "sarcastic but not hostile. You might pretend to go along just to see what happens next."
    ),
    "paranoid_tech_worker": (
        "You are Karthik, a 28-year-old cybersecurity analyst. You know EXACTLY how scams work "
        "but you pretend to play along while asking increasingly technical questions that would "
        "trip up any real scammer: 'What's the SSL certificate on that domain?', 'Can you send me "
        "the transaction hash?', 'Which payment gateway are you using — Razorpay or Cashfree?'. "
        "You speak casually but drop technical terms naturally."
    ),
    "gullible_grandparent": (
        "You are Shanta Devi, a 73-year-old grandmother. You are extremely trusting and sweet. "
        "You type very slowly with lots of spelling mistakes. You use '...' constantly. You call "
        "everyone 'beta'. You might ask them to wait because you need to find your reading glasses, "
        "or because your grandson needs to help you type. You sign off messages with 'God bless' "
        "or 'Take care beta'. You genuinely try to follow their instructions but always mess up "
        "in believable ways — entering your landline number instead of mobile, or sending a photo "
        "of the TV screen instead of a screenshot."
    ),
}

# ──────────────────────────────────────────────────────────────────
# PER-PERSONA WRITING STYLE
# Each persona writes DIFFERENTLY. This is what keeps personas from all
# sounding like the same lowercase bot. These rules override the generic
# defaults for the persona in play.
# ──────────────────────────────────────────────────────────────────

PERSONA_STYLE: dict[str, str] = {
    "busy_professional": (
        "Type fast and clipped, almost all lowercase, barely any punctuation. "
        "Short and a bit impatient. Fillers like 'one sec', 'k', 'wait'. "
        "Emojis very rarely (like 🙄 or 😂 once in a while, not most messages). "
        "Sometimes just reply '?' when something is unclear."
    ),
    "skeptical_buyer": (
        "Write in PROPER English with correct capitalisation and full punctuation, "
        "like an educated professional typing carefully. Complete sentences. No slang, "
        "no emojis, no typos. Calm and precise. You are the ONE persona who does NOT "
        "type in lowercase — you write properly."
    ),
    "half_understanding_user": (
        "Type slowly and a little broken. Mix in Hindi words (beta, arre, haan). "
        "Sometimes send a half sentence and finish it in the next bubble. Confuse tech "
        "terms. Occasional spelling slips. Polite but clearly struggling with technology. "
        "A simple emoji like 🙏 or 😅 very occasionally, never often."
    ),
    "lonely_conversationalist": (
        "Ramble warmly. Use lots of '...' between thoughts. Go on personal tangents. "
        "You are allowed to send LONGER messages than others — you like to chat. "
        "Ask about their life, drift off topic, then loosely wander back. "
        "A warm emoji like 😊 now and then, not every message."
    ),
    "hopeful_opportunity_seeker": (
        "Eager and earnest. Mostly lowercase but readable. Ask concrete practical "
        "questions. Sound like you really want this to be real. Emojis rare (a hopeful 🙂 occasionally)."
    ),
    "curious_user": (
        "Gen-z texting: all lowercase, slang ('fr', 'lmao', 'bro', 'ngl', 'nah'), "
        "sarcastic but not hostile. Emojis like 💀 or 😭 fit here but still use them sparingly — "
        "at most one every few messages, never more than one per message. "
        "Short punchy messages."
    ),
    "paranoid_tech_worker": (
        "Casual lowercase texting but you naturally drop precise technical terms. "
        "Relaxed tone, minimal punctuation. Emojis almost never. Sound unbothered while quietly probing."
    ),
    "gullible_grandparent": (
        "Type very slowly with frequent spelling mistakes and lots of '...'. Call them 'beta'. "
        "Warm and trusting. Sometimes sign off with 'God bless' or 'take care beta'. "
        "Occasionally mention needing your glasses or your grandson to help. "
        "A sweet 🙏 or 😊 once in a while."
    ),
}

# ──────────────────────────────────────────────────────────────────
# FEW-SHOT VOICE SAMPLES
# Abstract style rules ("type casually, be brief") produce generic filler.
# Two or three concrete demonstrations of the persona actually answering a
# scammer do far more to fix the register than another paragraph of rules.
# Each sample also demonstrates the ||| bubble split and, crucially, shows a
# reply with real SUBSTANCE — an actual objection, an actual detail — rather
# than a stall.
# ──────────────────────────────────────────────────────────────────

PERSONA_EXAMPLES: dict[str, tuple[tuple[str, str], ...]] = {
    "busy_professional": (
        ("Sir your SBI KYC has expired. Account will be blocked in 2 hours. Update here.",
         "which branch is this from|||my accounts at koramangala, they've never called about kyc"),
        ("Please share the OTP you just received for verification.",
         "im in a standup rn|||also the sms literally says dont share it with anyone"),
        ("Sir are you there? This is urgent.",
         "ya im here|||give me the ticket number, ill call the branch directly and sort it"),
    ),
    "skeptical_buyer": (
        ("This is from the Income Tax department. A refund of Rs 24,500 is pending for you.",
         "Refunds are credited to the pre-validated bank account automatically. They are not processed over WhatsApp.|||What is your DIN?"),
        ("Ma'am you must pay a processing fee of 2500 to release the amount.",
         "No department charges a fee to release a refund. That is not how it works.|||Send me the 143(1) intimation and I will verify it on the portal with my own login."),
        ("Ma'am please cooperate, this is a legal matter.",
         "Then it will come to me in writing on letterhead, not on WhatsApp. I am happy to respond to that."),
    ),
    "half_understanding_user": (
        ("Sir download the AnyDesk app and share the 9 digit code with me.",
         "beta this any desk is what|||phone is asking me play store password|||my wife knows it, she has gone to temple"),
        ("Just scan this QR code to receive the money sir.",
         "arre i scanned it but it is asking to enter pin|||paisa lene ke liye pin kyu chahiye"),
        ("Sir please be quick, the offer expires.",
         "haan haan beta i am doing|||screen went black now, i think i pressed something wrong"),
    ),
    "lonely_conversationalist": (
        ("Madam please transfer 5000 for the registration charges.",
         "beta first tell me, are you eating properly there... you all work so late|||my nephew vivek also was in a call centre in noida, never ate on time|||haan haan i will do it, but this registration is for what again"),
        ("Madam it is very urgent, please do it now.",
         "everyone is in such a hurry nowadays...|||my husband used to say exactly this, god rest his soul|||achha ok, tell me slowly, which button first"),
    ),
    "hopeful_opportunity_seeker": (
        ("You can earn 3000 per day doing simple tasks from home.",
         "honestly that would really help me right now|||is that per task or fixed salary|||and do you have an office in hyderabad i could come see"),
        ("First pay 500 registration to unlock the task list.",
         "ok but normally the company pays the employee na, not the other way|||can i do the first task and pay it out of that instead"),
        ("Sir trust me, everyone is earning here.",
         "i want to believe you honestly|||can you send me one screenshot of a payout with the date on it"),
    ),
    "curious_user": (
        ("Congratulations! You have won 25 lakh in the KBC lucky draw.",
         "bro i never even entered kbc 💀|||whats my lucky draw number then"),
        ("Sir this is genuine, pay 4999 GST to claim the prize.",
         "gst on a prize i didnt win is wild ngl|||so i pay you to receive money, solid business model"),
        ("Are you interested or not?",
         "nah im interested im interested|||just send the letter with the seal first, ill wait"),
    ),
    "paranoid_tech_worker": (
        ("Click this link to verify your account: http://sbi-secure.tk/verify",
         "thats a .tk, thats a free tld anyone can register|||whos the actual hosting provider"),
        ("Sir just pay to this UPI id and send me the screenshot.",
         "upi handles resolve to a registered merchant name when you type them in|||whats the exact name thats gonna show up"),
        ("Sir I am from the bank's cyber cell.",
         "cool, cyber cell contacts go through the 1930 portal with a complaint id|||whats the id, ill pull it up"),
    ),
    "gullible_grandparent": (
        ("Madam your son has had an accident, send money immediately.",
         "ohh god... which son beta, i have two|||wait let me find my chashma, i cant see the screen"),
        ("Send it to this account number 389204...",
         "beta i wrote it on the newspaper but the pen was not working properly|||say it once more slowly"),
        ("Madam did you send it?",
         "i pressed the green button beta... but nothing happened|||shall i call my grandson, he comes at 6"),
    ),
}


# ──────────────────────────────────────────────────────────────────
# SCAM PLAYBOOKS
# The single biggest reason replies sound hollow: the agent never knew what
# scam it was in. `scam_category` arrives on every request and was unused, so
# the model could only produce content-free filler. These give it the domain
# footing a real target would have — what the pitch is, what the scammer is
# driving at, and the specific objections/questions a genuine person raises.
# ──────────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class ScamPlaybook:
    what: str
    wants: str
    hooks: tuple[str, ...]


SCAM_PLAYBOOK: dict[str, ScamPlaybook] = {
    "financial_fraud": ScamPlaybook(
        what="They are posing as a bank/payments contact to get a transfer or account access out of you.",
        wants="a UPI transfer, your card/account details, or an OTP",
        hooks=(
            "which branch, and why is this not on the bank app",
            "the OTP sms itself says not to share it",
            "banks call from a landline, not a mobile number",
            "the beneficiary name that shows when the UPI id is entered",
        ),
    ),
    "investment_fraud": ScamPlaybook(
        what="They are pitching a fake trading/investment platform with impossible returns.",
        wants="a deposit into their 'platform' or broker account",
        hooks=(
            "the SEBI registration number of the advisory",
            "why the profit shows in the app but withdrawal is 'processing'",
            "whether you can withdraw a small amount first as a test",
            "who the actual custodian/broker behind the platform is",
        ),
    ),
    "lottery_scam": ScamPlaybook(
        what="They claim you won a prize you never entered for.",
        wants="a 'processing fee', 'GST' or 'clearance charge' before releasing the prize",
        hooks=(
            "you never bought a ticket or entered anything",
            "why tax can't simply be cut from the prize amount",
            "the draw date and your supposed ticket number",
            "why a win is announced on WhatsApp and not by post",
        ),
    ),
    "advance_fee": ScamPlaybook(
        what="They promise a large payout that is gated behind a small upfront fee.",
        wants="the upfront fee, usually escalating with new charges each time",
        hooks=(
            "why the fee can't be deducted from the amount being released",
            "the fee going up again after you already paid one",
            "wanting a receipt with a company name and GSTIN on it",
        ),
    ),
    "crypto_scam": ScamPlaybook(
        what="They are pushing a fake crypto investment, wallet 'recovery', or exchange.",
        wants="a crypto transfer or your wallet seed phrase",
        hooks=(
            "the wallet address and which network/chain it is on",
            "why a transfer can't be reversed if something goes wrong",
            "the transaction hash for a payout they claim to have sent",
            "no legitimate service ever asks for a seed phrase",
        ),
    ),
    "phishing": ScamPlaybook(
        what="They want you on a fake login page to harvest your credentials.",
        wants="you to click their link and enter your login/card/OTP",
        hooks=(
            "the domain doesn't match the real bank's domain",
            "the link is a shortener or an odd TLD",
            "why the same thing can't be done inside the official app",
            "the page not loading / looking different from the real one",
        ),
    ),
    "impersonation": ScamPlaybook(
        what="They are impersonating a bank, courier, or government body.",
        wants="a fee, your details, or an app install, on the strength of the fake authority",
        hooks=(
            "the complaint/reference/consignment number to check independently",
            "the official helpline where this can be verified",
            "why an official notice arrives on WhatsApp",
            "asking for it in writing on letterhead",
        ),
    ),
    "job_scam": ScamPlaybook(
        what="They are dangling a fake job or work-from-home task scheme.",
        wants="a registration/training/security deposit before any work",
        hooks=(
            "employers pay employees, not the reverse",
            "the company name, website and an office address you could visit",
            "who the HR contact is and whether there's a video interview",
            "asking to be paid for the first task before paying anything",
        ),
    ),
    "tech_support": ScamPlaybook(
        what="They claim your device is infected and want remote access.",
        wants="you to install AnyDesk/TeamViewer and hand over the access code, then pay",
        hooks=(
            "how they know the device is infected without being on it",
            "what the app is actually for and why they need the code",
            "why the fee is asked in gift cards or UPI instead of on an invoice",
            "wanting to take it to a service centre instead",
        ),
    ),
    # Coarser labels the detection layer and tracking-link routing also emit.
    "refund": ScamPlaybook(
        what="They claim a refund is owed to you and need you to 'accept' it.",
        wants="you to approve a reverse payment or hand over card/UPI details",
        hooks=(
            "why receiving money needs your PIN — you only enter a PIN to send",
            "the order or transaction id this refund is supposedly against",
            "why it isn't just credited back to the original payment method",
            "the amount not matching anything you actually bought",
        ),
    ),
    "delivery": ScamPlaybook(
        what="They're posing as a courier over a parcel that's supposedly stuck.",
        wants="a small redelivery/customs fee and your address or card details",
        hooks=(
            "you weren't expecting a parcel — what's in it and who sent it",
            "the consignment/tracking number to check on the courier's own site",
            "why the fee is paid to a personal UPI id and not on delivery",
            "which courier this is and which local hub it's sitting at",
        ),
    ),
    "romance_scam": ScamPlaybook(
        what="They are building emotional dependency to eventually extract money.",
        wants="money, framed as an emergency, customs fee, or hospital bill",
        hooks=(
            "why a video call keeps getting postponed",
            "the name of the hospital or the airport they're stuck at",
            "why the money can't go to the hospital or agency directly",
            "asking about details of their life they gave differently before",
        ),
    ),
}

_DEFAULT_PLAYBOOK = ScamPlaybook(
    what="They are running some kind of scam on you, but the angle isn't clear yet.",
    wants="money, credentials, or an app install — you don't know which yet",
    hooks=(
        "who exactly they are and which company they're calling from",
        "how they got your number",
        "why any of this can't be done through official channels",
    ),
)


def _playbook_for(category: str | None) -> ScamPlaybook:
    key = (category or "").strip().lower().replace(" ", "_").replace("-", "_")
    if key in SCAM_PLAYBOOK:
        return SCAM_PLAYBOOK[key]
    # Detection sometimes returns a coarser label ("financial", "refund",
    # "delivery"); fall back to the closest playbook by substring.
    for name, pb in SCAM_PLAYBOOK.items():
        if key and (key in name or name.split("_")[0] == key):
            return pb
    return _DEFAULT_PLAYBOOK


# bubbles = how many separate WhatsApp messages this persona tends to fire per turn.
# max_words = soft per-bubble word cap. This is what makes message COUNT feel human
# and different per persona (a lonely aunty double/triple texts; a busy PM sends one line).
@dataclass
class PersonaShape:
    max_words: int
    bubble_weights: tuple[float, float, float, float]  # weights for 1,2,3,4 bubbles

PERSONA_SHAPE: dict[str, PersonaShape] = {
    "busy_professional":        PersonaShape(max_words=14, bubble_weights=(0.6, 0.3, 0.1, 0.0)),
    "skeptical_buyer":          PersonaShape(max_words=28, bubble_weights=(0.65, 0.3, 0.05, 0.0)),
    "half_understanding_user":  PersonaShape(max_words=16, bubble_weights=(0.35, 0.35, 0.25, 0.05)),
    "lonely_conversationalist": PersonaShape(max_words=40, bubble_weights=(0.2, 0.35, 0.3, 0.15)),
    "hopeful_opportunity_seeker": PersonaShape(max_words=22, bubble_weights=(0.5, 0.35, 0.15, 0.0)),
    "curious_user":             PersonaShape(max_words=14, bubble_weights=(0.55, 0.35, 0.1, 0.0)),
    "paranoid_tech_worker":     PersonaShape(max_words=20, bubble_weights=(0.55, 0.35, 0.1, 0.0)),
    "gullible_grandparent":     PersonaShape(max_words=18, bubble_weights=(0.3, 0.35, 0.25, 0.1)),
}

_DEFAULT_SHAPE = PersonaShape(max_words=20, bubble_weights=(0.45, 0.35, 0.2, 0.0))
_DEFAULT_STYLE = (
    "Mostly lowercase, minimal punctuation, casual contractions. Emojis rare. "
    "Occasional minor typo. Never sound polished or professionally written."
)


def _persona_style(persona_id: str) -> str:
    return PERSONA_STYLE.get(persona_id, _DEFAULT_STYLE)


def _persona_shape(persona_id: str) -> PersonaShape:
    return PERSONA_SHAPE.get(persona_id, _DEFAULT_SHAPE)


def _pick_bubble_target(shape: PersonaShape) -> int:
    return random.choices([1, 2, 3, 4], weights=list(shape.bubble_weights), k=1)[0]


# Used only when the model returns nothing at all. Kept varied and in-register
# so a provider hiccup doesn't emit the same telltale string every time.
_FALLBACKS: dict[str, tuple[str, ...]] = {
    "skeptical_buyer": (
        "Sorry, my phone lagged. Say that again.",
        "I did not follow that. Repeat the last part.",
    ),
    "half_understanding_user": (
        "beta screen has gone blank|||what happened",
        "arre it is not opening properly",
    ),
    "gullible_grandparent": (
        "beta my phone is doing something funny...|||say again",
        "i think i pressed wrong button... sorry",
    ),
    "curious_user": (
        "wait my phone froze 💀|||say that again",
        "bro my net just died|||what",
    ),
}
_GENERIC_FALLBACKS = (
    "hold on my phone lagged|||say that again",
    "sorry didnt catch that",
    "wait what|||msg came half",
)


def _fallback_reply(persona_id: str) -> str:
    return random.choice(_FALLBACKS.get(persona_id, _GENERIC_FALLBACKS))


def _persona_examples_block(persona_id: str) -> str:
    """Render this persona's few-shot samples as a demonstration block.

    Samples are shuffled per call so the model doesn't anchor on whichever one
    happens to sit closest to the end of the prompt.
    """
    samples = PERSONA_EXAMPLES.get(persona_id)
    if not samples:
        return ""
    picked = random.sample(list(samples), k=min(3, len(samples)))
    lines = []
    for scammer_line, our_line in picked:
        lines.append(f'Them: "{scammer_line}"')
        lines.append(f'You:  "{our_line}"')
        lines.append("")
    return "\n".join(lines).rstrip()

# ──────────────────────────────────────────────────────────────────
# STRATEGY RULES (enhanced)
# ──────────────────────────────────────────────────────────────────

STRATEGY_DONT: dict[str, str] = {
    "CONFUSION": "Do not summarize their instructions correctly. Mix up details.",
    "DELAY": "Do not invent contradictory stories. Do NOT reuse the same excuse you already gave.",
    "FAKE_COMPLIANCE": "Never confirm success. Don't invent a new tech-glitch every turn — reuse or evolve the current problem.",
    "AGGRESSION": "No long lectures. One sharp doubt, then wait.",
    "DERAILMENT": "Don't fully comply. Tangent into something personal, then half-return.",
    "ESCALATION": "Don't accept vague reassurance. Ask for proof ONCE — do not keep repeating the same demand.",
}

# These describe the tactic's FLAVOUR, not a mandatory template. The excuse/proof
# beats are optional — only use them when they haven't just been used (the
# anti-repetition guards enforce this). Most turns can just be a plain human reply.
STRATEGY_SHAPE: dict[str, str] = {
    "CONFUSION": "mix up a detail they said, or ask one confused question — or just sound lost",
    "DELAY": "if you haven't stalled recently, give ONE brief real-life reason; otherwise just reply short and vague",
    "FAKE_COMPLIANCE": "sound like you're going along; mention a snag only if you haven't already",
    "AGGRESSION": "express doubt or challenge their legitimacy, briefly",
    "DERAILMENT": "drift to something offhand, then loosely connect back — sparingly",
    "ESCALATION": "if you haven't already, ask once for one piece of proof; don't nag",
}

# ──────────────────────────────────────────────────────────────────
# ENTITY EXTRACTION — Pull key details from conversation
# ──────────────────────────────────────────────────────────────────

_AMOUNT_RE = re.compile(r'(?:₹|rs\.?|inr|usd|\$)\s*[\d,]+\.?\d*|\d[\d,]*\.?\d*\s*(?:₹|rs|rupees|dollars|lac|lakh|crore)', re.I)
_UPI_RE = re.compile(r'[\w.-]+@[\w]+', re.I)
_URL_RE = re.compile(r'https?://\S+|www\.\S+', re.I)
_PHONE_RE = re.compile(r'(?:\+91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}')

def _extract_entities(history: List[ChatMessage]) -> dict[str, list[str]]:
    """Extract key entities mentioned by the scammer throughout the conversation."""
    entities: dict[str, list[str]] = {
        "amounts": [],
        "upi_ids": [],
        "urls": [],
        "phones": [],
        "names": [],
    }
    for m in history:
        if m.role != "user":
            continue
        text = m.content
        entities["amounts"].extend(_AMOUNT_RE.findall(text))
        entities["upi_ids"].extend(_UPI_RE.findall(text))
        entities["urls"].extend(_URL_RE.findall(text))
        entities["phones"].extend(_PHONE_RE.findall(text))
    # Deduplicate
    for k in entities:
        entities[k] = list(dict.fromkeys(entities[k]))[:5]
    return entities


# ──────────────────────────────────────────────────────────────────
# REPETITION GUARDS — the two things that most made the bot obvious:
#   1) inventing a "busy / meeting / phone / tech-glitch" excuse EVERY turn
#   2) repeating the same demand ("send your id / proof / official name")
# We detect these in our own recent replies and tell the model to stop.
# ──────────────────────────────────────────────────────────────────

_EXCUSE_RE = re.compile(
    r"\b(meet(?:ing|ng)?|call|boss|office|review|busy|charg(?:e|ing|er)|battery|"
    r"network|spinning|spinner|loading|hang(?:ing|s)?|stuck|glitch|coffee|"
    r"one sec|two sec|2 sec|driving|drive|lunch|later|hold on)\b",
    re.I,
)

_DEMAND_RE = re.compile(
    r"\b(employee id|supervisor|company (?:id|name)|official (?:id|proof|name|verif)|"
    r"proof|reference number|complaint (?:number|id)|badge|prove|verify (?:your|who)|"
    r"or i(?:'m| am)? (?:done|stopping|stop)|otherwise i)\b",
    re.I,
)


def _count_recent(pattern: re.Pattern, history: List[ChatMessage], turns: int = 4) -> int:
    """How many of our last `turns` assistant messages matched `pattern`."""
    assistant_msgs = [m.content for m in history if m.role == "assistant"]
    return sum(1 for msg in assistant_msgs[-turns:] if pattern.search(msg))


_AI_ACCUSATION_RE = re.compile(
    r"\b(you(?:'re| are|r)?\s*(?:an?\s*)?(?:ai|a\.?i\.?|bot|robot|chatbot|"
    r"artificial intelligence|gpt|chatgpt|automated|machine)|"
    r"are you (?:a )?(?:ai|bot|human|real)|is this (?:a )?bot|not (?:a )?human)\b",
    re.I,
)


def _scammer_accuses_ai(text: str) -> bool:
    return bool(_AI_ACCUSATION_RE.search(text))


# ──────────────────────────────────────────────────────────────────
# CONVERSATIONAL / CASUAL MESSAGE DETECTION
# ──────────────────────────────────────────────────────────────────

_CASUAL_RE = re.compile(
    r"^\s*(?:"
    r"h(?:i|ello|ey|ow are (?:you|u|ya))|" 
    r"good (?:morning|afternoon|evening|night)|" 
    r"what(?:'s| is) (?:up|happening|going on)|" 
    r"(?:are )?(?:you|u) (?:there|here|online|available|free)|" 
    r"(?:what|who) (?:are|r) (?:you|u)|" 
    r"(?:how|kaise) (?:are|r) (?:you|u)|" 
    r"kya (?:chal|ho) (?:raha|rahi)|" 
    r"(?:theek|thik) (?:ho|hai)|" 
    r"(?:ok|okay|k|fine|sure|yes|no|ya|yea|yeah|nah|nope|hmm)|" 
    r"thanks?(?:\s+(?:you|u))?|" 
    r"bye|good ?bye|take care|see (?:you|u)|" 
    r"sorry|maaf|excuse me|" 
    r"(?:tell|bata|batao) (?:me|na)|" 
    r"kya baat hai|suno|bolo" 
    r")\s*[.!?]*\s*$",
    re.I,
)

_QUESTION_TO_US_RE = re.compile(
    r"(?:"
    r"(?:what|where|which|who|how|when|why|kya|kahan|kaise|kaun|kab)\b.{0,60}\?"
    r"|\?\s*$"
    r")",
    re.I,
)

_TOPIC_SHIFT_INDICATORS = re.compile(
    r"(?:"
    r"(?:btw|by the way|anyway|anyways|also|one more thing|listen|wait|actually|achha|acha|sun|suno)|" 
    r"(?:forget (?:that|it|about)|leave (?:that|it)|chhodo|rehne do|never ?mind)" 
    r")",
    re.I,
)


def _is_casual_message(text: str) -> bool:
    """Detect if a message is casual/conversational (not scam-action)."""
    clean = text.strip()
    if len(clean) < 40 and _CASUAL_RE.search(clean):
        return True
    return False


def _scammer_asks_question(text: str) -> bool:
    """Detect if the scammer is asking US a question."""
    return bool(_QUESTION_TO_US_RE.search(text.strip()))


def _detect_topic_shift(history: List[ChatMessage], latest: str) -> bool:
    """Detect if the scammer has shifted topics from what we were discussing."""
    if _TOPIC_SHIFT_INDICATORS.search(latest):
        return True
    # If our last reply asked about X but scammer is now talking about Y
    if len(history) >= 2:
        last_assistant = next((m.content for m in reversed(history) if m.role == "assistant"), "")
        if last_assistant and len(latest) > 5:
            # Simple heuristic: if less than 15% word overlap, probably topic shifted
            our_words = set(re.findall(r'\w{3,}', last_assistant.lower()))
            their_words = set(re.findall(r'\w{3,}', latest.lower()))
            if our_words and their_words:
                overlap = len(our_words & their_words) / max(len(their_words), 1)
                if overlap < 0.1:
                    return True
    return False


# ──────────────────────────────────────────────────────────────────
# INTERNAL PATTERNS
# ──────────────────────────────────────────────────────────────────

_PAID_ASSISTANT = re.compile(
    r"\b(i paid|already paid|sent (the )?money|transferred|payment done|"
    r"sent (the )?screenshot|sent screenshot|did the transfer|money is sent)\b",
    re.I,
)
_NEGATION_REPLY = re.compile(
    r"\b(didn'?t|did not|never|not yet|haven'?t|ignored|nah i didn)\b",
    re.I,
)

# Catch any XML-like tags the model might hallucinate
_XML_TAG_RE = re.compile(r'<[^>]+>.*?</[^>]+>', re.DOTALL | re.IGNORECASE)

# Natural seams a person would actually break a message at: after sentence-end
# or comma punctuation, or before a leading conjunction.
_CLAUSE_SPLIT_RE = re.compile(
    r'(?<=[.!?,])\s+|\s+(?=(?:but|and|so|then|also|because|coz|cuz|though|actually)\b)',
    re.I,
)


class BaitingAgent:
    """Generates realistic human-like scam-bait replies."""

    def __init__(self, llm_provider: BaseLLMProvider, max_bubbles: int | None = None):
        self._llm = llm_provider
        self._stealth = StealthOptimizer()
        # Hard ceiling on bubbles per turn. 1 makes every reply a single
        # message; higher values allow persona-driven double-texting.
        self._max_bubbles = max(1, max_bubbles) if max_bubbles else None

    @staticmethod
    def _temperature_for_strategy(strategy: str) -> float:
        if strategy == "DELAY":
            return 0.95
        if strategy in ("AGGRESSION", "ESCALATION"):
            return 0.85
        return 1.0

    @staticmethod
    def _truncate(s: str, max_len: int) -> str:
        s = s.strip()
        if len(s) <= max_len:
            return s
        return s[: max_len - 1].rstrip() + "…"

    @staticmethod
    def _incoming_user_chars(history: List[ChatMessage]) -> int:
        for m in reversed(history):
            if m.role == "user":
                return len(m.content)
        return 0

    def _strategy_lines(self, strategy: str) -> tuple[str, str, str]:
        s = strategy.upper() if strategy else "CONFUSION"
        do = STRATEGY_RULES.get(s, STRATEGY_RULES["CONFUSION"])
        dont = STRATEGY_DONT.get(s, STRATEGY_DONT["CONFUSION"])
        shape = STRATEGY_SHAPE.get(s, STRATEGY_SHAPE["CONFUSION"])
        return do, dont, shape

    @staticmethod
    def _conversation_heat_line(history: List[ChatMessage]) -> str:
        n_user = sum(1 for m in history if m.role == "user")
        if n_user <= 2:
            return "Stage: EARLY. The scammer is probing. Sound naive, curious, or mildly distracted."
        if n_user <= 5:
            return "Stage: BUILDING. They're getting into their pitch. Engage but add friction."
        if n_user <= 10:
            return "Stage: MID. They're pushing hard now. Add more obstacles, confusion, or delays."
        return "Stage: LATE. They're getting frustrated. Keep responses shorter, never give clean wins."

    def _build_context_block(self, history: List[ChatMessage], media_summary: str | None = None) -> str:
        """Build a rich context block with entity tracking and conversation state."""
        lines: list[str] = []

        # Stage
        lines.append(self._conversation_heat_line(history))

        # ── Anti-repetition: the two habits that expose the bot ──
        if _count_recent(_EXCUSE_RE, history, turns=3) >= 2:
            lines.append(
                "🚫 STOP MAKING EXCUSES. You've already blamed being busy / in a meeting / "
                "phone / network / a tech glitch in your recent messages. Do NOT invent another "
                "excuse this turn. Just reply plainly and briefly — answer, react, or push back "
                "WITHOUT explaining why you're slow. Real people don't narrate their day every text."
            )
        if _count_recent(_DEMAND_RE, history, turns=3) >= 2:
            lines.append(
                "🚫 STOP REPEATING THE SAME DEMAND. You've already asked for their id / proof / "
                "official name more than once. Do NOT ask for it again in the same way. Either drop "
                "it, change the subject, or react to what they actually said instead."
            )

        # Scammer thinks we're a bot — handle it like an annoyed real person, not by
        # doubling down on the script.
        last_user_now = next((m.content for m in reversed(history) if m.role == "user"), "")
        if last_user_now and _scammer_accuses_ai(last_user_now):
            lines.append(
                "🤖 THEY ACCUSED YOU OF BEING AN AI/BOT. React like a real irritated human would: "
                "brush it off casually, be a bit offended or amused, or ignore it and carry on — "
                "keep it SHORT and natural. Do NOT over-explain, do NOT list reasons you're human, "
                "do NOT suddenly get very formal. One quick dismissive line is best."
            )

        # Inbound media the scammer just sent (understood via vision)
        if media_summary:
            lines.append(
                f"📷 THE SCAMMER JUST SENT AN IMAGE. {media_summary}. "
                "React to this image in character as if you actually opened and looked at it. "
                "Do NOT ignore it. Do NOT say you can't see images."
            )

        # Extract what the scammer has mentioned
        entities = _extract_entities(history)
        if entities["amounts"]:
            lines.append(f"Amounts they mentioned: {', '.join(entities['amounts'])}")
        if entities["upi_ids"]:
            lines.append(f"UPI IDs they gave: {', '.join(entities['upi_ids'])}")
        if entities["urls"]:
            lines.append(f"Links they shared: {', '.join(entities['urls'])}")
        if entities["phones"]:
            lines.append(f"Phone numbers mentioned: {', '.join(entities['phones'])}")

        # Track what YOU (assistant) have claimed
        assistant_blob = " ".join(m.content.lower() for m in history if m.role == "assistant")
        if _PAID_ASSISTANT.search(assistant_blob):
            lines.append(
                "IMPORTANT: You already implied you paid/transferred/sent screenshot. "
                "Do NOT contradict this. Instead: blame a tech glitch, say it's 'pending', or ask them to check again."
            )
        else:
            lines.append("You have NOT confirmed paying or sending money yet.")

        # Last messages for anti-repetition
        last_assistant = next((m.content for m in reversed(history) if m.role == "assistant"), "")
        if last_assistant:
            lines.append(f"Your last message was: \"{self._truncate(last_assistant, 150)}\"")
            lines.append("DO NOT repeat this message or ask the same question again. Say something DIFFERENT.")

        last_user = next((m.content for m in reversed(history) if m.role == "user"), "")
        if last_user:
            lines.append(f"Their latest message: \"{self._truncate(last_user, 200)}\"")

        # NOTE: the "casual message" / "they asked a question" / "topic shift"
        # directives used to be emitted here as well. They now live in the single
        # `turn_directive` built in generate_reply(). Emitting both meant two or
        # three of them could fire at once and contradict each other (e.g.
        # "answer their question first" alongside "drop that and follow the new
        # topic"), which is a large part of why replies came out evasive.
        if last_user:
            assistant_msgs = [m.content for m in history if m.role == "assistant"]
            recent_question_count = sum(1 for msg in assistant_msgs[-4:] if "?" in msg)
            if recent_question_count >= 2:
                lines.append(
                    "You've asked several questions in a row lately. This turn, say something "
                    "instead of asking — react, push back, or mention what happened on your end."
                )

        return "\n".join(f"- {line}" for line in lines)

    def _trim_history(self, history: List[ChatMessage]) -> List[ChatMessage]:
        """Keep the last 20 turns with full text for deep conversational memory."""
        window = history[-20:] if len(history) > 20 else history
        trimmed: List[ChatMessage] = []
        for m in window:
            trimmed.append(
                ChatMessage(role=m.role, content=self._truncate(m.content, 1500))
            )
        return trimmed

    @staticmethod
    def _limit_questions(parts: list[str], max_questions: int = 1) -> list[str]:
        """Drop question *sentences* past the cap, keeping the rest intact.

        The previous approach rewrote surplus '?' into '.', which produced
        obviously broken lines like "which company is this." — a far louder
        bot tell than the extra question would have been. Removing the whole
        interrogative sentence leaves natural text behind.
        """
        seen = 0
        out: list[str] = []
        for part in parts:
            if "?" not in part:
                out.append(part)
                continue
            sentences = [s for s in re.split(r"(?<=[?.!])\s+", part) if s.strip()]
            kept: list[str] = []
            for sentence in sentences:
                if sentence.rstrip().endswith("?"):
                    if seen >= max_questions:
                        continue
                    seen += 1
                kept.append(sentence)
            joined = " ".join(kept).strip()
            if joined:
                out.append(joined)
        return out or parts[:1]

    @staticmethod
    def _split_long_bubble(text: str, max_words: int) -> list[str]:
        """Break an over-long bubble at clause boundaries, never mid-clause.

        Chopping every `max_words` words regardless of where the sentence is
        ("...the money is not showing in the bank" / "app at all yaar") is one
        of the most obvious machine artifacts in the whole pipeline. Real
        people break at commas, sentence ends, and conjunctions — so we only
        split there, and we tolerate a bubble running somewhat over rather
        than damaging it.
        """
        words = text.split()
        if len(words) <= max_words * 1.4:
            return [text]

        clauses = [c.strip() for c in _CLAUSE_SPLIT_RE.split(text) if c and c.strip()]
        if len(clauses) < 2:
            # No natural seam anywhere — a long unbroken sentence is still far
            # more human than a mid-word guillotine.
            return [text]

        bubbles: list[str] = []
        current: list[str] = []
        for clause in clauses:
            prospective = current + [clause]
            if current and len(" ".join(prospective).split()) > max_words:
                bubbles.append(" ".join(current).strip())
                current = [clause]
            else:
                current = prospective
        if current:
            bubbles.append(" ".join(current).strip())
        return [b for b in bubbles if b] or [text]

    def _enforce_max_words(self, parts: list[str], max_words: int = 20) -> list[str]:
        """Split over-long bubbles at natural seams only."""
        final_parts: list[str] = []
        for part in parts:
            final_parts.extend(self._split_long_bubble(part, max_words))
        return final_parts

    def _parse_llm_segments(self, raw: str) -> List[str]:
        """Parse LLM output, stripping any thought/reasoning blocks."""
        # Remove ALL XML-like tag pairs (catches <thought>, <truth>, <think>, <reasoning>, etc.)
        text = _XML_TAG_RE.sub('', raw)
        # Also catch unclosed tags or malformed ones
        text = re.sub(r'</?(?:thought|truth|reasoning|think|plan|analysis|internal)[^>]*>', '', text, flags=re.IGNORECASE).strip()

        if not text:
            return ["hmm one sec"]
            
        if "\n\n" in text:
            parts = [p.replace("\n", " ").strip() for p in text.split("\n\n") if p.strip()]
        elif "|||" in text:
            parts = [p.replace("\n", " ").strip() for p in text.split("|||") if p.strip()]
        else:
            parts = [text.replace("\n", " ").strip()]

        parts = parts[:4]
        return parts if parts else ["hmm one sec"]

    def _maybe_repair_contradiction(self, parts: list[str], had_payment_claim: bool) -> list[str]:
        if not parts or not had_payment_claim:
            return parts
        joined = " ".join(parts).lower()
        if not _NEGATION_REPLY.search(joined):
            return parts
        repaired = [
            "wait ignore that i meant its not going through on my end",
            "bank app acting weird",
        ]
        return repaired[: max(1, min(len(parts), 2))]

    @staticmethod
    def _repair_tracking_link(parts: list[str], tracking_url: str) -> list[str]:
        """Guarantee the real tracking link reaches the scammer.

        The model is asked to paste the exact URL, but small/cheap models sometimes
        copy a leftover placeholder token (e.g. literal "{url}") instead of the real
        link, which reads as a broken link on the scammer's side. If the real URL is
        missing, swap any placeholder-looking token for it, or append it as its own
        bubble as a last resort.
        """
        if any(tracking_url in p for p in parts):
            return parts

        # Only matches bracket-wrapped placeholders ({url}, [link], …), never the
        # plain English word "link" that can legitimately appear in a sentence.
        placeholder_re = re.compile(r"[\{\[]\s*(?:url|link)\s*[\}\]]", re.I)
        for i, p in enumerate(parts):
            if placeholder_re.search(p):
                parts[i] = placeholder_re.sub(tracking_url, p)
                return parts

        return parts + [tracking_url]

    def _light_cleanup(self, parts: list[str]) -> list[str]:
        """Minimal cleanup — only remove truly AI-sounding artifacts, preserve natural text."""
        cleaned = []
        for seg in parts:
            t = seg.strip()
            if not t:
                continue
            # Remove surrounding quotes
            if (t.startswith('"') and t.endswith('"')) or (t.startswith("'") and t.endswith("'")):
                t = t[1:-1].strip()
            # Replace em-dashes (AI artifact) with regular dashes
            t = t.replace("—", " - ").replace("–", "-")
            # Collapse multiple spaces
            t = re.sub(r"\s{2,}", " ", t).strip()
            if t:
                cleaned.append(t)
        return cleaned if cleaned else ["hmm wait"]

    def _compute_part_delays(self, reply_parts: list[str], incoming_len: int, use_dynamic: bool = True, fixed_delay: int = 3, active_commitment: str | None = None) -> list[int]:
        if not use_dynamic:
            return [fixed_delay] * len(reply_parts)
            
        delays = []
        base_read_delay = min(max(incoming_len // 30, 1), 4)
        
        commitment_delay = 0
        if active_commitment:
            if active_commitment == "SHORT_DELAY":
                commitment_delay = random.randint(60, 180)
            elif active_commitment in ("AFTER_LUNCH", "AFTER_DINNER", "TOMORROW", "LATER"):
                commitment_delay = random.randint(300, 900)
            else:
                commitment_delay = random.randint(30, 120)
        
        for i, part in enumerate(reply_parts):
            typing_delay = min(max(len(part) // 25, 2), 6)
            
            if i == 0:
                delays.append(base_read_delay + typing_delay + commitment_delay)
            else:
                delays.append(typing_delay + random.randint(0, 1))
                
        return delays

    async def generate_reply(
        self, request: BaitingRequest, tracking_url: str | None = None,
    ) -> BaitingResponse:
        start_time = time.monotonic()
        session_id = request.session_id or "default_session"
        strategy = (request.current_strategy or "CONFUSION").upper()

        persona_id = request.persona.lower().replace(" ", "_")
        persona_desc = DEEP_PERSONAS.get(
            persona_id, DEEP_PERSONAS["busy_professional"]
        )
        persona_style = _persona_style(persona_id)
        persona_shape = _persona_shape(persona_id)
        target_bubbles = _pick_bubble_target(persona_shape)
        if self._max_bubbles:
            target_bubbles = min(target_bubbles, self._max_bubbles)
        persona_max_words = persona_shape.max_words
        # With only one bubble available a thought can't be continued in the
        # next message, so give the single message room to land properly.
        if target_bubbles == 1:
            persona_max_words = int(persona_max_words * 1.5)
        strat_do, strat_dont, strat_shape = self._strategy_lines(strategy)
        context_block = self._build_context_block(request.history, request.incoming_media_summary)

        # Detect conversational signals for the latest message
        last_user_msg = next((m.content for m in reversed(request.history) if m.role == "user"), "")
        is_casual = _is_casual_message(last_user_msg)
        asks_us_question = _scammer_asks_question(last_user_msg)
        topic_shifted = _detect_topic_shift(request.history, last_user_msg)

        # ── This turn's handling note ────────────────────────────────
        # Previously three separate "⚡ PRIORITY" blocks could all fire at once
        # and contradict each other (and the turn intent). There is now exactly
        # one directive, chosen by what actually happened in their last message.
        turn_directive = ""
        if is_casual:
            turn_directive = (
                "They just sent something casual/social. Answer the social bit like a person would "
                "('ya im here', 'im good, busy day') before anything else. Don't jump into business."
            )
        elif asks_us_question:
            turn_directive = (
                f'They asked you something: "{self._truncate(last_user_msg, 150)}". '
                "Give them an actual answer in character — a real one, a vague one, or a wrong one, "
                "but an answer. Bouncing a question straight back is the giveaway."
            )
        elif topic_shifted:
            turn_directive = (
                "They've moved to a new subject. Go with them — respond to the new thing, "
                "not the one they've dropped."
            )

        assistant_msgs = [m.content for m in request.history if m.role == "assistant"]
        recent_assistant = assistant_msgs[-4:]
        recent_question_count = sum(1 for msg in recent_assistant if "?" in msg)

        # Turn intent is a nudge for VARIETY, not a gag order. When they've
        # asked us something directly, answering always wins — forcing
        # "STATEMENT" or "REACT_EMOTION" on top of a direct question was
        # producing non-sequiturs that read as broken automation.
        if asks_us_question:
            turn_intent = "ANSWER_OR_BLUFF"
        else:
            intents = ["STATEMENT", "ANSWER_OR_BLUFF", "QUESTION", "REACT_EMOTION"]
            if recent_question_count == 0:
                weights = [0.35, 0.25, 0.25, 0.15]
            elif recent_question_count == 1:
                weights = [0.40, 0.30, 0.10, 0.20]
            else:
                weights = [0.55, 0.30, 0.00, 0.15]
            turn_intent = random.choices(intents, weights=weights, k=1)[0]

        # Fix 4: Track the first word of assistant's last message to avoid repetitions
        last_opener = ""
        if recent_assistant:
            last_msg = recent_assistant[-1].strip()
            if last_msg:
                words = re.findall(r"\b\w+\b", last_msg)
                if words:
                    last_opener = words[0].lower()

        current_turn = sum(1 for m in request.history if m.role == "assistant")
        
        commitment_state = _commitments.get(session_id)
        if commitment_state and commitment_state.commitment_until_turn and current_turn >= commitment_state.commitment_until_turn:
            commitment_state.pending_commitment = None
            commitment_state.commitment_until_turn = None
            
        active_commitment = commitment_state.pending_commitment if commitment_state else None

        # Emoji/typo states would break formal or non-emoji personas, so gate them.
        _formal_personas = {"skeptical_buyer", "hopeful_opportunity_seeker"}
        _temp_state_pool = ["DISTRACTED", "SHORT_REPLY", "MILDLY_ANNOYED", "NORMAL"]
        if persona_id not in _formal_personas:
            _temp_state_pool.append("TYPO_HEAVY")
        if persona_id == "curious_user":
            _temp_state_pool.append("EMOJI_FRIENDLY")
        temp_state = random.choice(_temp_state_pool)
        # Bare state tokens ("DISTRACTED", "TYPO_HEAVY") meant little to the
        # model; spelling out the behaviour actually changes the output.
        _state_text = {
            "DISTRACTED": "You're half paying attention right now — something else is going on around you.",
            "SHORT_REPLY": "You're not in the mood to type much this time. Keep it clipped.",
            "MILDLY_ANNOYED": "This is starting to get on your nerves a bit.",
            "TYPO_HEAVY": "You're typing carelessly right now — a couple of real typos slip through.",
            "EMOJI_FRIENDLY": "You're in a jokey mood and an emoji fits this one.",
        }
        state_instruction = ""
        if temp_state != "NORMAL":
            state_instruction = (
                "═══ YOUR MOOD RIGHT NOW ═══\n"
                f"{_state_text.get(temp_state, temp_state)} Just for this message.\n\n"
            )

        if active_commitment:
            state_instruction += (
                "═══ SOMETHING YOU ALREADY SAID ═══\n"
                f"You told them: {active_commitment.replace('_', ' ').lower()}. "
                "Behave like that's still true — don't suddenly be free and finishing tasks.\n\n"
            )

        # Intent is phrased as a leaning, not a prohibition. Hard bans ("do NOT
        # ask any questions", "do NOT use a question mark") on top of everything
        # else were pushing the model into contentless filler.
        intent_hint = {
            "STATEMENT": "lean toward making a statement or observation rather than asking something",
            "REACT_EMOTION": "lean toward just reacting — confusion, irritation, surprise, interest",
            "QUESTION": "a single question fits well here",
            "ANSWER_OR_BLUFF": (
                "answer them — either something concrete and specific, or a vague/hedged "
                "answer if the character wouldn't know"
            ),
        }.get(turn_intent, "reply however this character naturally would")

        playbook = _playbook_for(request.scam_category)
        examples_block = _persona_examples_block(persona_id)
        persona_name = persona_desc.split(",")[0].replace("You are ", "").strip()

        goal_line = (
            "Draw out details about their operation — account numbers, names, links, who they work for — "
            "but only ever as a curious/worried person would, never as an interrogator."
            if request.goal == "extract_information"
            else "Keep them busy. Time spent on you is time not spent on a real victim."
        )

        system_prompt = (
            f"You are {persona_name}, texting on WhatsApp. Someone has messaged you and you do "
            "not know they are a scammer — you are just a person dealing with a message.\n"
            "Write the next thing this person sends. Not dialogue, not a description — the literal "
            "text they type into the box.\n\n"

            "═══ WHO YOU ARE ═══\n"
            f"{persona_desc}\n\n"

            f"Today is {datetime.now().strftime('%A, %d %B %Y')}. You know what day it is, the same "
            "way anyone glancing at their phone does.\n\n"

            "═══ WHAT'S ACTUALLY GOING ON ═══\n"
            f"{playbook.what}\n"
            f"Right now they are angling for: {playbook.wants}.\n"
            "Things a real person in your position might genuinely latch onto (use at most one, "
            "only if it fits your character's level of understanding — Suresh uncle would never "
            "mention an SSL certificate):\n"
            + "".join(f"  • {h}\n" for h in playbook.hooks)
            + f"Your underlying aim: {goal_line}\n\n"

            f"{state_instruction}"

            "═══ HOW THIS PERSON TEXTS ═══\n"
            f"{persona_style}\n\n"

            + (f"═══ HOW {persona_name.upper()} REPLIES — study the register ═══\n"
               f"{examples_block}\n"
               "These show the voice, rhythm and level of detail to aim for. They are not lines to "
               "reuse — write something new that fits the message you actually received.\n\n"
               if examples_block else "")

            + "═══ THE ONE THING THAT MATTERS MOST ═══\n"
            "Your reply has to be ABOUT what they just said. Specific to it. If someone read only "
            "their message and your reply, it should be obvious you actually read theirs.\n"
            "Vague, could-go-anywhere replies ('ok', 'hmm let me see', 'one sec', 'what is this') are "
            "what makes a chat feel automated. Say something with content in it — an actual objection, "
            "an actual detail from your life, an actual misunderstanding of a specific word they used.\n\n"

            + (f"This turn specifically: {turn_directive}\n\n" if turn_directive else "")

            + f"Nudge for this turn (not a rule): {intent_hint}.\n"
            + (f"You opened your last message with '{last_opener}' — start differently this time.\n" if last_opener else "")
            + "\n"

            "═══ WHAT YOU MAY AND MAY NOT MAKE UP ═══\n"
            "About THEM and their story: stick to what they actually said. Never invent an amount, "
            "name, link, or reference number and put it in their mouth. You may misread or garble "
            "details they really did give.\n"
            "About YOUR OWN life: invent freely. Your bank, your app crashing, your daughter's exam, "
            "the number you half-typed, the balance you think you have — that's your character, and "
            "specific invented detail is exactly what makes you sound real.\n\n"

            "═══ THINGS THAT INSTANTLY READ AS A BOT ═══\n"
            "• Answering a question with a question.\n"
            "• Explaining your reasoning or narrating your day when nobody asked.\n"
            "• A new excuse every single message (meeting, battery, network, glitch).\n"
            "• Repeating a demand you already made, in the same words.\n"
            "• Being tidy: perfect structure, balanced sentences, an em-dash, a closing summary.\n"
            "• Politeness that never runs out. Real people get short, bored, or annoyed.\n\n"

            "═══ SHAPE OF THE REPLY ═══\n"
            + (
                "Send exactly ONE message. No ||| separator, no second line.\n"
                "That one message carries ONE thought — the single most natural thing this person "
                "would say back. Not two observations stapled together, not an answer plus an extra "
                "remark. Pick the one that matters and send only that.\n"
                f"Usually under ~{persona_max_words} words.\n"
                if target_bubbles == 1 else
                f"About {target_bubbles} bubble(s) this turn, split with |||. It's a target, not a quota — "
                "one blunt line is often the most human thing to send.\n"
                f"Each bubble is roughly one thought, usually under ~{persona_max_words} words. Break at natural "
                "points, the way you'd actually hit send.\n"
                '"i checked my account but the money is not there" → "i checked my account|||but the money is not there"\n'
            )
            + "At most ONE question in the whole turn — stacking two is a tell.\n"
            "If you said earlier that you were busy, waiting on someone, or would do something later, "
            "stay consistent with that.\n\n"

            "═══ WHERE THINGS STAND ═══\n"
            f"{context_block}\n\n"

            f"═══ YOUR ANGLE THIS TURN: {strategy} ═══\n"
            f"{strat_do}\n"
            f"Avoid: {strat_dont}\n"
            f"Roughly: {strat_shape}\n"
            "This colours HOW you reply. It never replaces replying to what they actually said, and "
            "it is not a script you have to hit every turn.\n\n"

            "═══ OUTPUT ═══\n"
            "Just the message text, exactly as typed. ||| between bubbles. No line breaks, no quotes "
            "around it, no tags, no explanation, no stage directions."
        )

        # --- Tracking link injection ---
        if tracking_url:
            system_prompt += (
                f"\n\n═══ TRACKING LINK ═══\n"
                f"Paste this EXACT link somewhere in your reply, unchanged, no placeholder text: {tracking_url}\n"
                f"Example: 'i put the screenshot here check {tracking_url}' or 'ok see this {tracking_url}'\n"
                "Do not shorten, rewrite, or wrap it. Make it fit the conversation. Don't sound promotional."
            )

        # Merge our own consecutive bubbles back into one turn instead of
        # discarding them. The old code kept only the FIRST bubble and threw the
        # rest away, so the model could not see most of what it had already
        # said — which is why it kept re-asking the same thing and contradicting
        # itself. Bubbles are joined with a space (never '|||') so nothing in the
        # history invites the model to imitate the separator.
        collapsed_history: list[ChatMessage] = []
        for msg in self._trim_history(request.history):
            content = " ".join(
                part.strip()
                for part in re.split(r"\|\|\||\n", msg.content)
                if part.strip()
            )
            if not content:
                continue

            if (
                collapsed_history
                and collapsed_history[-1].role == "assistant"
                and msg.role == "assistant"
            ):
                merged = f"{collapsed_history[-1].content} {content}".strip()
                collapsed_history[-1] = ChatMessage(role="assistant", content=merged)
            else:
                collapsed_history.append(ChatMessage(role=msg.role, content=content))

        messages: list[dict] = [{"role": "system", "content": system_prompt}]
        for msg in collapsed_history:
            messages.append({"role": msg.role, "content": msg.content})

        had_payment_claim = _PAID_ASSISTANT.search(
            " ".join(m.content.lower() for m in request.history if m.role == "assistant")
        ) is not None

        try:
            max_attempts = 2
            for attempt in range(max_attempts):
                raw = await self._llm.generate_text_for_risk(
                    messages=messages,
                    risk_level="high",
                    temperature=self._temperature_for_strategy(strategy),
                    # 80 tokens truncated real replies mid-sentence and pushed the
                    # model toward one-word filler. A WhatsApp turn of 2-3 bubbles
                    # needs meaningfully more headroom than that.
                    max_tokens=180,
                ) or _fallback_reply(persona_id)

                if not raw.strip():
                    logger.warning("LLM returned empty text for session=%s", session_id)
                    raw = _fallback_reply(persona_id)

                reply_parts = self._parse_llm_segments(raw)
                reply_parts = self._maybe_repair_contradiction(reply_parts, had_payment_claim)
                processed_parts = [p for p in self._light_cleanup(reply_parts) if p.strip()]
                if not processed_parts:
                    processed_parts = self._parse_llm_segments(_fallback_reply(persona_id))

                total_questions = sum(p.count('?') for p in processed_parts)

                if total_questions > 1 and attempt < max_attempts - 1:
                    logger.warning("Regenerating: turn has %d questions (limit 1). session=%s", total_questions, session_id)
                    continue

                if total_questions > 1:
                    # Retry didn't help — drop the surplus question sentences
                    # rather than mangling their punctuation.
                    processed_parts = self._limit_questions(processed_parts, max_questions=1)


                processed_parts = self._enforce_max_words(processed_parts, persona_max_words)
                # Keep the bubble count human: cap at 4, and don't let a terse
                # persona spray more bubbles than it naturally would this turn.
                # Surplus bubbles are folded into the last kept one rather than
                # discarded — slicing them off used to delete the substantive
                # half of a reply and leave only the throwaway opener.
                # One bubble of headroom above the target keeps replies from
                # feeling clipped — unless a hard ceiling is configured, which
                # is honoured exactly.
                bubble_cap = min(4, max(target_bubbles + 1, 1))
                if self._max_bubbles:
                    bubble_cap = min(bubble_cap, self._max_bubbles)
                if len(processed_parts) > bubble_cap:
                    head = processed_parts[: bubble_cap - 1]
                    tail = " ".join(processed_parts[bubble_cap - 1:]).strip()
                    processed_parts = head + ([tail] if tail else [])
                reply_parts = processed_parts
                break

            if tracking_url:
                reply_parts = self._repair_tracking_link(reply_parts, tracking_url)

            reply_text = " ".join(reply_parts) if len(reply_parts) > 1 else reply_parts[0]

            if not active_commitment:
                new_comm = _detect_commitment(reply_text)
                if new_comm:
                    if not commitment_state:
                        commitment_state = SessionCommitment()
                        _commitments[session_id] = commitment_state
                    commitment_state.pending_commitment = new_comm
                    commitment_state.commitment_until_turn = current_turn + random.randint(2, 4)

            logger.info(
                "Baiting reply [LLM]: session=%s, strategy=%s, parts=%d, text='%s'",
                session_id, strategy, len(reply_parts), reply_text[:120]
            )

            incoming_len = self._incoming_user_chars(request.history)
            part_delay_seconds = self._compute_part_delays(
                reply_parts, 
                incoming_len, 
                use_dynamic=request.use_dynamic_delay, 
                fixed_delay=request.fixed_delay_seconds,
                active_commitment=active_commitment
            )
            response_delay_seconds = part_delay_seconds[0] if part_delay_seconds else 3

            return BaitingResponse(
                reply_text=reply_text,
                reply_parts=reply_parts,
                response_delay_seconds=response_delay_seconds,
                part_delay_seconds=part_delay_seconds,
                processing_time_ms=(time.monotonic() - start_time) * 1000,
                strategy_used=strategy,
                persona_used=persona_id,
                goal=request.goal,
                stealth_typing_speed_ms=self._stealth.randomize_typing_speed(),
                suspicion_detected=False,
            )

        except Exception as e:
            logger.error("Baiting agent error for session=%s: %s", session_id, e)
            fb_parts = self._parse_llm_segments(_fallback_reply(persona_id))
            delays = self._compute_part_delays(
                fb_parts, 
                0, 
                use_dynamic=request.use_dynamic_delay, 
                fixed_delay=request.fixed_delay_seconds
            )
            return BaitingResponse(
                reply_text=" ".join(fb_parts),
                reply_parts=fb_parts,
                response_delay_seconds=delays[0],
                part_delay_seconds=delays,
                processing_time_ms=(time.monotonic() - start_time) * 1000,
                strategy_used=strategy,
                persona_used=request.persona,
                goal=request.goal,
                stealth_typing_speed_ms=100,
            )
