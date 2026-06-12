import requests

print("Testing render backend health...")
try:
    res = requests.get("https://scambaiting.onrender.com/api/v1/detect/health", timeout=10)
    print("Health Status:", res.status_code, res.text)
except Exception as e:
    print("Health check failed:", e)

print("Testing render backend login...")
try:
    res = requests.post(
        "https://scambaiting.onrender.com/api/v1/auth/login",
        json={"username": "admin", "password": "admin123"},
        timeout=10
    )
    print("Login Status:", res.status_code, res.text)
    if res.status_code == 200:
        token = res.json().get("access_token")
        
        print("Testing render backend detection...")
        det_res = requests.post(
            "https://scambaiting.onrender.com/api/v1/detect/full",
            headers={"Authorization": f"Bearer {token}"},
            json={
                "text": "Your account has been suspended! Click here to login and restore: http://fake-login-bank.com",
                "source": "notification",
                "sender": "+1234567890"
            },
            timeout=20
        )
        print("Detection Status:", det_res.status_code, det_res.text)
except Exception as e:
    print("Error:", e)
