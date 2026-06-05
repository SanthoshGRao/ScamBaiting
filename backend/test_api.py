import requests
import json

def get_token():
    res = requests.post("http://127.0.0.1:8000/api/v1/auth/login", json={
        "username": "admin",
        "password": "admin123"
    })
    if res.status_code == 200:
        return res.json()["access_token"]
    print("Login failed", res.text)
    return None

def test_detect():
    token = get_token()
    if not token: return
    headers = {"Authorization": f"Bearer {token}"}
    req = {
        "text": "Win $1000 now! Click here: http://scam.me",
        "source": "manual"
    }
    res = requests.post("http://127.0.0.1:8000/api/v1/detect/full", json=req, headers=headers)
    print("Status:", res.status_code)
    print(json.dumps(res.json(), indent=2))

if __name__ == "__main__":
    test_detect()
