from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "ScamShield Detection API"
    app_version: str = "2.0.0"
    environment: str = "development"

    database_url: str = "sqlite:///./scamshield.db"
    jwt_secret_key: str = "change-me-in-env"
    jwt_algorithm: str = "HS256"
    jwt_access_token_expire_minutes: int = 60
    jwt_refresh_token_expire_minutes: int = 10080

    cors_origins: str = "http://localhost:3000,http://10.0.2.2:8000"

    default_admin_username: str = "admin"
    default_admin_password: str = "admin123"

    groq_api_key: str = ""
    openai_api_key: str = ""
    llm_model: str = "gpt-4.1-mini"
    llm_temperature: float = 0.78
    llm_top_p: float = 0.9
    llm_max_tokens: int = 220

    # Tracking links
    tracking_base_url: str = "https://shanel-unretributory-knuckly.ngrok-free.dev"
    tracking_link_threshold: int = 6  # Min user messages before sending a tracking link

    model_config = {
        "env_file": ".env",
        "extra": "ignore",
    }

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


settings = Settings()
