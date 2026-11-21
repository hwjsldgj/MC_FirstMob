#!/usr/bin/env python3
"""向服务端发一条或多条 RCON 命令并打印回包。

用法：
    rcon_cmd.py <host> <port> <password> <command> [<command> ...]
    rcon_cmd.py <host> <port> <password> --file <path>

`--file` 从 UTF-8 文本读命令，一行一条，忽略空行与 `#` 开头的注释；
适合含引号/NBT 的长命令，避开 shell 转义。

只用标准库，CI 的 ubuntu-latest 自带 python3。
回包按行打印到 stdout，供 CI 断言或本地排查。
"""
import socket
import struct
import sys
import time

AUTH_TYPE = 3
COMMAND_TYPE = 2
CONNECT_TIMEOUT_SECONDS = 5
RETRY_SECONDS = 30


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


def run(host: str, port: int, password: str, commands: list[str]) -> int:
    deadline = time.time() + RETRY_SECONDS
    while True:
        try:
            sock = socket.create_connection((host, port), timeout=CONNECT_TIMEOUT_SECONDS)
            break
        except OSError as exc:
            if time.time() >= deadline:
                print(f"rcon: connect failed: {exc}", file=sys.stderr)
                return 1
            time.sleep(1)

    with sock:
        sock.sendall(packet(1, AUTH_TYPE, password))
        response = recv_packet(sock)
        (response_id,) = struct.unpack("<i", response[:4])
        if response_id == -1:
            print("rcon: authentication failed", file=sys.stderr)
            return 1
        for index, command in enumerate(commands, start=2):
            sock.sendall(packet(index, COMMAND_TYPE, command))
            body = recv_packet(sock)[8:-2].decode("utf-8", "replace")
            print(f"> {command}")
            print(body)
    return 0


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) < 5:
        print(__doc__, file=sys.stderr)
        return 2
    host, port, password = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    args = sys.argv[4:]
    if args[0] == "--file":
        if len(args) != 2:
            print(__doc__, file=sys.stderr)
            return 2
        with open(args[1], encoding="utf-8-sig") as handle:
            commands = [line.strip() for line in handle if line.strip() and not line.lstrip().startswith("#")]
    else:
        commands = args
    return run(host, port, password, commands)


if __name__ == "__main__":
    sys.exit(main())
