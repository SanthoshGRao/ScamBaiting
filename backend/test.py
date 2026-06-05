from app.db.session import SessionLocal
from app.api.detection_routes import build_detection_agent
from app.models.detection_models import DetectionRequest
import asyncio

async def main():
    db = SessionLocal()
    agent = build_detection_agent(db)
    request = DetectionRequest(text="Win $1000 now!", source="manual")
    res = await agent.detect_full(request)
    print(res.model_dump_json(indent=2))

if __name__ == "__main__":
    asyncio.run(main())
