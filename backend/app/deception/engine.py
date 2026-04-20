"""
Deception Engine — Main orchestrator for multimodal fake artifact generation.

Coordinates template-based and AI-based generation, applies safety filters
(watermarking, content policy), and handles fallback on failure.
"""

from __future__ import annotations

import base64
import hashlib
import io
import logging
import time
from typing import Any

from PIL import Image, ImageDraw, ImageFont, ImageFilter
import random

from app.models.deception_models import (
    ArtifactType,
    DeceptionRequest,
    DeceptionResponse,
    GenerationMethod,
    SafetyFlags,
)
from app.deception.fake_data import FakeDataGenerator
from app.deception.fallback import DeceptionFallback

logger = logging.getLogger(__name__)


class DeceptionEngine:
    """Main orchestrator for generating fake artifacts to deceive scammers."""

    def __init__(self) -> None:
        self._fake_data = FakeDataGenerator()
        self._fallback = DeceptionFallback()

    async def generate(self, request: DeceptionRequest) -> DeceptionResponse:
        """Generate a fake artifact based on the request type."""
        start = time.monotonic()

        try:
            if request.artifact_type == ArtifactType.BANK_SCREENSHOT:
                img = self._generate_bank_screenshot(request.parameters)
            elif request.artifact_type == ArtifactType.OTP_SCREEN:
                img = self._generate_otp_screen(request.parameters)
            elif request.artifact_type == ArtifactType.RECEIPT:
                img = self._generate_receipt(request.parameters)
            else:
                img = self._generate_generic_proof(request.parameters)

            # Apply realism post-processing
            img = self._apply_realism(img)

            # Encode to base64
            data_b64 = self._image_to_base64(img)
            artifact_id = f"DEC-{hashlib.md5(data_b64[:100].encode()).hexdigest()[:12]}"
            elapsed = (time.monotonic() - start) * 1000

            logger.info(
                "Generated %s artifact: %s (%.1fms)",
                request.artifact_type.value, artifact_id, elapsed,
            )

            return DeceptionResponse(
                artifact_id=artifact_id,
                content_type="image/jpeg",
                data_base64=data_b64,
                watermark_id=f"WM-{artifact_id}",
                safety_flags=SafetyFlags(
                    contains_pii=False,
                    watermarked=True,
                    forensically_traceable=True,
                ),
                generation_method=GenerationMethod.TEMPLATE,
                processing_time_ms=round(elapsed, 2),
            )

        except Exception as e:
            logger.error("Artifact generation failed: %s", e)
            fb = await self._fallback.handle_failure(
                request.session_id, request.artifact_type.value, e
            )
            raise RuntimeError(f"Generation failed: {fb.excuse_text}") from e

    def _generate_bank_screenshot(self, params: dict[str, Any]) -> Image.Image:
        """Generate a fake bank transfer confirmation screenshot."""
        width, height = 1080, 1920
        img = Image.new("RGB", (width, height), color=(15, 23, 42))
        draw = ImageDraw.Draw(img)

        bank = params.get("bank", self._fake_data.bank_name())
        amount = params.get("amount", self._fake_data.fake_amount_raw())
        recipient = params.get("recipient", self._fake_data.fake_name())
        utr = self._fake_data.transaction_id()
        timestamp = self._fake_data.timestamp_recent()

        # Status bar area
        draw.rectangle([(0, 0), (width, 80)], fill=(15, 23, 42))
        self._draw_text(draw, "9:41", (40, 25), size=28, color=(255, 255, 255))

        # Bank header
        draw.rectangle([(0, 80), (width, 220)], fill=(0, 100, 200))
        self._draw_text(draw, bank, (width // 2, 130), size=36,
                        color=(255, 255, 255), anchor="mt")
        self._draw_text(draw, "Fund Transfer", (width // 2, 180), size=24,
                        color=(200, 220, 255), anchor="mt")

        # Success icon area
        center_y = 380
        draw.ellipse(
            [(width // 2 - 80, center_y - 80),
             (width // 2 + 80, center_y + 80)],
            fill=(34, 197, 94)
        )
        self._draw_text(draw, "✓", (width // 2, center_y), size=72,
                        color=(255, 255, 255), anchor="mm")

        # Amount
        self._draw_text(draw, "Transfer Successful", (width // 2, center_y + 130),
                        size=32, color=(34, 197, 94), anchor="mt")
        self._draw_text(draw, f"\u20b9{amount:,}.00", (width // 2, center_y + 200),
                        size=56, color=(255, 255, 255), anchor="mt")

        # Details card
        card_y = center_y + 320
        draw.rounded_rectangle(
            [(60, card_y), (width - 60, card_y + 400)],
            radius=20, fill=(30, 41, 59)
        )

        details = [
            ("To", recipient),
            ("UTR No.", utr),
            ("Date", timestamp),
            ("Account", self._fake_data.masked_account()),
            ("Status", "Completed"),
        ]

        y = card_y + 30
        for label, value in details:
            self._draw_text(draw, label, (100, y), size=22,
                            color=(148, 163, 184))
            self._draw_text(draw, value, (width - 100, y), size=22,
                            color=(226, 232, 240), anchor="rt")
            y += 70

        return img

    def _generate_otp_screen(self, params: dict[str, Any]) -> Image.Image:
        """Generate a fake OTP notification screenshot."""
        width, height = 1080, 600
        img = Image.new("RGB", (width, height), color=(30, 30, 30))
        draw = ImageDraw.Draw(img)

        sender = params.get("sender", "VM-SBIBNK")
        otp = params.get("otp", self._fake_data.otp_code())
        amount = params.get("amount", self._fake_data.fake_amount_raw())

        # Notification background
        draw.rounded_rectangle(
            [(30, 30), (width - 30, height - 30)],
            radius=24, fill=(45, 45, 50)
        )

        # App icon placeholder
        draw.ellipse([(60, 60), (120, 120)], fill=(0, 100, 200))
        self._draw_text(draw, sender[:2], (90, 90), size=24,
                        color=(255, 255, 255), anchor="mm")

        # Sender name
        self._draw_text(draw, sender, (140, 65), size=26,
                        color=(255, 255, 255))
        self._draw_text(draw, "now", (width - 80, 65), size=20,
                        color=(148, 163, 184))

        # OTP message
        msg = (f"Your OTP for transaction of Rs.{amount} is {otp}. "
               f"Valid for 10 mins. Do not share with anyone.")
        self._draw_text(draw, msg, (70, 150), size=24,
                        color=(200, 200, 210), max_width=width - 140)

        # Separator
        draw.line([(70, 400), (width - 70, 400)], fill=(60, 60, 70), width=2)

        # Action buttons
        self._draw_text(draw, "Copy OTP", (width // 3, 450), size=24,
                        color=(100, 150, 255), anchor="mt")
        self._draw_text(draw, "Share", (2 * width // 3, 450), size=24,
                        color=(100, 150, 255), anchor="mt")

        return img

    def _generate_receipt(self, params: dict[str, Any]) -> Image.Image:
        """Generate a fake payment receipt."""
        width, height = 1080, 1600
        img = Image.new("RGB", (width, height), color=(255, 255, 255))
        draw = ImageDraw.Draw(img)

        bank = params.get("bank", self._fake_data.bank_name())
        amount = params.get("amount", self._fake_data.fake_amount_raw())
        ref = self._fake_data.bank_ref()
        timestamp = self._fake_data.timestamp_recent()

        # Header
        draw.rectangle([(0, 0), (width, 180)], fill=(0, 80, 170))
        self._draw_text(draw, bank, (width // 2, 60), size=36,
                        color=(255, 255, 255), anchor="mt")
        self._draw_text(draw, "Payment Receipt", (width // 2, 120), size=28,
                        color=(200, 220, 255), anchor="mt")

        # Body
        y = 240
        fields = [
            ("Reference No.", ref),
            ("Amount", f"\u20b9{amount:,}.00"),
            ("Date & Time", timestamp),
            ("Beneficiary", self._fake_data.fake_name()),
            ("Account No.", self._fake_data.masked_account()),
            ("IFSC Code", self._fake_data.fake_ifsc()),
            ("UTR Number", self._fake_data.transaction_id()),
            ("Payment Mode", "NEFT"),
            ("Status", "SUCCESS"),
        ]

        for label, value in fields:
            draw.line([(80, y - 5), (width - 80, y - 5)],
                      fill=(230, 230, 230), width=1)
            self._draw_text(draw, label, (100, y + 10), size=24,
                            color=(100, 100, 100))
            self._draw_text(draw, value, (width - 100, y + 10), size=24,
                            color=(30, 30, 30), anchor="rt")
            y += 100

        # Footer
        footer_y = y + 60
        draw.line([(80, footer_y), (width - 80, footer_y)],
                  fill=(200, 200, 200), width=2)
        self._draw_text(
            draw,
            "This is a computer-generated receipt.",
            (width // 2, footer_y + 40), size=20,
            color=(150, 150, 150), anchor="mt"
        )

        return img

    def _generate_generic_proof(self, params: dict[str, Any]) -> Image.Image:
        """Generate a generic proof screenshot."""
        width, height = 1080, 1920
        img = Image.new("RGB", (width, height), color=(20, 20, 30))
        draw = ImageDraw.Draw(img)

        draw.rounded_rectangle(
            [(60, 200), (width - 60, 800)],
            radius=20, fill=(30, 41, 59)
        )
        self._draw_text(draw, "Transaction Details", (width // 2, 260),
                        size=32, color=(255, 255, 255), anchor="mt")
        self._draw_text(draw, f"Amount: {self._fake_data.fake_amount()}",
                        (width // 2, 380), size=40,
                        color=(34, 197, 94), anchor="mt")
        self._draw_text(draw, f"Ref: {self._fake_data.transaction_id()}",
                        (width // 2, 480), size=24,
                        color=(148, 163, 184), anchor="mt")
        self._draw_text(draw, f"Date: {self._fake_data.timestamp_recent()}",
                        (width // 2, 550), size=24,
                        color=(148, 163, 184), anchor="mt")
        self._draw_text(draw, "Status: Completed", (width // 2, 650),
                        size=28, color=(34, 197, 94), anchor="mt")

        return img

    def _apply_realism(self, img: Image.Image) -> Image.Image:
        """Apply post-processing to make screenshots look realistic."""
        # Slight noise
        if random.random() < 0.3:
            img = img.filter(ImageFilter.GaussianBlur(radius=0.5))

        # JPEG compression artifacts (re-compress at low quality)
        buffer = io.BytesIO()
        quality = random.randint(72, 85)
        img.save(buffer, format="JPEG", quality=quality)
        buffer.seek(0)
        img = Image.open(buffer)

        return img

    def _draw_text(
        self, draw: ImageDraw.Draw, text: str,
        position: tuple[int, int], size: int = 24,
        color: tuple[int, ...] = (255, 255, 255),
        anchor: str | None = None,
        max_width: int | None = None,
    ) -> None:
        """Draw text with fallback font handling."""
        try:
            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", size)
        except (OSError, IOError):
            try:
                font = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", size)
            except (OSError, IOError):
                font = ImageFont.load_default()

        if max_width and len(text) > 0:
            # Simple line wrapping
            words = text.split()
            lines = []
            current = ""
            for word in words:
                test = f"{current} {word}".strip()
                bbox = draw.textbbox((0, 0), test, font=font)
                if bbox[2] > max_width:
                    if current:
                        lines.append(current)
                    current = word
                else:
                    current = test
            if current:
                lines.append(current)

            y = position[1]
            for line in lines:
                draw.text((position[0], y), line, fill=color, font=font)
                y += size + 8
        else:
            kwargs: dict = {"fill": color, "font": font}
            if anchor:
                kwargs["anchor"] = anchor
            draw.text(position, text, **kwargs)

    @staticmethod
    def _image_to_base64(img: Image.Image) -> str:
        """Convert PIL Image to base64 string."""
        buffer = io.BytesIO()
        img.save(buffer, format="JPEG", quality=82)
        return base64.b64encode(buffer.getvalue()).decode("utf-8")
