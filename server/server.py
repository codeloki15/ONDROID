"""
LocalLink Pro Server — Main entry point.

Runs a WebSocket server that receives messages from the Android app,
processes them through an AI provider, and streams responses back.

Optionally supports Bluetooth SPP on Linux.

Usage:
    python server.py
"""

import asyncio
import json
import logging
import signal
import sys
from datetime import datetime
from typing import Set

import websockets
from websockets.server import WebSocketServerProtocol

from ai_handler import AIProvider, create_ai_provider
from config import load_config, AIConfig, ServerConfig
from protocol import (
    MessageTypes,
    ProtocolMessage,
    make_ai_response,
    make_stream_start,
    make_stream_chunk,
    make_stream_end,
    make_error,
    make_pong,
)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("LocalLink")

# Connected clients
connected_clients: Set[WebSocketServerProtocol] = set()

# Conversation history per client (keyed by websocket id)
client_histories: dict[int, list[dict]] = {}


async def handle_ai_request(
    websocket: WebSocketServerProtocol,
    msg: ProtocolMessage,
    ai: AIProvider,
    use_streaming: bool = True,
):
    """Process an AI request and send the response (streaming or complete)."""
    prompt = msg.content.text
    if not prompt:
        await websocket.send(make_error("Empty message").to_json())
        return

    client_id = id(websocket)
    history = client_histories.get(client_id, [])

    logger.info(f"AI request from client {client_id}: {prompt[:80]}...")

    try:
        if use_streaming:
            # Send stream start
            command_id = msg.content.command_id
            await websocket.send(make_stream_start(command_id).to_json())

            full_response = ""
            async for token in ai.generate_stream(prompt, history):
                full_response += token
                await websocket.send(
                    make_stream_chunk(token, command_id).to_json()
                )

            # Send stream end
            await websocket.send(make_stream_end(command_id).to_json())
        else:
            response = await ai.generate(prompt, history)
            full_response = response
            await websocket.send(make_ai_response(response, msg.content.command_id).to_json())

        # Update conversation history
        history.append({"role": "user", "content": prompt})
        history.append({"role": "assistant", "content": full_response})
        # Keep last 20 exchanges
        if len(history) > 40:
            history = history[-40:]
        client_histories[client_id] = history

    except Exception as e:
        logger.error(f"AI processing error: {e}")
        await websocket.send(make_error(f"AI error: {str(e)}").to_json())


async def handle_message(
    websocket: WebSocketServerProtocol,
    raw_message: str,
    ai: AIProvider,
):
    """Route an incoming message to the appropriate handler."""
    try:
        msg = ProtocolMessage.from_json(raw_message)
    except (json.JSONDecodeError, Exception) as e:
        logger.error(f"Failed to parse message: {e}")
        await websocket.send(make_error(f"Invalid message format: {e}").to_json())
        return

    msg_type = msg.type
    logger.debug(f"Received message type: {msg_type}")

    if msg_type == MessageTypes.PING:
        await websocket.send(make_pong().to_json())

    elif msg_type in (MessageTypes.AI_REQUEST, MessageTypes.COMMAND):
        await handle_ai_request(websocket, msg, ai)

    elif msg_type == MessageTypes.NOTIFICATION:
        logger.info(f"Notification: {msg.content.text}")

    else:
        logger.warning(f"Unknown message type: {msg_type}")


async def client_handler(
    websocket: WebSocketServerProtocol,
    ai: AIProvider,
):
    """Handle a single client WebSocket connection."""
    client_id = id(websocket)
    remote = websocket.remote_address
    logger.info(f"Client connected: {remote} (id={client_id})")
    connected_clients.add(websocket)
    client_histories[client_id] = []

    try:
        async for raw_message in websocket:
            await handle_message(websocket, raw_message, ai)
    except websockets.exceptions.ConnectionClosed as e:
        logger.info(f"Client disconnected: {remote} ({e})")
    except Exception as e:
        logger.error(f"Client error: {remote} - {e}")
    finally:
        connected_clients.discard(websocket)
        client_histories.pop(client_id, None)
        logger.info(f"Client cleaned up: {remote}")


async def start_websocket_server(ai: AIProvider, config: ServerConfig):
    """Start the WebSocket server."""
    logger.info(f"Starting WebSocket server on ws://{config.ws_host}:{config.ws_port}")

    async with websockets.serve(
        lambda ws: client_handler(ws, ai),
        config.ws_host,
        config.ws_port,
        ping_interval=30,
        ping_timeout=10,
        max_size=2**20,  # 1MB max message size
    ):
        logger.info(
            f"Server running at ws://{config.ws_host}:{config.ws_port}"
        )
        logger.info("Waiting for Android client connections...")
        await asyncio.Future()  # Run forever


