# ScamShield Backend (Production Upgrade)

## Environment Setup

Create `backend/.env`:

```env
DATABASE_URL=sqlite:///./scamshield.db
# For PostgreSQL:
# DATABASE_URL=postgresql+psycopg2://user:password@localhost:5432/scamshield

JWT_SECRET_KEY=replace_with_a_long_random_secret
JWT_ALGORITHM=HS256
JWT_ACCESS_TOKEN_EXPIRE_MINUTES=60

DEFAULT_ADMIN_USERNAME=admin
DEFAULT_ADMIN_PASSWORD=admin123

CORS_ORIGINS=http://localhost:3000,http://10.0.2.2:8000

GROQ_API_KEY=your_groq_api_key
OPENAI_API_KEY=your_openai_api_key
LLM_MODEL=gpt-4.1-mini
LLM_TEMPERATURE=0.78
LLM_TOP_P=0.9
LLM_MAX_TOKENS=220
DETECTION_LLM_PROVIDER=openai
DETECTION_GROQ_MODEL=llama-3.1-8b-instant
DETECTION_OPENAI_FALLBACK_MODEL=gpt-4.1-mini
RESPONSE_OPENAI_HIGH_MODEL=gpt-4.1
RESPONSE_OPENAI_MEDIUM_MODEL=gpt-4.1-mini
```

## Install

```bash
cd backend
pip install -r requirements.txt
```

## Migrations

```bash
cd backend
alembic upgrade head
```

## Run Backend

```bash
cd backend
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

## Authentication Flow

1. Login:
```bash
curl -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```
2. Use returned bearer token in `Authorization` header for protected APIs.

## Health Checks

- `GET /`
- `GET /api/v1/detect/health`
- `GET /api/v2/deception/status`

## Tests

```bash
cd backend
pytest
```

## Android App Run Notes

- Android default base URL is `http://10.0.2.2:8000/` in `android/app/build.gradle`.
- Emulator reaches host backend via `10.0.2.2`.
