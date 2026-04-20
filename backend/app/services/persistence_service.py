from __future__ import annotations

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.db.models import AnalyticsRecord, DetectionHistory, Feedback, SenderProfile
from app.models.detection_models import DetectionVerdict


class PersistenceService:
    def __init__(self, db: Session) -> None:
        self.db = db

    def save_detection(self, verdict: DetectionVerdict, text: str, sender_id: str | None) -> None:
        row = DetectionHistory(
            message_id=verdict.message_id,
            sender_id=sender_id,
            text=text,
            category=verdict.category,
            confidence=verdict.confidence,
            risk_level=verdict.risk_level.value,
            is_scam=verdict.is_scam,
            reasoning=verdict.reasoning,
            explanation=verdict.explanation,
            detection_mode=verdict.detection_mode.value,
        )
        self.db.add(row)

        if sender_id:
            profile = self.db.query(SenderProfile).filter(SenderProfile.sender_id == sender_id).first()
            if not profile:
                profile = SenderProfile(
                    sender_id=sender_id,
                    scam_count=0,
                    safe_count=0,
                    risk_score=0.5,
                )
                self.db.add(profile)

            # Older rows or newly created instances can carry None before flush/defaults apply.
            profile.scam_count = int(profile.scam_count or 0)
            profile.safe_count = int(profile.safe_count or 0)

            if verdict.is_scam:
                profile.scam_count += 1
            else:
                profile.safe_count += 1
            total = max(profile.scam_count + profile.safe_count, 1)
            profile.risk_score = round(profile.scam_count / total, 4)

        self.db.commit()

    def get_sender_risk_score(self, sender_id: str | None) -> float:
        if not sender_id:
            return 0.5
        profile = self.db.query(SenderProfile).filter(SenderProfile.sender_id == sender_id).first()
        return profile.risk_score if profile else 0.5

    def save_feedback(self, message_id: str, label: str, notes: str = "") -> None:
        self.db.add(Feedback(message_id=message_id, label=label, notes=notes))
        self.db.commit()

    def analytics_summary(self) -> dict[str, float | int]:
        total = self.db.query(func.count(DetectionHistory.id)).scalar() or 0
        scams = self.db.query(func.count(DetectionHistory.id)).filter(DetectionHistory.is_scam.is_(True)).scalar() or 0
        categories = (
            self.db.query(DetectionHistory.category, func.count(DetectionHistory.id))
            .group_by(DetectionHistory.category)
            .all()
        )
        success = self.db.query(func.count(AnalyticsRecord.id)).filter(AnalyticsRecord.successful.is_(True)).scalar() or 0
        sessions = self.db.query(func.count(AnalyticsRecord.id)).scalar() or 0
        saved = self.db.query(func.sum(AnalyticsRecord.estimated_money_saved)).scalar() or 0.0
        trend_rows = (
            self.db.query(func.date(DetectionHistory.created_at), func.count(DetectionHistory.id))
            .group_by(func.date(DetectionHistory.created_at))
            .order_by(func.date(DetectionHistory.created_at))
            .all()
        )
        return {
            "total_scams_detected": scams,
            "total_messages_processed": total,
            "scam_categories": {k: v for k, v in categories},
            "detection_trend": [
                {"date": str(day), "count": count}
                for day, count in trend_rows
            ],
            "success_rate": round((success / sessions) * 100.0, 2) if sessions else 0.0,
            "estimated_money_saved": float(saved),
        }
