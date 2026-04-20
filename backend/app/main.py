"""
ScamShield API — FastAPI application entry point.

Main application with detection routes, CORS, and logging config.
"""

from __future__ import annotations

import logging
import sys

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware
from slowapi.util import get_remote_address

from app.api.auth_routes import router as auth_router
from app.api.detection_routes import router as detection_router
from app.api.baiting_routes import router as baiting_router
from app.api.strategy_routes import router as strategy_router
from app.api.analytics_routes import router as analytics_router
from app.api.deception_routes import router as deception_router
from app.api.scammer_routes import router as scammer_router, public_router as public_scammer_router
from app.api.tracking_routes import router as tracking_router, public_router as public_tracking_router
from app.core.config import settings
from app.db.init_db import ensure_default_user, init_db
from app.db.session import SessionLocal

# --- Logging Setup ---

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(name)s | %(levelname)s | %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger(__name__)

# --- App ---

app = FastAPI(
    title=settings.app_name,
    description=(
        "A Deception-Driven Multimodal AI System for Intelligent "
        "Scam Detection and Adaptive Response"
    ),
    version=settings.app_version,
    docs_url="/docs",
    redoc_url="/redoc",
)
limiter = Limiter(key_func=get_remote_address, default_limits=["120/minute"])
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.add_middleware(SlowAPIMiddleware)

# --- CORS ---

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- Routes ---

app.include_router(auth_router)
app.include_router(detection_router)
app.include_router(baiting_router)
app.include_router(strategy_router)
app.include_router(analytics_router)
app.include_router(deception_router)
app.include_router(scammer_router)
app.include_router(public_scammer_router)
app.include_router(tracking_router)
app.include_router(public_tracking_router)


@app.get("/", tags=["root"])
async def root() -> dict[str, str]:
    """API root — basic info."""
    return {
        "name": settings.app_name,
        "version": settings.app_version,
        "docs": "/docs",
    }


@app.on_event("startup")
async def startup() -> None:
    """Initialize services on startup."""
    init_db()
    db = SessionLocal()
    try:
        ensure_default_user(db)
    finally:
        db.close()
    logger.info("ScamShield API starting up...")


@app.on_event("shutdown")
async def shutdown() -> None:
    """Cleanup on shutdown."""
    logger.info("ScamShield API shutting down...")
