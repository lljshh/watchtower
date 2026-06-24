import asyncio
import json
import logging
import os
import uuid
from dataclasses import dataclass, field
from typing import Optional

import websockets
from websockets.asyncio.server import ServerConnection

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("watchtower")

DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8765


@dataclass
class ServerConfig:
    password: str
    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT

    @classmethod
    def from_json(cls, path: str) -> "ServerConfig":
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return cls(
            password=data.get("password", "123"),
            host=data.get("address") or DEFAULT_HOST,
            port=data.get("port") or DEFAULT_PORT,
        )

    @classmethod
    def from_env(cls) -> "ServerConfig":
        return cls(
            password=os.environ.get("WATCHTOWER_PASSWORD", "123"),
            host=os.environ.get("WATCHTOWER_HOST", DEFAULT_HOST),
            port=int(os.environ.get("WATCHTOWER_PORT", str(DEFAULT_PORT))),
        )


@dataclass
class ClientState:
    websocket: ServerConnection
    token: str
    remote: str
    latest_status: Optional[dict] = None
    player_list: list[str] = field(default_factory=list)
    pending: dict[str, asyncio.Future] = field(default_factory=dict)


class WatchtowerServer:
    def __init__(self, config: ServerConfig):
        self.config = config
        self._clients: dict[str, ClientState] = {}
        self._lock = asyncio.Lock()

    async def handle(self, websocket: ServerConnection) -> None:
        remote = f"{websocket.remote_address[0]}:{websocket.remote_address[1]}"
        logger.info("Client connected: %s", remote)
        state: Optional[ClientState] = None

        try:
            async for raw in websocket:
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    await websocket.send(json.dumps({"type": "error", "message": "invalid json"}))
                    continue

                msg_type = msg.get("type", "")

                if msg_type == "auth":
                    state = await self._handle_auth(websocket, msg, remote)
                elif msg.get("reply") is not None:
                    if state is not None:
                        self._handle_reply(state, msg)
                elif state is None:
                    await websocket.send(json.dumps({"type": "error", "message": "auth required first"}))
                elif msg_type == "status":
                    await self._handle_status(state, msg)
                else:
                    logger.warning("[%s] Unknown message type: %s", remote, msg_type)
                    await websocket.send(json.dumps({
                        "type": "error",
                    "id": "-1",
                        "token": state.token,
                        "message": f"unknown type: {msg_type}",
                    }))

        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            if state is not None:
                async with self._lock:
                    self._clients.pop(state.token, None)
                for fut in state.pending.values():
                    if not fut.done():
                        fut.set_exception(ConnectionError("client disconnected"))
            logger.info("Client disconnected: %s", remote)

    async def _handle_auth(
        self, websocket: ServerConnection, msg: dict, remote: str
    ) -> Optional[ClientState]:
        password = msg.get("password", "")
        if password != self.config.password:
            logger.warning("[%s] Authentication failed: wrong password", remote)
            await websocket.send(json.dumps({
                "type": "auth_fail",
                "id": "-1",
                "reason": "invalid password",
            }))
            return None

        token = uuid.uuid4().hex
        state = ClientState(websocket=websocket, token=token, remote=remote)
        async with self._lock:
            self._clients[token] = state

        logger.info("[%s] Authenticated, token=%s", remote, token)
        await websocket.send(json.dumps({
            "type": "auth_success",
                "id": "-1",
            "auth_token": token,
        }))
        return state

    async def _handle_status(self, state: ClientState, msg: dict) -> None:
        if msg.get("token") != state.token:
            await state.websocket.send(json.dumps({
                "type": "error",
                "id": "-1",
                "token": state.token,
                "message": "invalid token",
            }))
            return

        data = msg.get("data")
        if isinstance(data, dict):
            state.latest_status = data
            logger.info(
                "[%s] Status: online=%s/%s tps=%s mspt=%s cpu=%.1f%% mem=%.1f%% heap=%s/%s",
                state.remote,
                data.get("online", "?"),
                data.get("max", "?"),
                data.get("tps", "?"),
                data.get("mspt", "?"),
                (data.get("cpuLoad", 0) or 0) * 100,
                (data.get("hostMemoryUsage", 0) or 0) * 100,
                _format_bytes(data.get("heapUsed", 0) or 0),
                _format_bytes(data.get("heapMax", 0) or 0),
            )

    def _handle_reply(self, state: ClientState, msg: dict) -> None:
        reply_id = msg.get("reply")
        if reply_id is not None:
            fut = state.pending.pop(str(reply_id), None)
            if fut is not None and not fut.done():
                fut.set_result(msg)

    def _find_client(self, token: str) -> Optional[ClientState]:
        if token in self._clients:
            return self._clients[token]
        for k, v in self._clients.items():
            if k.startswith(token):
                return v
        return None

    async def send_command(self, token: str, cmd_type: str) -> Optional[dict]:
        async with self._lock:
            state = self._find_client(token)
        if state is None:
            return None

        cmd_id = uuid.uuid4().hex
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        state.pending[cmd_id] = fut

        payload = {"type": cmd_type, "id": cmd_id}
        try:
            await state.websocket.send(json.dumps(payload))
        except websockets.exceptions.ConnectionClosed:
            state.pending.pop(cmd_id, None)
            return None

        try:
            return await asyncio.wait_for(fut, timeout=10)
        except asyncio.TimeoutError:
            state.pending.pop(cmd_id, None)
            logger.warning("[%s] Command %s timed out", state.remote, cmd_type)
            return None

    def list_clients(self) -> list[dict]:
        return [
            {
                "token": s.token,
                "remote": s.remote,
                "has_status": s.latest_status is not None,
            }
            for s in self._clients.values()
        ]


