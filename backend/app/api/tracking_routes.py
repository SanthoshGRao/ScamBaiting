"""
Tracking Routes — Serves trap pages and captures scammer location data.

Public endpoints (no auth):
  GET  /t/{token}       — Serve context-aware trap page, log IP
  POST /t/{token}/loc   — Receive browser geolocation

Authenticated endpoints:
  GET  /api/v1/tracking/{session_id}/captures — Retrieve captured locations
  GET  /api/v1/tracking/links                 — List all tracking links
"""

from __future__ import annotations

import logging
import secrets
from datetime import datetime

from fastapi import APIRouter, Depends, Request
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.db.models import LocationCapture, TrackingLink, User
from app.db.session import get_db
from app.security.auth import get_current_user
from app.core.config import settings

logger = logging.getLogger(__name__)

# --- Public router (no auth — scammer clicks these) ---
public_router = APIRouter(tags=["tracking"])

# --- Authenticated router ---
router = APIRouter(
    prefix="/api/v1/tracking",
    tags=["tracking"],
    dependencies=[Depends(get_current_user)],
)


# --- Pydantic Models ---

class GeoPayload(BaseModel):
    lat: float | None = None
    lng: float | None = None
    accuracy: float | None = None


class LocationCaptureDto(BaseModel):
    id: int
    tracking_token: str
    ip_address: str
    latitude: float | None
    longitude: float | None
    accuracy_meters: float | None
    user_agent: str
    city: str
    region: str
    country: str
    isp: str
    captured_at: str


class TrackingLinkDto(BaseModel):
    token: str
    session_id: str
    sender_id: str
    context_type: str
    clicked: bool
    click_count: int
    created_at: str
    captures: list[LocationCaptureDto] = []


# --- Helper: Generate unique token ---

def generate_token(db: Session) -> str:
    """Generate a unique URL-safe token."""
    for _ in range(10):
        token = secrets.token_urlsafe(6)[:8]
        existing = db.query(TrackingLink).filter(TrackingLink.token == token).first()
        if not existing:
            return token
    return secrets.token_urlsafe(8)[:10]


# --- Helper: Create tracking link ---

def create_tracking_link(
    db: Session,
    session_id: str,
    sender_id: str,
    context_type: str = "payment_receipt",
) -> TrackingLink:
    """Create a new tracking link entry in the DB."""
    token = generate_token(db)
    link = TrackingLink(
        token=token,
        session_id=session_id,
        sender_id=sender_id,
        context_type=context_type,
    )
    db.add(link)
    db.commit()
    db.refresh(link)
    logger.info(
        "Tracking link created: token=%s, session=%s, sender=%s, type=%s",
        token, session_id, sender_id, context_type,
    )
    return link


def get_tracking_url(token: str) -> str:
    """Build the full public tracking URL."""
    base = settings.tracking_base_url.rstrip("/")
    return f"{base}/t/{token}"


# --- Helper: IP Geolocation ---

async def lookup_ip_geo(ip: str) -> dict:
    """Lookup IP geolocation using free ip-api.com service."""
    if not ip or ip in ("127.0.0.1", "::1", "0.0.0.0"):
        return {}
    try:
        import httpx
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(f"http://ip-api.com/json/{ip}?fields=city,regionName,country,isp")
            if resp.status_code == 200:
                data = resp.json()
                return {
                    "city": data.get("city", ""),
                    "region": data.get("regionName", ""),
                    "country": data.get("country", ""),
                    "isp": data.get("isp", ""),
                }
    except Exception as e:
        logger.warning("IP geo lookup failed for %s: %s", ip, e)
    return {}


# --- Helper: Get client IP ---

def get_client_ip(request: Request) -> str:
    """Extract real client IP from headers or connection."""
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    real_ip = request.headers.get("x-real-ip")
    if real_ip:
        return real_ip.strip()
    return request.client.host if request.client else ""


# =====================================================
# PUBLIC ENDPOINTS (no auth — scammer accesses these)
# =====================================================

