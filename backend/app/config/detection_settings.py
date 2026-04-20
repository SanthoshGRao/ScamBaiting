"""
Detection Settings — Pydantic BaseSettings configuration for DetectionAgent.

Loads from environment variables with sensible defaults.
Category config loaded from JSON file.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from pydantic import Field
from pydantic_settings import BaseSettings

logger = logging.getLogger(__name__)

# Resolve config directory relative to this file
_CONFIG_DIR = Path(__file__).parent.parent / "config"


class DetectionSettings(BaseSettings):
    """DetectionAgent pipeline configuration."""

    # --- Thresholds ---
    detection_threshold: float = Field(
        default=0.65,
        ge=0.0, le=1.0,
        description="Minimum confidence to flag as scam"
    )
    high_risk_threshold: float = Field(
        default=0.80,
        ge=0.0, le=1.0,
        description="Confidence threshold for high risk"
    )
    medium_risk_threshold: float = Field(
        default=0.50,
        ge=0.0, le=1.0,
        description="Confidence threshold for medium risk"
    )
    low_risk_threshold: float = Field(
        default=0.30,
        ge=0.0, le=1.0,
        description="Confidence threshold for low risk"
    )

    # --- Score Weights ---
    rule_weight: float = Field(
        default=0.30,
        ge=0.0, le=1.0,
        description="Weight for rule-based score in hybrid mode"
    )
    llm_weight: float = Field(
        default=0.70,
        ge=0.0, le=1.0,
        description="Weight for LLM score in hybrid mode"
    )

    # --- LLM Settings ---
    llm_provider: str = Field(
        default="groq",
        description="Primary detection LLM provider"
    )
    llm_model: str = Field(
        default="gpt-4.1-mini",
        description="LLM model identifier"
    )
    llm_timeout_seconds: float = Field(
        default=5.0,
        description="LLM API call timeout"
    )
    llm_max_retries: int = Field(
        default=1,
        description="Max retries for LLM calls"
    )

    # --- Privacy ---
    privacy_mode: bool = Field(
        default=False,
        description="If True, skip all cloud LLM calls"
    )

    # --- Normalizer ---
    max_text_length: int = Field(
        default=5000,
        description="Max characters to process"
    )
    default_language: str = Field(
        default="en",
        description="Fallback language if detection fails"
    )

    # --- Category Config ---
    category_config_path: str = Field(
        default=str(_CONFIG_DIR / "scam_categories.json"),
        description="Path to category config JSON"
    )

    model_config = {
        "env_prefix": "DETECTION_",
        "env_file": ".env",
        "extra": "ignore",
    }


def load_category_config(path: str | None = None) -> list[dict[str, Any]]:
    """Load scam category definitions from JSON config file."""
    settings = DetectionSettings()
    config_path = Path(path or settings.category_config_path)

    if not config_path.exists():
        logger.warning(
            "Category config not found at %s, using empty categories",
            config_path
        )
        return []

    try:
        with open(config_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        categories = data.get("categories", [])
        logger.info("Loaded %d scam categories from %s", len(categories), config_path)
        return categories
    except (json.JSONDecodeError, OSError) as e:
        logger.error("Failed to load category config: %s", e)
        return []
