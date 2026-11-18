"""只在管理器私有目录接收连接。固定已打开的 socket inode，并校验上游进程身份。"""
import os
import select
import socket
import socketserver
import stat
import struct
import threading


def connect_ide(path):
    fd=os.open(path,os.O_PATH|os.O_NOFOLLOW)
    peer=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
    try:
        info=os.fstat(fd)
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid!=os.geteuid():
            raise PermissionError("unexpected IDE socket owner or type")
        peer.connect("/proc/self/fd/"+str(fd))
        _,uid,_=struct.unpack("3i",peer.getsockopt(socket.SOL_SOCKET,socket.SO_PEERCRED,12))
        if uid!=os.geteuid():
            raise PermissionError("unexpected IDE peer")
        return peer
    except BaseException:
        peer.close()
        raise
    finally:
        os.close(fd)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with connect_ide(self.server.upstream) as peer:
                while True:
                    readable,_,_=select.select((self.request,peer),(),(),60)
                    for source in readable:
                        data=source.recv(65536)
                        if not data:
                            return
                        (peer if source is self.request else self.request).sendall(data)
        except OSError:
            return


class Relay(socketserver.ThreadingUnixStreamServer):
    daemon_threads=True

    def __init__(self, listener, upstream):
        self.upstream=upstream
        if listener.exists():
            if not listener.is_socket():
                raise ValueError("gateway socket path occupied")
            listener.unlink()
        super().__init__(str(listener),Handler)
        listener.chmod(0o600)
        self.listener=listener
        self.thread=threading.Thread(target=self.serve_forever,daemon=True)
        self.thread.start()

    def stop(self):
        self.shutdown()
        self.thread.join(2)
        self.server_close()
        self.listener.unlink(missing_ok=True)