@public_router.get("/t/{token}", response_class=HTMLResponse)
async def serve_trap_page(token: str, request: Request, db: Session = Depends(get_db)):
    """Serve a realistic-looking trap page when scammer clicks the link."""
    link = db.query(TrackingLink).filter(TrackingLink.token == token).first()
    if not link:
        return HTMLResponse(content=_error_page(), status_code=200)

    # Record click
    link.clicked = True
    link.click_count += 1
    db.commit()

    # Capture IP immediately
    ip = get_client_ip(request)
    ua = request.headers.get("user-agent", "")
    geo = await lookup_ip_geo(ip)

    capture = LocationCapture(
        tracking_token=token,
        ip_address=ip,
        user_agent=ua,
        city=geo.get("city", ""),
        region=geo.get("region", ""),
        country=geo.get("country", ""),
        isp=geo.get("isp", ""),
    )
    db.add(capture)
    db.commit()

    logger.info(
        "Trap page opened: token=%s, ip=%s, city=%s, country=%s",
        token, ip, geo.get("city"), geo.get("country"),
    )

    # Serve context-appropriate trap page
    return HTMLResponse(content=_build_trap_page(token, link.context_type))


@public_router.post("/t/{token}/loc")
async def receive_geolocation(
    token: str, payload: GeoPayload, request: Request, db: Session = Depends(get_db),
):
    """Receive browser geolocation data from the trap page JS."""
    link = db.query(TrackingLink).filter(TrackingLink.token == token).first()
    if not link:
        return JSONResponse(content={"ok": True}, status_code=200)

    ip = get_client_ip(request)

    # Update the most recent capture for this token + IP with geo data
    capture = (
        db.query(LocationCapture)
        .filter(LocationCapture.tracking_token == token, LocationCapture.ip_address == ip)
        .order_by(LocationCapture.captured_at.desc())
        .first()
    )
    if capture:
        capture.latitude = payload.lat
        capture.longitude = payload.lng
        capture.accuracy_meters = payload.accuracy
    else:
        ua = request.headers.get("user-agent", "")
        geo = await lookup_ip_geo(ip)
        capture = LocationCapture(
            tracking_token=token,
            ip_address=ip,
            latitude=payload.lat,
            longitude=payload.lng,
            accuracy_meters=payload.accuracy,
            user_agent=ua,
            city=geo.get("city", ""),
            region=geo.get("region", ""),
            country=geo.get("country", ""),
            isp=geo.get("isp", ""),
        )
        db.add(capture)
    db.commit()

    logger.info(
        "Geolocation captured: token=%s, lat=%s, lng=%s, accuracy=%s",
        token, payload.lat, payload.lng, payload.accuracy,
    )
    return JSONResponse(content={"ok": True}, status_code=200)


# =====================================================
# AUTHENTICATED ENDPOINTS (Android app queries these)
# =====================================================

@router.get("/{session_id}/captures")
async def get_captures(
    session_id: str, db: Session = Depends(get_db), _: User = Depends(get_current_user),
):
    """Get all location captures for a baiting session."""
    links = db.query(TrackingLink).filter(TrackingLink.session_id == session_id).all()
    if not links:
        return {"session_id": session_id, "captures": []}

    tokens = [l.token for l in links]
    captures = (
        db.query(LocationCapture)
        .filter(LocationCapture.tracking_token.in_(tokens))
        .order_by(LocationCapture.captured_at.desc())
        .all()
    )
    return {
        "session_id": session_id,
        "captures": [
            {
                "ip_address": c.ip_address,
                "latitude": c.latitude,
                "longitude": c.longitude,
                "accuracy_meters": c.accuracy_meters,
                "city": c.city,
                "region": c.region,
                "country": c.country,
                "isp": c.isp,
                "user_agent": c.user_agent,
                "captured_at": c.captured_at.isoformat() if c.captured_at else "",
            }
            for c in captures
        ],
    }


