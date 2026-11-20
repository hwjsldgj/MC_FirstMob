#!/usr/bin/env python3
"""向无头服务端发一条 RCON 命令，用于冒烟测试结束后优雅停服。

用法：rcon_stop.py <host> <port> <password> <command>
只用标准库（socket/struct），CI 的 ubuntu-latest 自带 python3。
"""
import socket
import struct
import sys
import time

AUTH_TYPE = 3
COMMAND_TYPE = 2
REQUEST_ID = 1


def packet(request_id: int, packet_type: int, body: str) -> bytes:
    payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def recv_exact(sock: socket.socket, size: int) -> bytes:
    data = b""
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise OSError("connection closed")
        data += chunk
    return data


def recv_packet(sock: socket.socket) -> bytes:
    (length,) = struct.unpack("<i", recv_exact(sock, 4))
    return recv_exact(sock, length)


def send_command(host: str, port: int, password: str, command: str) -> None:
    with socket.create_connection((host, port), timeout=5) as sock:
        sock.sendall(packet(REQUEST_ID, AUTH_TYPE, password))
        response = recv_packet(sock)
        (response_id,) = struct.unpack("<i", response[:4])
        if response_id == -1:
            raise OSError("rcon authentication failed")
        sock.sendall(packet(REQUEST_ID + 1, COMMAND_TYPE, command))
        recv_packet(sock)


def main() -> int:
    if len(sys.argv) != 5:
        print(__doc__, file=sys.stderr)
        return 2
    host, port, password, command = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    deadline = time.time() + 30
    while True:
        try:
            send_command(host, port, password, command)
            print(f"rcon: sent {command!r} to {host}:{port}")
            return 0
        except OSError as exc:
            if time.time() >= deadline:
                print(f"rcon: giving up: {exc}", file=sys.stderr)
                return 1
            time.sleep(1)


if __name__ == "__main__":
    sys.exit(main())