async def start_bluetooth_server(ai: AIProvider, config: ServerConfig):
    """Start Bluetooth SPP server (Linux only)."""
    try:
        import bluetooth
    except ImportError:
        logger.warning(
            "PyBluez not available — Bluetooth disabled. "
            "Install pybluez2 on Linux for Bluetooth support."
        )
        return

    logger.info(f"Starting Bluetooth SPP server on channel {config.bt_channel}")

    server_sock = bluetooth.BluetoothSocket(bluetooth.RFCOMM)
    server_sock.bind(("", config.bt_channel))
    server_sock.listen(1)

    uuid = "00001101-0000-1000-8000-00805F9B34FB"
    bluetooth.advertise_service(
        server_sock,
        "LocalLink Pro",
        service_id=uuid,
        service_classes=[uuid, bluetooth.SERIAL_PORT_CLASS],
        profiles=[bluetooth.SERIAL_PORT_PROFILE],
    )

    logger.info("Bluetooth SPP server advertised and listening")

    loop = asyncio.get_event_loop()

    while True:
        # Accept connection in executor (blocking call)
        client_sock, client_info = await loop.run_in_executor(
            None, server_sock.accept
        )
        logger.info(f"Bluetooth client connected: {client_info}")

        # Handle Bluetooth client in background task
        asyncio.create_task(
            handle_bluetooth_client(client_sock, client_info, ai)
        )


async def handle_bluetooth_client(client_sock, client_info, ai: AIProvider):
    """Handle a single Bluetooth SPP client."""
    loop = asyncio.get_event_loop()
    history = []
    buffer = ""

    try:
        while True:
            # Read data in executor (blocking)
            data = await loop.run_in_executor(None, client_sock.recv, 4096)
            if not data:
                break

            buffer += data.decode("utf-8")
            # Process complete messages (newline-delimited)
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                if not line.strip():
                    continue

                try:
                    msg = ProtocolMessage.from_json(line)
                except Exception as e:
                    logger.error(f"BT parse error: {e}")
                    error_resp = make_error(str(e)).to_json() + "\n"
                    await loop.run_in_executor(
                        None, client_sock.send, error_resp.encode()
                    )
                    continue

                if msg.type == MessageTypes.PING:
                    resp = make_pong().to_json() + "\n"
                    await loop.run_in_executor(
                        None, client_sock.send, resp.encode()
                    )
                elif msg.type in (MessageTypes.AI_REQUEST, MessageTypes.COMMAND):
                    prompt = msg.content.text or ""
                    if prompt:
                        try:
                            response = await ai.generate(prompt, history)
                            history.append({"role": "user", "content": prompt})
                            history.append(
                                {"role": "assistant", "content": response}
                            )
                            if len(history) > 40:
                                history = history[-40:]

                            resp = (
                                make_ai_response(
                                    response, msg.content.command_id
                                ).to_json()
                                + "\n"
                            )
                            await loop.run_in_executor(
                                None, client_sock.send, resp.encode()
                            )
                        except Exception as e:
                            error_resp = make_error(str(e)).to_json() + "\n"
                            await loop.run_in_executor(
                                None, client_sock.send, error_resp.encode()
                            )

    except Exception as e:
        logger.error(f"Bluetooth client error: {e}")
    finally:
        client_sock.close()
        logger.info(f"Bluetooth client disconnected: {client_info}")


async def main():
    """Main entry point."""
    ai_config, server_config = load_config()

    # Set log level
    logging.getLogger().setLevel(server_config.log_level)

    # Create AI provider
    ai = create_ai_provider(ai_config)

    print()
    print("=" * 56)
    print("   LocalLink Pro Server v1.0.0")
    print("=" * 56)
    print(f"   AI Provider:  {ai_config.provider}")
    print(f"   WebSocket:    ws://{server_config.ws_host}:{server_config.ws_port}")
    print(f"   Bluetooth:    {'Enabled' if server_config.bt_enabled else 'Disabled'}")
    print(f"   Started:      {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 56)
    print()

    tasks = [start_websocket_server(ai, server_config)]

    if server_config.bt_enabled:
        tasks.append(start_bluetooth_server(ai, server_config))

    await asyncio.gather(*tasks)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Server shutdown requested")
        print("\nServer stopped.")
