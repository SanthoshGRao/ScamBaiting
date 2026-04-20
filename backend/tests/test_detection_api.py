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


def test_detection_endpoint_works() -> None:
    token = _token()
    response = client.post(
        "/api/v1/detect",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "text": "Urgent! verify your bank account at http://bit.ly/test",
            "source": "notification",
            "sender": "+911234567890",
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert "verdict" in data
    assert "confidence" in data["verdict"]