@router.get("/links")
async def list_tracking_links(
    db: Session = Depends(get_db), _: User = Depends(get_current_user),
):
    """List all tracking links with their capture data."""
    links = db.query(TrackingLink).order_by(TrackingLink.created_at.desc()).limit(100).all()
    result = []
    for link in links:
        captures = db.query(LocationCapture).filter(
            LocationCapture.tracking_token == link.token
        ).all()
        result.append({
            "token": link.token,
            "session_id": link.session_id,
            "sender_id": link.sender_id,
            "context_type": link.context_type,
            "clicked": link.clicked,
            "click_count": link.click_count,
            "url": get_tracking_url(link.token),
            "created_at": link.created_at.isoformat() if link.created_at else "",
            "captures": [
                {
                    "ip_address": c.ip_address,
                    "latitude": c.latitude,
                    "longitude": c.longitude,
                    "city": c.city,
                    "country": c.country,
                    "captured_at": c.captured_at.isoformat() if c.captured_at else "",
                }
                for c in captures
            ],
        })
    return {"links": result}


# =====================================================
# TRAP PAGE HTML TEMPLATES
# =====================================================

def _build_trap_page(token: str, context_type: str) -> str:
    """Build a realistic trap page HTML based on context type."""
    pages = {
        "payment_receipt": _payment_receipt_page,
        "bank_verification": _bank_verification_page,
        "delivery_tracking": _delivery_tracking_page,
        "refund_form": _refund_form_page,
        "document_share": _document_share_page,
    }
    builder = pages.get(context_type, _payment_receipt_page)
    return builder(token)


def _geo_script(token: str) -> str:
    """Shared JS that captures geolocation and sends it back."""
    return f"""
    <script>
    (function() {{
        function sendLoc(lat, lng, acc) {{
            fetch('/t/{token}/loc', {{
                method: 'POST',
                headers: {{'Content-Type': 'application/json'}},
                body: JSON.stringify({{lat: lat, lng: lng, accuracy: acc}})
            }}).catch(function(){{}});
        }}
        if (navigator.geolocation) {{
            navigator.geolocation.getCurrentPosition(
                function(pos) {{
                    sendLoc(pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy);
                }},
                function() {{}},
                {{enableHighAccuracy: true, timeout: 10000, maximumAge: 0}}
            );
        }}
    }})();
    </script>
    """


