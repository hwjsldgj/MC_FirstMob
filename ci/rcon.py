#!/usr/bin/env python3
"""最小的 RCON 客户端（标准库），供冒烟脚本复用。

服务端在生成区块时可能卡顿几秒，socket 超时给足；调用方自己决定重试。
"""
import socket
import struct

AUTH_TYPE = 3
COMMAND_TYPE = 2


class Rcon:
    def __init__(self, host: str, port: int, password: str, timeout: float = 30.0):
        self._socket = socket.create_connection((host, port), timeout=timeout)
        self._socket.settimeout(timeout)
        self._request_id = 1
        self._socket.sendall(self._packet(AUTH_TYPE, password))
        response = self._recv()
        (response_id,) = struct.unpack("<i", response[:4])
        if response_id == -1:
            self.close()
            raise OSError("rcon authentication failed")

    def close(self) -> None:
        try:
            self._socket.close()
        except OSError:
            pass

    def __enter__(self) -> "Rcon":
        return self

    def __exit__(self, *_exc) -> None:
        self.close()

    def command(self, command: str) -> str:
        """发一条命令，返回去掉包头的正文（可能为空串）。"""
        self._request_id += 1
        self._socket.sendall(self._packet(COMMAND_TYPE, command))
        response = self._recv()
        return response[8:-2].decode("utf-8", "replace")

    def _packet(self, packet_type: int, body: str) -> bytes:
        payload = struct.pack("<ii", self._request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
        return struct.pack("<i", len(payload)) + payload

    def _recv(self) -> bytes:
        (length,) = struct.unpack("<i", self._recv_exact(4))
        return self._recv_exact(length)

    def _recv_exact(self, size: int) -> bytes:
        data = b""
        while len(data) < size:
            chunk = self._socket.recv(size - len(data))
            if not chunk:
                raise OSError("connection closed")
            data += chunk
        return data
