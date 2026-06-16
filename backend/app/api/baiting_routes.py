"""
Baiting Routes — API endpoints for Scam Baiting operations.
"""

from __future__ import annotations

import hashlib
import logging
import time
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from tenacity import retry, stop_after_attempt, wait_exponential

from app.db.models import BaitingSession, ScammerMessage, SenderProfile, TrackingLink, User
from app.db.session import get_db
from app.models.baiting_models import BaitingRequest, BaitingResponse
from app.agents.strategy.baiting_agent import BaitingAgent
from app.agents.strategy.strategy_agent import StrategyAgent
from app.api.tracking_routes import create_tracking_link, get_tracking_url
from app.deception.engine import DeceptionEngine, IMAGE_REQUEST_PATTERN, extract_payment_context
from app.providers.llm_classifier import create_llm_provider, LLMSettings
from app.security.auth import get_current_user
from app.security.rate_limit import rate_limiter
from app.core.config import settings

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/bait",
    tags=["baiting"],
)

_baiting_agent: BaitingAgent | None = None
# Heuristic-only selector keeps /bait/reply token usage low (no extra LLM call).
_strategy_selector = StrategyAgent(llm_provider=None)

_reply_cache: dict[str, dict] = {}
_CACHE_TTL = 300  # 5 minutes

def _get_idempotency_key(request: BaitingRequest) -> str:
    last_user_index = max((i for i, m in enumerate(request.history) if m.role == "user"), default=-1)
    last_user_msg = request.history[last_user_index].content if last_user_index >= 0 else ""
    key_str = f"{request.session_id}:{last_user_msg}:{last_user_index}"
    return hashlib.sha256(key_str.encode()).hexdigest()

def get_baiting_agent() -> BaitingAgent:
    """Get singleton BaitingAgent."""
    global _baiting_agent
    if _baiting_agent is None:
        try:
            llm_settings = LLMSettings()
            # Single provider handles risk-based model routing internally
            # (gpt-4.1 for high-risk, gpt-4.1-mini for medium/low)
            provider = create_llm_provider(provider="openai", settings=llm_settings)
            _baiting_agent = BaitingAgent(provider)
        except Exception as e:
            logger.error("Failed to init BaitingAgent: %s", e)
            raise HTTPException(status_code=500, detail="Baiting agent unavailable")
    return _baiting_agent

@router.post(
    "/reply",
    response_model=BaitingResponse,
    summary="Generate Persona Reply",
    description="Generates an AI reply to waste a scammer's time.",
)
async def generate_reply(
    request: BaitingRequest,
    db: Session = Depends(get_db),
    _: User = Depends(get_current_user),
) -> BaitingResponse:
    """Generate persona-based reply."""
    rate_limiter.check(f"user:{_.id}:bait")
    
    # --- Idempotency Check ---
    now = time.time()
    expired = [k for k, v in _reply_cache.items() if now - v['time'] > _CACHE_TTL]
    for k in expired:
        del _reply_cache[k]

    event_key = _get_idempotency_key(request)
    if event_key in _reply_cache:
        logger.info("Idempotency hit: Returning cached response for %s", event_key)
        return _reply_cache[event_key]['response']

    agent = get_baiting_agent()
    
    if request.offline_analytics:
        logger.info(
            "Received %d offline analytics events for session=%s",
            len(request.offline_analytics), request.session_id,
        )
    
    try:
        history_dicts = [{"role": m.role, "content": m.content} for m in request.history]
        selection = await _strategy_selector.select_strategy(
            session_id=request.session_id,
            history=history_dicts,
            scam_category=request.scam_category,
            current_strategy=request.current_strategy,
        )
        bait_request = request.model_copy(update={"current_strategy": selection.strategy})

        # --- Tracking Link Injection ---
        tracking_url = None
        n_user_msgs = sum(1 for m in request.history if m.role == "user")
        # Check if enough messages and no link sent yet for this session
        if n_user_msgs >= settings.tracking_link_threshold:
            existing_link = db.query(TrackingLink).filter(
                TrackingLink.session_id == request.session_id
            ).first()
            if not existing_link:
                # Choose context type based on scam category
                context_map = {
                    "phishing": "bank_verification",
                    "financial": "payment_receipt",
                    "delivery": "delivery_tracking",
                    "refund": "refund_form",
                }
                ctx = context_map.get(request.scam_category, "payment_receipt")
                link = create_tracking_link(
                    db=db,
                    session_id=request.session_id,
                    sender_id=request.sender_id,
                    context_type=ctx,
                )
                tracking_url = get_tracking_url(link.token)
                logger.info(
                    "Tracking link created for session=%s: %s (type=%s)",
                    request.session_id, tracking_url, ctx,
                )

        response = await _generate(agent, bait_request, tracking_url=tracking_url)

        # --- Image Generation: Check if scammer asked for a screenshot ---
        image_base64 = None
        image_context = None
        last_user_msg = next(
            (m.content for m in reversed(request.history) if m.role == "user"), ""
        )
        if IMAGE_REQUEST_PATTERN.search(last_user_msg):
            try:
                payment_ctx = extract_payment_context(request.history)
                engine = DeceptionEngine()
                image_base64 = engine.generate_contextual_screenshot(payment_ctx)
                image_context = payment_ctx.get("screenshot_type", "payment_proof")
                logger.info(
                    "Generated fake screenshot for session=%s: type=%s",
                    request.session_id, image_context,
                )
            except Exception as img_err:
                logger.warning("Image generation failed: %s", img_err)

        response = response.model_copy(update={
            "session_strategy": selection.strategy,
            "tracking_url": tracking_url,
            "image_base64": image_base64,
            "image_context": image_context,
        })
        strategy_for_session = selection.strategy

        logger.info(
            "Bait reply generated: session=%s, sender=%s, strategy=%s, reply_len=%d",
            request.session_id, request.sender_id, strategy_for_session,
            len(response.reply_text),
        )

        session = db.query(BaitingSession).filter(BaitingSession.session_id == request.session_id).first()
        if not session:
            session = BaitingSession(
                session_id=request.session_id,
                sender_id=request.sender_id,
                persona=request.persona,
                goal=request.goal,
                current_strategy=strategy_for_session,
                messages_count=len(request.history),
            )
            db.add(session)
        else:
            session.messages_count = len(request.history)
            session.current_strategy = strategy_for_session
            session.goal = request.goal
        db.commit()
        profile = db.query(SenderProfile).filter(SenderProfile.sender_id == request.sender_id).first()
        if profile:
            profile.last_message = request.history[-1].content if request.history else profile.last_message
            db.add(
                ScammerMessage(
                    sender_id=request.sender_id,
                    role="assistant",
                    content=response.reply_text,
                )
            )
            db.commit()
            
        _reply_cache[event_key] = {'response': response, 'time': time.time()}
        return response
    except Exception as e:
        logger.error(
            "Baiting reply failed: session=%s, sender=%s, history_len=%d, error=%s",
            request.session_id, request.sender_id, len(request.history), e,
        )
        raise HTTPException(status_code=500, detail="Reply generation failed")


@retry(wait=wait_exponential(multiplier=0.3, min=0.3, max=2), stop=stop_after_attempt(3), reraise=True)
async def _generate(
    agent: BaitingAgent, request: BaitingRequest, tracking_url: str | None = None,
) -> BaitingResponse:
    return await agent.generate_reply(request, tracking_url=tracking_url)