def _format_bytes(n: int) -> str:
    if n >= 1 << 30:
        return f"{n / (1 << 30):.1f}G"
    if n >= 1 << 20:
        return f"{n / (1 << 20):.1f}M"
    if n >= 1 << 10:
        return f"{n / (1 << 10):.1f}K"
    return str(n)


def _find_config() -> str:
    candidates = [
        "run/config/lemonfate.json",
        "lemonfate.json",
    ]
    for p in candidates:
        if os.path.isfile(p):
            return p
    return ""


async def cli(server: WatchtowerServer) -> None:
    loop = asyncio.get_running_loop()
    print("Watchtower CLI ready. Commands: list, status <token>, players <token>, help, quit")
    while True:
        line = await loop.run_in_executor(None, input, "> ")
        line = line.strip()
        if not line:
            continue

        parts = line.split(maxsplit=1)
        cmd = parts[0].lower()

        if cmd in ("quit", "exit"):
            logger.info("Shutting down...")
            return
        elif cmd == "help":
            print("Commands:")
            print("  list              - list connected Minecraft servers")
            print("  status <token>    - query latest status from a server")
            print("  players <token>   - query player list from a server")
            print("  quit              - stop the server")
        elif cmd == "list":
            clients = server.list_clients()
            if not clients:
                print("  No clients connected.")
            else:
                for c in clients:
                    flag = "[S]" if c["has_status"] else "[ ]"
                    print(f"  {flag} {c['token'][:8]}... @ {c['remote']}")
        elif cmd == "status" and len(parts) > 1:
            token = parts[1]
            resp = await server.send_command(token, "get_status")
            if resp is None:
                print("  No response (client may have disconnected)")
            else:
                print(json.dumps(resp, indent=2, ensure_ascii=False))
        elif cmd == "players" and len(parts) > 1:
            token = parts[1]
            resp = await server.send_command(token, "get_players")
            if resp is None:
                print("  No response (client may have disconnected)")
            else:
                print(json.dumps(resp, indent=2, ensure_ascii=False))
        else:
            print(f"  Unknown command: {cmd}")


async def main() -> None:
    config_path = _find_config()
    if config_path:
        config = ServerConfig.from_json(config_path)
        logger.info("Loaded config from %s", config_path)
    else:
        config = ServerConfig.from_env()
        logger.info("No config file found, using defaults/env")

    server = WatchtowerServer(config)

    async with websockets.serve(server.handle, config.host, config.port):
        logger.info("Watchtower WebSocket server listening on ws://%s:%s", config.host, config.port)
        await cli(server)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Server stopped")
