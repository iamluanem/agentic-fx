"""Rule-based intent parser for English and Brazilian Portuguese.

Used when no LLM is configured, when the LLM fails, and as the reference for grounding checks (numbers the LLM
returns must appear in what the user actually wrote). It only extracts what is stated; defaults are applied
later, visibly, by the validator.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation

from app.models import StructuredIntent

CURRENCY_TOKENS = {
    "r$": "BRL", "brl": "BRL", "reais": "BRL", "real": "BRL",
    "€": "EUR", "eur": "EUR", "euro": "EUR", "euros": "EUR",
    "us$": "USD", "$": "USD", "usd": "USD", "dollar": "USD", "dollars": "USD", "dólar": "USD", "dólares": "USD",
    "£": "GBP", "gbp": "GBP", "pound": "GBP", "pounds": "GBP", "libra": "GBP", "libras": "GBP",
}

_NUM = r"\d[\d.,]*(?:\s?(?:k|mil)\b)?"
_PREFIX = re.compile(r"(?P<cur>R\$|US\$|€|£|\$|\b(?:BRL|EUR|USD|GBP)\b)\s?(?P<num>" + _NUM + ")", re.I)
_SUFFIX = re.compile(
    r"(?P<num>" + _NUM + r")\s?(?P<cur>€|£|\b(?:BRL|EUR|USD|GBP|reais|real|euros?|d[oó]lar(?:es)?|dollars?|libras?|pounds?)\b)",
    re.I)

MONTHS = {
    "january": 1, "jan": 1, "janeiro": 1, "february": 2, "feb": 2, "fevereiro": 2, "fev": 2, "march": 3, "mar": 3,
    "março": 3, "marco": 3, "april": 4, "apr": 4, "abril": 4, "abr": 4, "may": 5, "maio": 5, "mai": 5, "june": 6,
    "jun": 6, "junho": 6, "july": 7, "jul": 7, "julho": 7, "august": 8, "aug": 8, "agosto": 8, "ago": 8,
    "september": 9, "sep": 9, "sept": 9, "setembro": 9, "set": 9, "october": 10, "oct": 10, "outubro": 10,
    "out": 10, "november": 11, "nov": 11, "novembro": 11, "december": 12, "dec": 12, "dezembro": 12, "dez": 12,
}
_MONTH_NAMES = "|".join(sorted(MONTHS, key=len, reverse=True))

THRESHOLD_WORDS = re.compile(
    r"\b(ask me|ask first|check with me|approval|approve|confirm|pergunt\w*|consult\w*|aprova\w*|confirm\w*|autoriza\w*)",
    re.I)
PER_TX_WORDS = re.compile(
    r"(per (transaction|conversion|trade|purchase|execution)|por execu[çc][ãa]o|in (one|a single) (transaction|conversion|go)|each (transaction|conversion|time)|"
    r"at a time|por (transa[çc][ãa]o|convers[ãa]o|vez|opera[çc][ãa]o)|numa (s[óo] )?(transa[çc][ãa]o|vez)|"
    r"em uma (s[óo] )?(transa[çc][ãa]o|vez)|de uma vez|cada (transa[çc][ãa]o|convers[ãa]o|compra))", re.I)
BUDGET_WORDS = re.compile(
    r"\b(spend\w*|budget|available|up to|at most|no more than|i have|afford|gastar|gasto|or[çc]amento|"
    r"dispon[ií]ve\w*|at[ée]|no m[áa]ximo|tenho|posso usar)\b", re.I)
TARGET_WORDS = re.compile(
    r"\b(need|want|acquire|buy|get|save up|preciso|quero|comprar|adquirir|juntar|ter)\b", re.I)

_SENTENCE_SPLIT = re.compile(r"[.!?;](?=\s|$)|\n")


@dataclass(frozen=True)
class MoneyMention:
    amount: Decimal
    currency: str
    start: int
    end: int


def parse_number(raw: str) -> Decimal | None:
    text = raw.strip().lower()
    multiplier = Decimal(1)
    if text.endswith("mil"):
        multiplier, text = Decimal(1000), text[:-3].strip()
    elif text.endswith("k"):
        multiplier, text = Decimal(1000), text[:-1].strip()
    text = text.rstrip(".,")
    if not text:
        return None
    if "." in text and "," in text:
        decimal_sep = "." if text.rfind(".") > text.rfind(",") else ","
        thousands = "," if decimal_sep == "." else "."
        text = text.replace(thousands, "").replace(decimal_sep, ".")
    elif "," in text or "." in text:
        sep = "," if "," in text else "."
        parts = text.split(sep)
        if len(parts) > 2 or len(parts[-1]) == 3:
            text = text.replace(sep, "")
        else:
            text = text.replace(sep, ".")
    try:
        return Decimal(text) * multiplier
    except InvalidOperation:
        return None


def money_mentions(text: str) -> list[MoneyMention]:
    found: dict[int, MoneyMention] = {}
    for pattern in (_PREFIX, _SUFFIX):
        for m in pattern.finditer(text):
            currency = CURRENCY_TOKENS.get(m.group("cur").lower())
            amount = parse_number(m.group("num"))
            if currency and amount and amount > 0:
                overlapping = [k for k, v in found.items() if not (m.end() <= v.start or m.start() >= v.end)]
                if not overlapping:
                    found[m.start()] = MoneyMention(amount, currency, m.start(), m.end())
    return sorted(found.values(), key=lambda x: x.start)


def numbers_in(text: str) -> set[Decimal]:
    """Every number the user wrote, normalised — used to ground LLM output."""
    values = set()
    for m in re.finditer(_NUM, text, re.I):
        n = parse_number(m.group(0))
        if n is not None:
            values.add(n.normalize())
    return values


def detect_language(text: str) -> str:
    pt = len(re.findall(r"\b(preciso|quero|at[ée]|reais|para|de|minha|meu|n[ãa]o|posso|gastar|antes|pergunte|vez)\b",
                        text, re.I))
    en = len(re.findall(r"\b(need|want|by|before|the|my|spend|don't|ask|each|up to|i)\b", text, re.I))
    return "pt" if pt > en else "en"


def _sentence_bounds(text: str, index: int) -> tuple[int, int]:
    start, end = 0, len(text)
    for m in _SENTENCE_SPLIT.finditer(text):
        if m.end() <= index:
            start = m.end()
        elif m.start() >= index:
            end = m.start()
            break
    return start, end


def _last_match_end(pattern: re.Pattern, text: str) -> int:
    ends = [m.end() for m in pattern.finditer(text)]
    return max(ends) if ends else -1


def classify(text: str, mention: MoneyMention, neighbours: list[MoneyMention] | None = None) -> str | None:
    """Role of an amount, judged from its own clause: the context stops at neighbouring amounts."""
    s, e = _sentence_bounds(text, mention.start)
    for other in neighbours or []:
        if other.end <= mention.start:
            s = max(s, other.end)
        elif other.start >= mention.end:
            e = min(e, other.start)
    before, after = text[s:mention.start], text[mention.end:e]
    after = re.split(r",|\band\b|\be\b", after, maxsplit=1)[0]
    if THRESHOLD_WORDS.search(before) and re.search(r"(above|over|more than|exceed|acima|mais de|passar de|maior)",
                                                    before, re.I):
        return "approvalThreshold"
    if PER_TX_WORDS.search(after[:45]) or PER_TX_WORDS.search(before[-45:]):
        return "maxTransactionAmount"
    if re.match(r"\s*(available|dispon[ií]ve)", after, re.I):
        return "maxBudget"
    budget, target = _last_match_end(BUDGET_WORDS, before), _last_match_end(TARGET_WORDS, before)
    if budget > target:
        return "maxBudget"
    if target >= 0:
        return "targetAmount"
    return None


def parse_deadline(text: str, today: date) -> date | None:
    iso = re.search(r"\b(20\d{2})-(\d{2})-(\d{2})\b", text)
    if iso:
        return _safe_date(int(iso.group(1)), int(iso.group(2)), int(iso.group(3)))
    patterns = [
        (rf"\b({_MONTH_NAMES})\.?\s+(\d{{1,2}})(?:st|nd|rd|th)?(?:,?\s+(20\d{{2}}))?\b", "md"),
        (rf"\b(\d{{1,2}})(?:st|nd|rd|th)?\s+(?:de\s+)?({_MONTH_NAMES})\.?(?:\s+(?:de\s+)?(20\d{{2}}))?\b", "dm"),
    ]
    for pattern, order in patterns:
        for m in re.finditer(pattern, text, re.I):
            if order == "md":
                month, day = MONTHS.get(m.group(1).lower()), int(m.group(2))
            else:
                day, month = int(m.group(1)), MONTHS.get(m.group(2).lower())
            if month is None:
                continue
            return _resolve(day, month, m.group(3), today)
    slash = re.search(r"\b(\d{1,2})/(\d{1,2})(?:/(20\d{2}))?\b", text)
    if slash and re.search(r"\b(at[ée]|antes|prazo|dia)\b", text, re.I):
        return _resolve(int(slash.group(1)), int(slash.group(2)), slash.group(3), today)
    return None


def _resolve(day: int, month: int, year: str | None, today: date) -> date | None:
    if year:
        return _safe_date(int(year), month, day)
    candidate = _safe_date(today.year, month, day)
    if candidate and candidate < today:
        candidate = _safe_date(today.year + 1, month, day)
    return candidate


def _safe_date(y: int, m: int, d: int) -> date | None:
    try:
        return date(y, m, d)
    except ValueError:
        return None


def parse_target_rate(text: str) -> tuple[Decimal | None, str | None]:
    """Returns (rate, quote_style). 'direct' = target per 1 source (e.g. 0.17 EUR per BRL);
    'inverse' = source per 1 target (e.g. EUR at R$5.90)."""
    direct = re.search(r"\brate\s+(?:of\s+|at\s+|reaches\s+|hits\s+|above\s+)?(0[.,]\d{2,6})\b", text, re.I)
    if direct:
        return parse_number(direct.group(1).replace(",", ".")), "direct"
    inverse = re.search(
        r"\b(?:euro|eur|dollar|d[óo]lar|usd|pound|libra|gbp)s?\s+(?:at|a|em|reaches|chegar a|abaixo de|below)\s+"
        r"(?:R\$\s?)?(\d{1,2}[.,]\d{1,4})\b", text, re.I)
    if inverse:
        return parse_number(inverse.group(1).replace(",", ".")), "inverse"
    return None, None


def parse_name(text: str) -> str | None:
    m = re.search(r"\bfor (?:my|our|the|a) ([a-zA-Z][\w\s'-]{2,40}?)(?=\s+(?:before|by|until|in|on)\b|[.,!]|$)",
                  text, re.I)
    if not m:
        m = re.search(r"\bpara (?:a |o |minha |meu |nossa |nosso )([^\W\d][\w\s'-]{2,40}?)(?=\s+(?:at[ée]|antes|em|no|na)\b|[.,!]|$)",
                      text, re.I)
    if not m:
        return None
    name = m.group(1).strip()
    return name[0].upper() + name[1:]


def parse(text: str, today: date, awaiting_fields: list[str] | None = None) -> StructuredIntent:
    """Extracts only what is stated in {text}."""
    fields: dict = {}
    mentions = money_mentions(text)
    for mention in mentions:
        role = classify(text, mention, mentions)
        if role and role not in fields:
            fields[role] = mention.amount
            if role == "targetAmount":
                fields["targetCurrency"] = mention.currency
            elif "sourceCurrency" not in fields:
                fields["sourceCurrency"] = mention.currency

    # A lone amount answering a pending question ("R$32.000", "32000") fills that field.
    amount_fields = [f for f in (awaiting_fields or []) if f in
                     ("targetAmount", "maxBudget", "maxTransactionAmount", "approvalThreshold")]
    if len(amount_fields) == 1 and amount_fields[0] not in fields:
        amounts = [m.amount for m in mentions if classify(text, m, mentions) is None]
        if not mentions:
            bare = [parse_number(x) for x in re.findall(_NUM, text, re.I)]
            amounts = [n for n in bare if n and n > 1]
        if len(amounts) == 1:
            fields[amount_fields[0]] = amounts[0]
            if mentions:
                cur = mentions[0].currency
                fields.setdefault("targetCurrency" if amount_fields[0] == "targetAmount" else "sourceCurrency", cur)

    deadline = parse_deadline(text, today)
    if deadline:
        fields["deadline"] = deadline

    rate, style = parse_target_rate(text)
    if rate:
        fields["targetRate"] = rate if style == "direct" else (Decimal(1) / rate).quantize(Decimal("0.00000001"))
        fields.setdefault("strategyType", "TARGET_RATE")

    if re.search(r"\b(autonomous\w*|automatically|without asking|on your own|autom[aá]tic\w*|aut[oô]nom\w*|"
                 r"sem me perguntar|sem perguntar)\b", text, re.I):
        fields["executionMode"] = "AUTONOMOUS"
    elif "approvalThreshold" in fields or re.search(r"\b(ask me|approve each|pergunte|aprovar cada)\b", text, re.I):
        fields["executionMode"] = "HUMAN_IN_THE_LOOP"

    if re.search(r"\b(multiple (transactions|conversions)|gradually|in parts|several|over time|"
                 r"v[áa]rias (transa[çc][õo]es|vezes|convers[õo]es)|aos poucos|gradualmente|em partes|parcelad\w*)\b",
                 text, re.I):
        fields["allowPartialExecution"] = True
        fields.setdefault("strategyType", "ACCUMULATE")
    if re.search(r"\b(all at once|single (transaction|conversion)|one go|de uma vez s[óo]|numa [úu]nica|"
                 r"uma [úu]nica (transa[çc][ãa]o|vez))\b", text, re.I):
        fields["allowPartialExecution"] = False
    if re.search(r"\b(no matter what|guarantee|must have|de qualquer jeito|garantir|sem falta)\b", text, re.I):
        fields.setdefault("strategyType", "DEADLINE_AWARE")

    name = parse_name(text)
    if name:
        fields["name"] = name
    return StructuredIntent.model_validate(fields)
