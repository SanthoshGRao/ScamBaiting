from __future__ import annotations

from sqlalchemy.orm import Session

from app.core.config import settings
from app.db.base import Base
from app.db.models import User
from app.db.session import engine
from app.security.auth import get_password_hash


def init_db() -> None:
    Base.metadata.create_all(bind=engine)


def ensure_default_user(db: Session) -> None:
    exists = db.query(User).filter(User.username == settings.default_admin_username).first()
    if exists:
        return
    user = User(
        username=settings.default_admin_username,
        hashed_password=get_password_hash(settings.default_admin_password),
        is_active=True,
    )
    db.add(user)
    db.commit()
