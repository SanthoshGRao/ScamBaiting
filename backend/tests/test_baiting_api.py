from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def _token() -> str:
    response = client.post(
        "/api/v1/auth/login",
        json={"username": "admin", "password": "admin123"},
    )
    assert response.status_code == 200
    return response.json()["access_token"]


def test_baiting_reply_endpoint_works() -> None:
    token = _token()
    response = client.post(
        "/api/v1/bait/reply",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "sender_id": "scammer-1",
            "session_id": "s1",
            "persona": "curious_user",
            "current_strategy": "CONFUSION",
            "scam_category": "financial_fraud",
            "goal": "waste_time",
            "history": [{"role": "user", "content": "share your OTP now"}],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert "reply_text" in body
    assert body.get("session_strategy") in (
        "CONFUSION",
        "DELAY",
        "FAKE_COMPLIANCE",
        "AGGRESSION",
        "DERAILMENT",
        "ESCALATION",
    )
    if body.get("part_delay_seconds") is not None:
        assert isinstance(body["part_delay_seconds"], list)
        assert len(body["part_delay_seconds"]) == len(body.get("reply_parts") or [])
