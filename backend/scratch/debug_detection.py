
import asyncio
import logging
import sys
from app.agents.detection.detection_agent import DetectionAgent
from app.models.detection_models import DetectionRequest, RuleVerdict
from app.providers.llm_classifier import create_llm_provider, LLMSettings
from app.config.detection_settings import DetectionSettings

# Setup logging to see what's happening
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(name)s | %(levelname)s | %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)

async def test_detection():
    print("Testing Detection Agent...")
    
    settings = DetectionSettings()
    llm_settings = LLMSettings()
    llm_provider = create_llm_provider(provider="openai", settings=llm_settings)
    
    agent = DetectionAgent(
        settings=settings,
        llm_classifier=llm_provider
    )
    
    # Example scam message
    scam_text = "Congratulations! You have won $1,000,000. Send $500 to claim your prize now at http://scam-link.com"
    
    request = DetectionRequest(
        text=scam_text,
        sender="Unknown",
        source="sms",
        rule_verdict=RuleVerdict(is_suspicious=True)
    )
    
    print(f"\nScanning text: {scam_text}")
    response = await agent.detect_full(request)
    
    print("\n--- RESULTS ---")
    print(f"Is Scam: {response.verdict.is_scam}")
    print(f"Confidence: {response.verdict.confidence}")
    print(f"Category: {response.verdict.category}")
    print(f"Risk Level: {response.verdict.risk_level}")
    print(f"Reasoning: {response.verdict.reasoning}")
    print(f"Red Flags: {response.verdict.red_flags}")
    print(f"Processing Time: {response.processing_time_ms}ms")

if __name__ == "__main__":
    asyncio.run(test_detection())
