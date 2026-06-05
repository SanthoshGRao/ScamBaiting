"""
Link Analyzer — Deep URL analysis with unshortening and phishing heuristics.

Analyzes URLs for suspicious TLDs, typo-squatting, brand impersonation,
URL shortener resolution, and structural anomalies.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from urllib.parse import urlparse, parse_qs

logger = logging.getLogger(__name__)


SUSPICIOUS_TLDS = {
    "tk", "ml", "ga", "cf", "gq", "top", "buzz", "xyz", "click",
    "loan", "work", "date", "racing", "win", "bid", "stream",
    "download", "review", "country", "party", "cricket", "science",
}

BRAND_KEYWORDS = {
    "microsoft", "google", "apple", "sbi", "hdfc", "icici", "paypal",
    "amazon", "flipkart", "phonepe", "paytm", "gpay", "whatsapp",
    "telegram", "facebook", "instagram", "netflix", "razorpay",
    "bhim", "upi", "yesbank", "kotak", "axis", "idfc",
}

# Known URL shorteners
SHORTENER_DOMAINS = {
    "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd",
    "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at", "tiny.cc",
    "rb.gy", "surl.li", "t.ly",
}

# Typo-squatting patterns (character substitutions)
TYPO_PATTERNS = [
    (r"rn", "m"),       # rnicrosoft → microsoft
    (r"0", "o"),        # g00gle → google
    (r"1", "l"),        # paypa1 → paypal
    (r"vv", "w"),       # vvhatsapp → whatsapp
]


@dataclass
class LinkVerdict:
    """Result of link analysis."""
    is_suspicious: bool = False
    risk_score: float = 0.0
    flags: list[str] = field(default_factory=list)
    is_shortened: bool = False
    resolved_url: str | None = None
    is_phishing: bool = False
    is_malware_link: bool = False


def analyze_urls(urls: list[str]) -> tuple[float, list[str]]:
    """
    Analyze a list of URLs for suspicious indicators.

    Returns:
        (risk_score: 0.0-1.0, flags: list of human-readable warnings)
    """
    if not urls:
        return 0.0, []

    flags: list[str] = []
    risk = 0.0

    for url in urls:
        verdict = _analyze_single_url(url)
        risk += verdict.risk_score
        flags.extend(verdict.flags)

    return min(risk, 1.0), list(dict.fromkeys(flags))


def _analyze_single_url(url: str) -> LinkVerdict:
    """Deep analysis of a single URL."""
    verdict = LinkVerdict()

    # Normalize
    if not url.startswith("http"):
        url = f"http://{url}"

    try:
        parsed = urlparse(url)
    except Exception:
        verdict.flags.append("Malformed URL")
        verdict.risk_score = 0.3
        return verdict

    host = parsed.netloc.lower().strip(".")
    path = parsed.path.lower()
    if not host:
        return verdict

    # --- TLD check ---
    tld = host.split(".")[-1] if "." in host else ""
    if tld in SUSPICIOUS_TLDS:
        verdict.flags.append(f"Suspicious TLD: .{tld}")
        verdict.risk_score += 0.25
        verdict.is_suspicious = True

    # --- Domain length anomaly ---
    domain_parts = host.split(".")
    main_domain = ".".join(domain_parts[-2:]) if len(domain_parts) >= 2 else host
    if len(host) > 28:
        verdict.flags.append("Unusually long domain name")
        verdict.risk_score += 0.15

    # --- Subdomain depth (more than 3 levels = suspicious) ---
    if len(domain_parts) > 3:
        verdict.flags.append(f"Deep subdomain nesting ({len(domain_parts)} levels)")
        verdict.risk_score += 0.15

    # --- IP address as domain ---
    if re.match(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$", host.split(":")[0]):
        verdict.flags.append("Direct IP address URL (no domain name)")
        verdict.risk_score += 0.30
        verdict.is_suspicious = True

    # --- URL shortener detection ---
    if main_domain in SHORTENER_DOMAINS or host in SHORTENER_DOMAINS:
        verdict.is_shortened = True
        verdict.flags.append(f"URL shortener detected: {host}")
        verdict.risk_score += 0.20
        verdict.is_suspicious = True

    # --- Typo-squatting detection ---
    for pattern, replacement in TYPO_PATTERNS:
        test_host = host.replace(pattern, replacement)
        if test_host != host:
            for brand in BRAND_KEYWORDS:
                if brand in test_host and brand not in host:
                    verdict.flags.append(f"Possible typo-squatting: {host} → {brand}")
                    verdict.risk_score += 0.40
                    verdict.is_phishing = True
                    verdict.is_suspicious = True

    # --- Brand keyword with separator (sbi-kyc-verify.com) ---
    for brand in BRAND_KEYWORDS:
        if brand in host:
            # Check if it's NOT the official domain
            official_hints = [f"{brand}.com", f"{brand}.in", f"{brand}.co.in", f"{brand}.org"]
            is_official = any(host.endswith(hint) and host.count(".") <= 2 for hint in official_hints)
            if not is_official and ("-" in host or host.count(".") > 2):
                verdict.flags.append(f"Brand impersonation: '{brand}' in non-official domain")
                verdict.risk_score += 0.30
                verdict.is_phishing = True
                verdict.is_suspicious = True

    # --- Suspicious path patterns ---
    phishing_paths = [
        r"/login", r"/verify", r"/secure", r"/update", r"/confirm",
        r"/kyc", r"/account", r"/banking", r"/otp", r"/reset",
        r"/wallet", r"/claim", r"/reward", r"/prize", r"/winner",
    ]
    for pp in phishing_paths:
        if re.search(pp, path, re.I):
            verdict.flags.append(f"Suspicious path keyword: {pp.strip('/')}")
            verdict.risk_score += 0.10
            break  # Only count once

    # --- Suspicious query parameters ---
    try:
        params = parse_qs(parsed.query)
        sensitive_params = {"token", "otp", "code", "pin", "password", "auth", "session"}
        found = sensitive_params & set(k.lower() for k in params.keys())
        if found:
            verdict.flags.append(f"Sensitive query params: {', '.join(found)}")
            verdict.risk_score += 0.15
            verdict.is_suspicious = True
    except Exception:
        pass

    # --- HTTP (no SSL) ---
    if parsed.scheme == "http" and not verdict.is_shortened:
        verdict.flags.append("No HTTPS (unencrypted connection)")
        verdict.risk_score += 0.10

    # --- @ symbol in URL (credential stuffing trick) ---
    if "@" in host or "@" in parsed.path:
        verdict.flags.append("@ symbol in URL (possible credential trick)")
        verdict.risk_score += 0.25
        verdict.is_suspicious = True

    verdict.risk_score = min(verdict.risk_score, 1.0)
    verdict.is_suspicious = verdict.is_suspicious or verdict.risk_score >= 0.35

    if verdict.risk_score >= 0.60:
        verdict.is_phishing = True

    return verdict


async def unshorten_url(url: str) -> str | None:
    """Attempt to resolve a shortened URL by following redirects."""
    try:
        import httpx
        async with httpx.AsyncClient(
            timeout=5.0, follow_redirects=True, max_redirects=5,
        ) as client:
            resp = await client.head(url)
            resolved = str(resp.url)
            if resolved != url:
                logger.info("Unshortened %s → %s", url, resolved)
                return resolved
    except Exception as e:
        logger.debug("URL unshortening failed for %s: %s", url, e)
    return None
