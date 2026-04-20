from __future__ import annotations

from datetime import datetime

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    username: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    hashed_password: Mapped[str] = mapped_column(String(255))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class SenderProfile(Base):
    __tablename__ = "sender_profiles"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    sender_id: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    scam_count: Mapped[int] = mapped_column(Integer, default=0)
    safe_count: Mapped[int] = mapped_column(Integer, default=0)
    risk_score: Mapped[float] = mapped_column(Float, default=0.5)
    risk_level: Mapped[str] = mapped_column(String(20), default="LOW")
    last_message: Mapped[str] = mapped_column(Text, default="")
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    ai_enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class DetectionHistory(Base):
    __tablename__ = "detection_history"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    message_id: Mapped[str] = mapped_column(String(120), unique=True, index=True)
    sender_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    text: Mapped[str] = mapped_column(Text)
    category: Mapped[str] = mapped_column(String(100), default="unknown")
    confidence: Mapped[float] = mapped_column(Float, default=0.0)
    risk_level: Mapped[str] = mapped_column(String(20), default="safe")
    is_scam: Mapped[bool] = mapped_column(Boolean, default=False)
    reasoning: Mapped[str] = mapped_column(Text, default="")
    explanation: Mapped[str] = mapped_column(Text, default="")
    detection_mode: Mapped[str] = mapped_column(String(32), default="rule_only")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class BaitingSession(Base):
    __tablename__ = "baiting_sessions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    session_id: Mapped[str] = mapped_column(String(120), unique=True, index=True)
    sender_id: Mapped[str] = mapped_column(String(255), index=True)
    persona: Mapped[str] = mapped_column(String(120), default="curious_user")
    goal: Mapped[str] = mapped_column(String(120), default="waste_time")
    current_strategy: Mapped[str] = mapped_column(String(120), default="CONFUSION")
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    messages_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class AnalyticsRecord(Base):
    __tablename__ = "analytics_records"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    session_id: Mapped[str] = mapped_column(String(120), index=True)
    scam_category: Mapped[str] = mapped_column(String(100), default="unknown")
    successful: Mapped[bool] = mapped_column(Boolean, default=False)
    estimated_money_saved: Mapped[float] = mapped_column(Float, default=0.0)
    time_wasted_seconds: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class Feedback(Base):
    __tablename__ = "feedback"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    message_id: Mapped[str] = mapped_column(String(120), index=True)
    label: Mapped[str] = mapped_column(String(16))
    notes: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class ScammerMessage(Base):
    __tablename__ = "scammer_messages"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    sender_id: Mapped[str] = mapped_column(String(255), index=True)
    role: Mapped[str] = mapped_column(String(20), default="user")
    content: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class TrackingLink(Base):
    """A tracking link sent to a scammer during a baiting session."""
    __tablename__ = "tracking_links"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    token: Mapped[str] = mapped_column(String(16), unique=True, index=True)
    session_id: Mapped[str] = mapped_column(String(120), index=True)
    sender_id: Mapped[str] = mapped_column(String(255), index=True)
    context_type: Mapped[str] = mapped_column(String(50), default="payment_receipt")
    clicked: Mapped[bool] = mapped_column(Boolean, default=False)
    click_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class LocationCapture(Base):
    """Captured location data when a scammer clicks a tracking link."""
    __tablename__ = "location_captures"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    tracking_token: Mapped[str] = mapped_column(String(16), index=True)
    ip_address: Mapped[str] = mapped_column(String(45), default="")
    latitude: Mapped[float | None] = mapped_column(Float, nullable=True)
    longitude: Mapped[float | None] = mapped_column(Float, nullable=True)
    accuracy_meters: Mapped[float | None] = mapped_column(Float, nullable=True)
    user_agent: Mapped[str] = mapped_column(Text, default="")
    city: Mapped[str] = mapped_column(String(100), default="")
    region: Mapped[str] = mapped_column(String(100), default="")
    country: Mapped[str] = mapped_column(String(100), default="")
    isp: Mapped[str] = mapped_column(String(200), default="")
    captured_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

