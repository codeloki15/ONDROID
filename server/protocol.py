"""
Protocol message definitions matching the Android client.
JSON-based wire protocol for LocalLink Pro.
"""

import json
import time
import uuid
from dataclasses import dataclass, field, asdict
from typing import Optional


# Message type constants (must match Android MessageTypes)
class MessageTypes:
    COMMAND = "command"
    RESPONSE_TEXT = "response_text"
    RESPONSE_STREAM_START = "response_stream_start"
    RESPONSE_STREAM_CHUNK = "response_stream_chunk"
    RESPONSE_STREAM_END = "response_stream_end"
    ERROR = "error"
    PING = "ping"
    PONG = "pong"
    NOTIFICATION = "notification"
    AI_REQUEST = "ai_request"
    AI_RESPONSE = "ai_response"
    AI_STREAM_START = "ai_stream_start"
    AI_STREAM_CHUNK = "ai_stream_chunk"
    AI_STREAM_END = "ai_stream_end"


@dataclass
class MessageContent:
    text: Optional[str] = None
    command_id: Optional[str] = None
    is_voice: bool = False
    language: str = "en"
    parameters: Optional[dict] = None
    tts_text: Optional[str] = None
    error: Optional[str] = None
    stream_token: Optional[str] = None


@dataclass
class ProtocolMessage:
    message_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    type: str = ""
    content: MessageContent = field(default_factory=MessageContent)
    transport: Optional[str] = None
    timestamp: int = field(default_factory=lambda: int(time.time() * 1000))

    def to_json(self) -> str:
        data = {
            "message_id": self.message_id,
            "type": self.type,
            "content": {
                k: v for k, v in asdict(self.content).items() if v is not None
            },
            "timestamp": self.timestamp,
        }
        if self.transport:
            data["transport"] = self.transport
        return json.dumps(data)

    @classmethod
    def from_json(cls, json_str: str) -> "ProtocolMessage":
        data = json.loads(json_str)
        content_data = data.get("content", {})
        content = MessageContent(
            text=content_data.get("text"),
            command_id=content_data.get("command_id"),
            is_voice=content_data.get("is_voice", False),
            language=content_data.get("language", "en"),
            parameters=content_data.get("parameters"),
            tts_text=content_data.get("tts_text"),
            error=content_data.get("error"),
            stream_token=content_data.get("stream_token"),
        )
        return cls(
            message_id=data.get("message_id", str(uuid.uuid4())),
            type=data.get("type", ""),
            content=content,
            transport=data.get("transport"),
            timestamp=data.get("timestamp", int(time.time() * 1000)),
        )


# Helper constructors
def make_ai_response(text: str, command_id: str = None) -> ProtocolMessage:
    return ProtocolMessage(
        type=MessageTypes.AI_RESPONSE,
        content=MessageContent(text=text, command_id=command_id, tts_text=text),
    )


def make_stream_start(command_id: str = None) -> ProtocolMessage:
    return ProtocolMessage(
        type=MessageTypes.AI_STREAM_START,
        content=MessageContent(command_id=command_id),
    )


def make_stream_chunk(token: str, command_id: str = None) -> ProtocolMessage:
    return ProtocolMessage(
        type=MessageTypes.AI_STREAM_CHUNK,
        content=MessageContent(stream_token=token, command_id=command_id),
    )


def make_stream_end(command_id: str = None) -> ProtocolMessage:
    return ProtocolMessage(
        type=MessageTypes.AI_STREAM_END,
        content=MessageContent(command_id=command_id),
    )


def make_error(error_text: str) -> ProtocolMessage:
    return ProtocolMessage(
        type=MessageTypes.ERROR,
        content=MessageContent(error=error_text),
    )


def make_pong() -> ProtocolMessage:
    return ProtocolMessage(
        type=MessageTypes.PONG,
        content=MessageContent(text="pong"),
    )
