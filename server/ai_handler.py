"""
AI provider abstraction layer.
Supports OpenAI, Ollama (local LLM), and a mock provider for testing.
"""

import asyncio
import logging
from abc import ABC, abstractmethod
from typing import AsyncGenerator

from config import AIConfig

logger = logging.getLogger(__name__)


class AIProvider(ABC):
    """Base class for AI providers."""

    @abstractmethod
    async def generate(self, prompt: str, history: list[dict] = None) -> str:
        """Generate a complete response."""
        pass

    @abstractmethod
    async def generate_stream(
        self, prompt: str, history: list[dict] = None
    ) -> AsyncGenerator[str, None]:
        """Generate a streaming response, yielding tokens."""
        pass


class MockAIProvider(AIProvider):
    """Mock AI for testing without an actual LLM."""

    async def generate(self, prompt: str, history: list[dict] = None) -> str:
        await asyncio.sleep(0.5)  # Simulate latency
        return f"I received your message: \"{prompt}\". This is a mock AI response. Configure a real AI provider (OpenAI or Ollama) in the .env file for actual AI responses."

    async def generate_stream(
        self, prompt: str, history: list[dict] = None
    ) -> AsyncGenerator[str, None]:
        response = f"I received your message: \"{prompt}\". This is a streaming mock response from LocalLink Pro server."
        words = response.split()
        for word in words:
            await asyncio.sleep(0.08)  # Simulate token generation
            yield word + " "


class OpenAIProvider(AIProvider):
    """OpenAI API provider (GPT-4, GPT-3.5, etc.)."""

    def __init__(self, config: AIConfig):
        try:
            from openai import AsyncOpenAI
            self.client = AsyncOpenAI(api_key=config.openai_api_key)
            self.model = config.openai_model
            self.system_prompt = config.system_prompt
        except ImportError:
            raise RuntimeError("openai package not installed. Run: pip install openai")

    def _build_messages(self, prompt: str, history: list[dict] = None) -> list[dict]:
        messages = [{"role": "system", "content": self.system_prompt}]
        if history:
            messages.extend(history)
        messages.append({"role": "user", "content": prompt})
        return messages

    async def generate(self, prompt: str, history: list[dict] = None) -> str:
        messages = self._build_messages(prompt, history)
        response = await self.client.chat.completions.create(
            model=self.model,
            messages=messages,
        )
        return response.choices[0].message.content

    async def generate_stream(
        self, prompt: str, history: list[dict] = None
    ) -> AsyncGenerator[str, None]:
        messages = self._build_messages(prompt, history)
        stream = await self.client.chat.completions.create(
            model=self.model,
            messages=messages,
            stream=True,
        )
        async for chunk in stream:
            delta = chunk.choices[0].delta
            if delta.content:
                yield delta.content


class OllamaProvider(AIProvider):
    """Ollama local LLM provider."""

    def __init__(self, config: AIConfig):
        self.host = config.ollama_host
        self.model = config.ollama_model
        self.system_prompt = config.system_prompt

    async def generate(self, prompt: str, history: list[dict] = None) -> str:
        import aiohttp

        payload = {
            "model": self.model,
            "prompt": prompt,
            "system": self.system_prompt,
            "stream": False,
        }
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"{self.host}/api/generate", json=payload
            ) as resp:
                data = await resp.json()
                return data.get("response", "")

    async def generate_stream(
        self, prompt: str, history: list[dict] = None
    ) -> AsyncGenerator[str, None]:
        import aiohttp

        payload = {
            "model": self.model,
            "prompt": prompt,
            "system": self.system_prompt,
            "stream": True,
        }
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"{self.host}/api/generate", json=payload
            ) as resp:
                async for line in resp.content:
                    if line:
                        import json
                        data = json.loads(line)
                        token = data.get("response", "")
                        if token:
                            yield token


def create_ai_provider(config: AIConfig) -> AIProvider:
    """Factory function to create the configured AI provider."""
    provider = config.provider.lower()
    if provider == "openai":
        logger.info(f"Using OpenAI provider with model: {config.openai_model}")
        return OpenAIProvider(config)
    elif provider == "ollama":
        logger.info(f"Using Ollama provider with model: {config.ollama_model}")
        return OllamaProvider(config)
    else:
        logger.info("Using Mock AI provider (set AI_PROVIDER in .env for real AI)")
        return MockAIProvider()
