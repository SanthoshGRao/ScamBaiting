import asyncio
import logging
from app.agents.strategy.baiting_agent import BaitingAgent
from app.providers.llm_classifier import build_llm_provider
from app.models.baiting_models import BaitingRequest, ChatMessage

logging.basicConfig(level=logging.INFO)

async def test_baiting():
    # Setup
    llm = build_llm_provider("openai")
    agent = BaitingAgent(llm)

    request = BaitingRequest(
        session_id="test_session",
        persona="half_understanding_user",
        goal="waste_time",
        history=[
            ChatMessage(role="user", content="Hello, your SBI Bank account has been suspended due to incomplete KYC. Please click here immediately to verify your PAN and avoid a penalty of Rs. 5000: http://sbi-kyc-verify.com")
        ],
        current_strategy="CONFUSION"
    )

    print("Sending request to LLM...")
    response = await agent.generate_reply(request)
    print("----- FINAL REPLY -----")
    print(response.reply_text)
    print("-----------------------")

if __name__ == "__main__":
    asyncio.run(test_baiting())
