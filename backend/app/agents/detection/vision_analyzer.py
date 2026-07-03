"""
VisionAnalyzer — Understands the CONTENT of images a scammer sends.

Unlike MediaAnalyzer (which only inspects filename/extension metadata), this
module actually "reads" the pixels: it captions the image and extracts any text
via OCR, so the baiting agent can reply in context to what the scammer sent
(e.g. a fake payment receipt, a QR code, an ID card, a threat screenshot).

Uses local open-source models:
  - Captioning: BLIP (transformers) if available.
  - OCR: pytesseract (Tesseract) if available.

Every heavy dependency is imported LAZILY and guarded. If a model or its weights
are not installed, the analyzer degrades gracefully to a metadata-only summary
instead of crashing — so the backend stays importable and deployable everywhere.
"""

from __future__ import annotations

import base64
import binascii
import io
import logging
import re
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


# --- Cached lazy singletons for the heavy models ---
_blip_processor = None
_blip_model = None
_blip_load_failed = False
_tesseract_checked = False
_tesseract_available = False


@dataclass
class ImageUnderstanding:
    """What the analyzer could work out about an inbound image."""
    ok: bool = False                    # True if we actually inspected pixels
    method: str = "unavailable"         # blip_ocr | ocr_only | caption_only | metadata_fallback | unavailable
    caption: str = ""                   # natural-language description of the image
    ocr_text: str = ""                  # text read off the image
    looks_like_payment: bool = False
    looks_like_qr: bool = False
    summary: str = ""                   # one-line human-readable summary for the reply context
    flags: list[str] = field(default_factory=list)


_PAYMENT_HINTS = re.compile(
    r"\b(paid|payment|transfer|transaction|utr|imps|neft|rtgs|upi|"
    r"success|successful|debited|credited|receipt|₹|rs\.?|inr|amount|balance)\b",
    re.I,
)
_QR_HINTS = re.compile(r"\b(qr|scan|scanner)\b", re.I)


def _decode_image(image_base64: str):
    """Decode a base64 (optionally data-URI) string into a PIL RGB image."""
    from PIL import Image  # Pillow is already a hard dependency

    data = image_base64.strip()
    if data.startswith("data:"):
        # strip "data:image/...;base64," prefix
        _, _, data = data.partition(",")
    try:
        raw = base64.b64decode(data, validate=False)
    except (binascii.Error, ValueError) as e:
        raise ValueError(f"invalid base64 image: {e}") from e
    return Image.open(io.BytesIO(raw)).convert("RGB")


def _run_ocr(img) -> str:
    """Extract text via Tesseract if available; empty string otherwise."""
    global _tesseract_checked, _tesseract_available
    if not _tesseract_checked:
        _tesseract_checked = True
        try:
            import pytesseract  # noqa: F401
            # A missing Tesseract binary raises only at call time, so probe once.
            pytesseract.get_tesseract_version()
            _tesseract_available = True
        except Exception as e:  # ImportError, EnvironmentError, etc.
            logger.info("OCR unavailable (pytesseract/tesseract not usable): %s", e)
            _tesseract_available = False

    if not _tesseract_available:
        return ""

    try:
        import pytesseract
        text = pytesseract.image_to_string(img) or ""
        return re.sub(r"[ \t]+", " ", text).strip()
    except Exception as e:
        logger.warning("OCR failed: %s", e)
        return ""


def _run_caption(img) -> str:
    """Caption the image via BLIP if available; empty string otherwise."""
    global _blip_processor, _blip_model, _blip_load_failed
    if _blip_load_failed:
        return ""

    if _blip_model is None:
        try:
            from transformers import BlipProcessor, BlipForConditionalGeneration
            model_name = "Salesforce/blip-image-captioning-base"
            _blip_processor = BlipProcessor.from_pretrained(model_name)
            _blip_model = BlipForConditionalGeneration.from_pretrained(model_name)
            _blip_model.eval()
            logger.info("BLIP caption model loaded: %s", model_name)
        except Exception as e:
            logger.info("Captioning unavailable (BLIP not loadable): %s", e)
            _blip_load_failed = True
            return ""

    try:
        inputs = _blip_processor(img, return_tensors="pt")
        out = _blip_model.generate(**inputs, max_new_tokens=40)
        caption = _blip_processor.decode(out[0], skip_special_tokens=True)
        return (caption or "").strip()
    except Exception as e:
        logger.warning("Captioning failed: %s", e)
        return ""


def analyze_image(image_base64: str, surrounding_text: str = "") -> ImageUnderstanding:
    """Inspect the content of a scammer-sent image.

    Args:
        image_base64: base64 (or data-URI) encoded image bytes.
        surrounding_text: nearby chat text, used as a fallback context signal.

    Returns:
        ImageUnderstanding — always returned, never raises for a bad image.
    """
    result = ImageUnderstanding()

    if not image_base64:
        return result

    try:
        img = _decode_image(image_base64)
    except Exception as e:
        logger.warning("Could not decode inbound image: %s", e)
        result.method = "metadata_fallback"
        result.summary = "the scammer sent an image (could not open it)"
        return result

    caption = _run_caption(img)
    ocr_text = _run_ocr(img)

    result.caption = caption
    result.ocr_text = ocr_text

    if caption and ocr_text:
        result.method = "blip_ocr"
    elif caption:
        result.method = "caption_only"
    elif ocr_text:
        result.method = "ocr_only"
    else:
        result.method = "metadata_fallback"

    result.ok = result.method in ("blip_ocr", "caption_only", "ocr_only")

    combined = f"{caption} {ocr_text} {surrounding_text}"
    result.looks_like_payment = bool(_PAYMENT_HINTS.search(combined))
    result.looks_like_qr = bool(_QR_HINTS.search(caption)) or bool(_QR_HINTS.search(surrounding_text))

    if result.looks_like_payment:
        result.flags.append("payment_proof_image")
    if result.looks_like_qr:
        result.flags.append("qr_code_image")

    result.summary = _build_summary(result)
    return result


def _build_summary(u: ImageUnderstanding) -> str:
    """Turn the raw signals into one short line the reply prompt can consume."""
    parts: list[str] = []
    if u.caption:
        parts.append(f"it appears to show: {u.caption}")
    if u.ocr_text:
        # Keep OCR short so it doesn't blow up the prompt.
        snippet = u.ocr_text.replace("\n", " ")
        if len(snippet) > 200:
            snippet = snippet[:200].rstrip() + "…"
        parts.append(f'text visible in it reads: "{snippet}"')
    if u.looks_like_payment:
        parts.append("looks like a payment/receipt screenshot")
    if u.looks_like_qr:
        parts.append("looks like a QR/scan code")

    if not parts:
        return "the scammer sent an image (contents unclear)"
    return "the scammer sent an image — " + "; ".join(parts)
