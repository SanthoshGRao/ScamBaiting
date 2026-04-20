from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel


class ScammerRegisterRequest(BaseModel):
    phone_number: str
    last_message: str = ""
    risk_level: str = "HIGH"
    is_active: bool = True


class ScammerHistoryItem(BaseModel):
    role: str
    content: str
    timestamp: datetime


class ScammerHistoryResponse(BaseModel):
    phone_number: str
    ai_enabled: bool
    history: list[ScammerHistoryItem]


class ActivateAIRequest(BaseModel):
    enabled: bool = True