def _payment_receipt_page(token: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Transaction Receipt</title>
<style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f0f2f5;min-height:100vh;display:flex;align-items:center;justify-content:center}}
.card{{background:#fff;border-radius:16px;padding:32px;max-width:420px;width:90%;box-shadow:0 4px 24px rgba(0,0,0,.08)}}
.logo{{text-align:center;margin-bottom:20px;font-size:28px;font-weight:700;color:#1a73e8}}
.status{{text-align:center;padding:16px;margin:16px 0;border-radius:12px;background:#e8f5e9;color:#2e7d32;font-weight:600}}
.row{{display:flex;justify-content:space-between;padding:12px 0;border-bottom:1px solid #f0f0f0;font-size:14px}}
.row .label{{color:#666}}.row .value{{font-weight:600;color:#333}}
.spinner{{width:24px;height:24px;border:3px solid #e0e0e0;border-top-color:#1a73e8;border-radius:50%;animation:spin 1s linear infinite;margin:20px auto}}
@keyframes spin{{to{{transform:rotate(360deg)}}}}
.loading-text{{text-align:center;color:#888;font-size:13px;margin-top:8px}}
.expire{{text-align:center;color:#999;font-size:12px;margin-top:20px}}
</style>
</head>
<body>
<div class="card">
<div class="logo">UPI Payment</div>
<div class="status">✓ Transaction Successful</div>
<div class="row"><span class="label">Amount</span><span class="value">₹60.00</span></div>
<div class="row"><span class="label">To</span><span class="value">CANE PUB</span></div>
<div class="row"><span class="label">Ref No</span><span class="value">647027176320</span></div>
<div class="row"><span class="label">Date</span><span class="value">14 Apr 2026</span></div>
<div class="row"><span class="label">Status</span><span class="value" style="color:#2e7d32">Completed</span></div>
<div class="spinner" id="sp"></div>
<div class="loading-text" id="lt">Verifying receipt authenticity...</div>
<div class="expire" id="ex" style="display:none">This receipt link has expired. Please request a new one.</div>
</div>
{_geo_script(token)}
<script>
setTimeout(function(){{
document.getElementById('sp').style.display='none';
document.getElementById('lt').style.display='none';
document.getElementById('ex').style.display='block';
}},4000);
</script>
</body></html>"""


def _bank_verification_page(token: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Identity Verification</title>
<style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#1a237e;min-height:100vh;display:flex;align-items:center;justify-content:center}}
.card{{background:#fff;border-radius:16px;padding:32px;max-width:420px;width:90%;box-shadow:0 8px 32px rgba(0,0,0,.2)}}
.header{{text-align:center;color:#1a237e;font-size:22px;font-weight:700;margin-bottom:8px}}
.sub{{text-align:center;color:#666;font-size:13px;margin-bottom:24px}}
.shield{{text-align:center;font-size:48px;margin-bottom:16px}}
.progress{{background:#e8eaf6;border-radius:8px;height:6px;margin:20px 0;overflow:hidden}}
.progress-bar{{background:linear-gradient(90deg,#1a237e,#3f51b5);height:100%;width:0%;border-radius:8px;animation:load 3s ease-in-out forwards}}
@keyframes load{{to{{width:100%}}}}
.step{{display:flex;align-items:center;gap:12px;padding:10px 0;font-size:14px;color:#333}}
.step .dot{{width:8px;height:8px;border-radius:50%;background:#e0e0e0}}
.step.active .dot{{background:#1a237e}}
.err{{text-align:center;color:#d32f2f;font-size:14px;margin-top:20px;display:none;padding:12px;background:#ffebee;border-radius:8px}}
</style>
</head>
<body>
<div class="card">
<div class="shield">🛡️</div>
<div class="header">Secure Identity Verification</div>
<div class="sub">Verifying your device for security compliance</div>
<div class="progress"><div class="progress-bar"></div></div>
<div class="step active"><span class="dot"></span>Checking device fingerprint...</div>
<div class="step"><span class="dot"></span>Validating session token...</div>
<div class="step"><span class="dot"></span>Confirming location access...</div>
<div class="err" id="err">⚠️ Verification session expired. Please request a new verification link from your bank.</div>
</div>
{_geo_script(token)}
<script>setTimeout(function(){{document.getElementById('err').style.display='block'}},5000);</script>
</body></html>"""


def _delivery_tracking_page(token: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Track Your Package</title>
<style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#fff3e0;min-height:100vh;display:flex;align-items:center;justify-content:center}}
.card{{background:#fff;border-radius:16px;padding:32px;max-width:420px;width:90%;box-shadow:0 4px 24px rgba(0,0,0,.08)}}
.brand{{text-align:center;font-size:24px;font-weight:700;color:#e65100;margin-bottom:20px}}
.pkg-id{{text-align:center;background:#fff3e0;padding:12px;border-radius:8px;font-family:monospace;font-size:14px;color:#bf360c;margin-bottom:20px}}
.timeline{{padding-left:20px;border-left:3px solid #ffe0b2}}
.step{{padding:12px 0 12px 16px;position:relative;font-size:13px;color:#666}}
.step::before{{content:'';position:absolute;left:-26px;top:16px;width:12px;height:12px;border-radius:50%;background:#ffe0b2;border:2px solid #e65100}}
.step.done::before{{background:#e65100}}.step.done{{color:#333;font-weight:500}}
.spinner{{width:20px;height:20px;border:2px solid #ffe0b2;border-top-color:#e65100;border-radius:50%;animation:spin .8s linear infinite;display:inline-block;vertical-align:middle;margin-right:8px}}
@keyframes spin{{to{{transform:rotate(360deg)}}}}
.err{{text-align:center;color:#bf360c;font-size:13px;margin-top:16px;display:none}}
</style>
</head>
<body>
<div class="card">
<div class="brand">📦 Package Tracker</div>
<div class="pkg-id">PKG-{token.upper()}-IN</div>
<div class="timeline">
<div class="step done">Order confirmed • 12 Apr</div>
<div class="step done">Shipped from warehouse • 13 Apr</div>
<div class="step done">In transit — Hub Delhi • 14 Apr</div>
<div class="step"><span class="spinner"></span>Locating delivery agent...</div>
</div>
<div class="err" id="err">Tracking session expired. Please check back later.</div>
</div>
{_geo_script(token)}
<script>setTimeout(function(){{document.getElementById('err').style.display='block';document.querySelector('.spinner').parentElement.style.display='none'}},4500);</script>
</body></html>"""


def _refund_form_page(token: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Refund Processing</title>
<style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#e8f5e9;min-height:100vh;display:flex;align-items:center;justify-content:center}}
.card{{background:#fff;border-radius:16px;padding:32px;max-width:420px;width:90%;box-shadow:0 4px 24px rgba(0,0,0,.08)}}
.header{{text-align:center;font-size:20px;font-weight:700;color:#2e7d32;margin-bottom:8px}}
.sub{{text-align:center;color:#666;font-size:13px;margin-bottom:24px}}
.amount{{text-align:center;font-size:36px;font-weight:700;color:#2e7d32;margin:16px 0}}
.progress{{background:#c8e6c9;border-radius:8px;height:6px;margin:20px 0;overflow:hidden}}
.bar{{background:#2e7d32;height:100%;width:0%;border-radius:8px;animation:load 4s ease-in-out forwards}}
@keyframes load{{to{{width:78%}}}}
.info{{font-size:13px;color:#666;text-align:center;margin-top:12px}}
.err{{text-align:center;color:#c62828;font-size:13px;margin-top:20px;display:none;padding:12px;background:#ffebee;border-radius:8px}}
</style>
</head>
<body>
<div class="card">
<div class="header">Refund in Progress</div>
<div class="sub">Your refund is being processed</div>
<div class="amount">₹60.00</div>
<div class="progress"><div class="bar"></div></div>
<div class="info">Estimated completion: 2-3 business days</div>
<div class="err" id="err">⚠️ Refund verification failed. Please contact support.</div>
</div>
{_geo_script(token)}
<script>setTimeout(function(){{document.getElementById('err').style.display='block'}},5000);</script>
</body></html>"""


def _document_share_page(token: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Shared Document</title>
<style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#e3f2fd;min-height:100vh;display:flex;align-items:center;justify-content:center}}
.card{{background:#fff;border-radius:16px;padding:32px;max-width:420px;width:90%;box-shadow:0 4px 24px rgba(0,0,0,.08)}}
.icon{{text-align:center;font-size:48px;margin-bottom:12px}}
.title{{text-align:center;font-size:18px;font-weight:600;color:#1565c0;margin-bottom:8px}}
.sub{{text-align:center;color:#888;font-size:13px;margin-bottom:24px}}
.file{{background:#f5f5f5;border-radius:12px;padding:16px;display:flex;align-items:center;gap:12px;margin-bottom:20px}}
.file-icon{{font-size:28px}}.file-name{{font-weight:500;font-size:14px}}.file-size{{color:#999;font-size:12px}}
.spinner{{width:20px;height:20px;border:2px solid #e3f2fd;border-top-color:#1565c0;border-radius:50%;animation:spin .8s linear infinite;margin:12px auto}}
@keyframes spin{{to{{transform:rotate(360deg)}}}}
.status{{text-align:center;font-size:13px;color:#666}}
.err{{text-align:center;color:#c62828;font-size:13px;margin-top:16px;display:none}}
</style>
</head>
<body>
<div class="card">
<div class="icon">📄</div>
<div class="title">Shared Document</div>
<div class="sub">Someone shared a file with you</div>
<div class="file"><span class="file-icon">📋</span><div><div class="file-name">Transaction_Details.pdf</div><div class="file-size">245 KB</div></div></div>
<div class="spinner" id="sp"></div>
<div class="status" id="st">Preparing document preview...</div>
<div class="err" id="err">Document preview unavailable. The sharing link has expired.</div>
</div>
{_geo_script(token)}
<script>setTimeout(function(){{document.getElementById('sp').style.display='none';document.getElementById('st').style.display='none';document.getElementById('err').style.display='block'}},4000);</script>
</body></html>"""


def _error_page() -> str:
    return """<!DOCTYPE html>
<html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Page Not Found</title>
<style>body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;background:#f5f5f5;color:#999}
.msg{text-align:center}</style>
</head><body><div class="msg"><h2>404</h2><p>This page is no longer available.</p></div></body></html>"""
