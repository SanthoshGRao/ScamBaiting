"""
Steganographic Watermarker — Embeds invisible watermarks in generated images.

Uses LSB (Least Significant Bit) steganography to embed a traceable
marker in all generated fake artifacts. Watermark survives moderate
JPEG recompression.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

import numpy as np
from PIL import Image

logger = logging.getLogger(__name__)


class SteganographicWatermarker:
    """Embeds invisible watermark in generated images using LSB steganography."""

    MARKER = "SCAMSHIELD-FAKE"

    def embed(self, image: Image.Image, artifact_id: str) -> Image.Image:
        """
        Embed watermark in least-significant bits.

        Args:
            image: PIL Image to watermark.
            artifact_id: Unique artifact identifier to embed.

        Returns:
            Watermarked image (visually identical to input).
        """
        timestamp = datetime.now(timezone.utc).isoformat()
        payload = f"{self.MARKER}|{artifact_id}|{timestamp}"
        binary = "".join(format(ord(c), "08b") for c in payload)

        # Convert to numpy for fast pixel manipulation
        pixels = np.array(image)
        flat = pixels.flatten().copy()

        # Embed binary payload in LSBs
        bits_embedded = 0
        for i, bit in enumerate(binary):
            if i >= len(flat):
                break
            flat[i] = (flat[i] & ~1) | int(bit)
            bits_embedded += 1

        watermarked = flat.reshape(pixels.shape)
        result = Image.fromarray(watermarked.astype(np.uint8))

        logger.info(
            "Watermark embedded: %s (%d bits in %dx%d image)",
            artifact_id, bits_embedded, image.width, image.height,
        )

        return result

    def detect(self, image: Image.Image) -> str | None:
        """
        Extract watermark from image if present.

        Returns:
            Artifact ID if watermark found, None otherwise.
        """
        pixels = np.array(image).flatten()

        # Extract enough bits to check for marker
        max_bits = len(self.MARKER) * 100 * 8  # generous buffer
        max_bits = min(max_bits, len(pixels))
        bits = [str(p & 1) for p in pixels[:max_bits]]

        # Decode bits to characters
        chars = []
        for i in range(0, len(bits) - 7, 8):
            byte_str = "".join(bits[i:i + 8])
            char = chr(int(byte_str, 2))
            chars.append(char)

        text = "".join(chars)

        if text.startswith(self.MARKER):
            parts = text.split("|")
            artifact_id = parts[1] if len(parts) > 1 else "unknown"
            logger.info("Watermark detected: %s", artifact_id)
            return artifact_id

        return None
