"""
Configuration loader for LocalLink Pro server.
Reads from .env file and environment variables.
"""

import os
from dataclasses import dataclass, field
from dotenv import load_dotenv

load_dotenv()


@dataclass
class AIConfig:
    provider: str = "mock"  # "openai", "ollama", "mock"
    openai_api_key: str = ""
    openai_model: str = "gpt-4"
    ollama_host: str = "http://localhost:11434"
    ollama_model: str = "llama3"
    system_prompt: str = (
        "You are LocalLink AI, a helpful assistant running on the user's local computer. "
        "You can help with questions, tasks, and commands. Keep responses concise and helpful."
    )


@dataclass
class ServerConfig:
    ws_host: str = "0.0.0.0"
    ws_port: int = 8765
    bt_enabled: bool = False
    bt_channel: int = 1
    log_level: str = "INFO"


def load_config() -> tuple[AIConfig, ServerConfig]:
    ai = AIConfig(
        provider=os.getenv("AI_PROVIDER", "mock"),
        openai_api_key=os.getenv("OPENAI_API_KEY", ""),
        openai_model=os.getenv("OPENAI_MODEL", "gpt-4"),
        ollama_host=os.getenv("OLLAMA_HOST", "http://localhost:11434"),
        ollama_model=os.getenv("OLLAMA_MODEL", "llama3"),
    )

    server = ServerConfig(
        ws_host=os.getenv("WS_HOST", "0.0.0.0"),
        ws_port=int(os.getenv("WS_PORT", "8765")),
        bt_enabled=os.getenv("BT_ENABLED", "false").lower() == "true",
        bt_channel=int(os.getenv("BT_CHANNEL", "1")),
        log_level=os.getenv("LOG_LEVEL", "INFO"),
    )

    return ai, server
