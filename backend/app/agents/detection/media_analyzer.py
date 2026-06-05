"""
MediaAnalyzer — Analyzes media/file metadata from accessibility events.

Scores risk based on file type, filename patterns, and surrounding chat context.
APKs and executables are always HIGH risk. Images in financial context are MEDIUM.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class MediaVerdict:
    """Result of media analysis."""
    is_suspicious: bool = False
    risk_score: float = 0.0          # 0.0 – 1.0
    risk_level: str = "safe"         # safe, low, medium, high
    media_type: str = "unknown"      # image, video, document, apk, link, audio
    flags: list[str] = field(default_factory=list)
    recommendation: str = "none"     # none, warn, block


# --- Dangerous file extensions ---
_EXECUTABLE_EXTS = {
    ".apk", ".exe", ".bat", ".cmd", ".msi", ".scr", ".pif", ".com",
    ".vbs", ".js", ".wsf", ".jar", ".ps1", ".sh",
}
_ARCHIVE_EXTS = {".zip", ".rar", ".7z", ".tar", ".gz"}
_DOCUMENT_EXTS = {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".rtf"}
_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"}
_VIDEO_EXTS = {".mp4", ".avi", ".mov", ".mkv", ".3gp", ".webm"}

# Patterns suggesting financial context (when paired with media)
_FINANCIAL_CONTEXT = re.compile(
    r"\b(payment|receipt|screenshot|proof|transfer|transaction|upi|"
    r"neft|rtgs|sent money|paid|amount|bank|credit|debit|balance|"
    r"account|statement|invoice|bill)\b",
    re.I,
)

# Patterns suggesting the scammer is asking for something
_REQUEST_PATTERNS = re.compile(
    r"\b(send|share|show|give|forward|attach|upload)\s+"
    r"(screenshot|proof|receipt|photo|image|picture|pic|snap|document|file)\b",
    re.I,
)

# Suspicious filename patterns
_SUSPICIOUS_FILENAME = re.compile(
    r"(bank.?update|kyc.?verif|account.?alert|security.?patch|"
    r"whatsapp.?update|telegram.?premium|free.?recharge|lottery|"
    r"reward|winner|claim|prize|offer)",
    re.I,
)


class MediaAnalyzer:
    """Analyzes media type indicators from chat context."""

    def analyze(
        self,
        media_type: str = "",
        filename: str = "",
        surrounding_text: str = "",
    ) -> MediaVerdict:
        """
        Analyze media/file risk based on type, filename, and chat context.

        Args:
            media_type: 'image', 'video', 'document', 'apk', 'link', 'audio', or ''
            filename: e.g., 'payment_proof.jpg', 'update.apk'
            surrounding_text: Text around the media in the chat

        Returns:
            MediaVerdict with risk assessment
        """
        verdict = MediaVerdict(media_type=media_type or "unknown")
        ext = self._get_extension(filename)

        # --- APK / Executable: ALWAYS HIGH RISK ---
        if media_type == "apk" or ext in _EXECUTABLE_EXTS:
            verdict.is_suspicious = True
            verdict.risk_score = 0.95
            verdict.risk_level = "high"
            verdict.recommendation = "block"
            verdict.flags.append(f"Executable file detected: {ext or 'APK'}")

            if _SUSPICIOUS_FILENAME.search(filename):
                verdict.flags.append(f"Suspicious filename: {filename}")
                verdict.risk_score = 1.0

            logger.warning(
                "HIGH RISK media: type=%s, file=%s, flags=%s",
                media_type, filename, verdict.flags,
            )
            return verdict

        # --- Archives: MEDIUM-HIGH (could contain executables) ---
        if ext in _ARCHIVE_EXTS:
            verdict.is_suspicious = True
            verdict.risk_score = 0.65
            verdict.risk_level = "medium"
            verdict.recommendation = "warn"
            verdict.flags.append(f"Archive file: {ext} (may contain executables)")

            if _SUSPICIOUS_FILENAME.search(filename):
                verdict.risk_score = 0.85
                verdict.risk_level = "high"
                verdict.flags.append(f"Suspicious archive name: {filename}")

            return verdict

        # --- Documents: Context-dependent ---
        if media_type == "document" or ext in _DOCUMENT_EXTS:
            verdict.risk_score = 0.25
            verdict.risk_level = "low"

            if _SUSPICIOUS_FILENAME.search(filename):
                verdict.is_suspicious = True
                verdict.risk_score = 0.60
                verdict.risk_level = "medium"
                verdict.recommendation = "warn"
                verdict.flags.append(f"Suspicious document name: {filename}")

            if _FINANCIAL_CONTEXT.search(surrounding_text):
                verdict.risk_score = min(1.0, verdict.risk_score + 0.20)
                verdict.flags.append("Document shared in financial context")
                if verdict.risk_score >= 0.45:
                    verdict.is_suspicious = True
                    verdict.risk_level = "medium"
                    verdict.recommendation = "warn"

            return verdict

        # --- Images: Check for fake proof context ---
        if media_type == "image" or ext in _IMAGE_EXTS:
            verdict.risk_score = 0.10
            verdict.risk_level = "safe"

            if _FINANCIAL_CONTEXT.search(surrounding_text):
                verdict.risk_score = 0.45
                verdict.risk_level = "medium"
                verdict.is_suspicious = True
                verdict.recommendation = "warn"
                verdict.flags.append("Image shared in financial/payment context — possible fake proof")

            if _SUSPICIOUS_FILENAME.search(filename):
                verdict.risk_score = min(1.0, verdict.risk_score + 0.25)
                verdict.flags.append(f"Suspicious image name: {filename}")

            return verdict

        # --- Videos: Usually low risk unless financial ---
        if media_type == "video" or ext in _VIDEO_EXTS:
            verdict.risk_score = 0.10
            verdict.risk_level = "safe"

            if _FINANCIAL_CONTEXT.search(surrounding_text):
                verdict.risk_score = 0.35
                verdict.risk_level = "low"
                verdict.flags.append("Video in financial context")

            return verdict

        # --- Links: Delegate to link_analyzer (handled elsewhere) ---
        if media_type == "link":
            verdict.risk_score = 0.30
            verdict.risk_level = "low"
            verdict.flags.append("Link detected — analyzed separately by link_analyzer")
            return verdict

        # --- Audio: Low risk ---
        if media_type == "audio":
            verdict.risk_score = 0.05
            verdict.risk_level = "safe"
            return verdict

        # --- Unknown media type: check filename ---
        if filename:
            if ext in _EXECUTABLE_EXTS:
                return self.analyze("apk", filename, surrounding_text)
            if ext in _DOCUMENT_EXTS:
                return self.analyze("document", filename, surrounding_text)
            if ext in _IMAGE_EXTS:
                return self.analyze("image", filename, surrounding_text)

        return verdict

    @staticmethod
    def _get_extension(filename: str) -> str:
        """Extract lowercase file extension."""
        if not filename:
            return ""
        parts = filename.rsplit(".", 1)
        if len(parts) == 2:
            return f".{parts[1].lower()}"
        return ""
