from __future__ import annotations

from urllib.parse import urlparse


SUSPICIOUS_TLDS = {"tk", "ml", "ga", "cf", "gq", "top", "buzz", "xyz", "click"}
BRAND_KEYWORDS = {"microsoft", "google", "apple", "sbi", "hdfc", "icici", "paypal", "amazon"}


def analyze_urls(urls: list[str]) -> tuple[float, list[str]]:
    if not urls:
        return 0.0, []

    flags: list[str] = []
    risk = 0.0

    for url in urls:
        parsed = urlparse(url if url.startswith("http") else f"http://{url}")
        host = parsed.netloc.lower().strip(".")
        if not host:
            continue

        tld = host.split(".")[-1] if "." in host else ""
        if tld in SUSPICIOUS_TLDS:
            flags.append(f"Suspicious TLD: .{tld}")
            risk += 0.25

        if len(host) > 28:
            flags.append("Domain length anomaly")
            risk += 0.2

        # Basic lookalike heuristic for rnicrosoft-style spoofing
        if "rnicrosoft" in host or "g00gle" in host or "paypa1" in host:
            flags.append("Possible typo-squatting domain")
            risk += 0.35

        if any(brand in host for brand in BRAND_KEYWORDS) and "-" in host:
            flags.append("Brand keyword with separator pattern")
            risk += 0.15

    return min(risk, 1.0), list(dict.fromkeys(flags))
