#!/usr/bin/env python3
"""本地TCP转发：127.0.0.1:LISTEN -> 127.0.0.1:TARGET。杀掉进程即模拟应用到数据库的网络中断，不触碰共享MySQL容器。"""
import asyncio, sys
LISTEN, TARGET = int(sys.argv[1]), int(sys.argv[2])
async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data); await writer.drain()
    except Exception:
        pass
    finally:
        writer.close()
async def handle(client_reader, client_writer):
    try:
        server_reader, server_writer = await asyncio.open_connection('127.0.0.1', TARGET)
    except Exception:
        client_writer.close(); return
    await asyncio.gather(pipe(client_reader, server_writer), pipe(server_reader, client_writer))
async def main():
    server = await asyncio.start_server(handle, '127.0.0.1', LISTEN)
    async with server: await server.serve_forever()
asyncio.run(main())
