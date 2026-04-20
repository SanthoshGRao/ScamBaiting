import asyncio
from groq import AsyncGroq

async def get_models():
    import os
    client = AsyncGroq(api_key=os.getenv("GROQ_API_KEY", ""))
    try:
        models = await client.models.list()
        print("Available models:")
        for m in models.data:
            print("-", m.id)
    except Exception as e:
        print("ERROR:", str(e))

if __name__ == "__main__":
    asyncio.run(get_models())
