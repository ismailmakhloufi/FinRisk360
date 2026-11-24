"""Expose only the iPhone's SMS relay routes to a private HTTPS proxy."""

import re
from http import HTTPStatus
from http.client import HTTPConnection, HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


RELAY_ROUTE = re.compile(
    r"/internal/sms-relay/(?:claim|"
    r"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}/submitted)"
)


class SmsRelayHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if not RELAY_ROUTE.fullmatch(self.path):
            self.send_error(HTTPStatus.NOT_FOUND)
            return

        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            size = -1
        if not 0 <= size <= 128 or self.headers.get("Transfer-Encoding"):
            self.send_error(HTTPStatus.BAD_REQUEST)
            return
        self.rfile.read(size)

        upstream = HTTPConnection("127.0.0.1", 8085, timeout=5)
        try:
            upstream.request(
                "POST", self.path, body=b"",
                headers={"Authorization": self.headers.get("Authorization", "")},
            )
            reply = upstream.getresponse()
            body = reply.read(4097)
            status = reply.status
        except (OSError, HTTPException):
            self.send_error(HTTPStatus.BAD_GATEWAY)
            return
        finally:
            upstream.close()

        if len(body) > 4096 or status not in (200, 204, 400, 401, 403, 404):
            self.send_error(HTTPStatus.BAD_GATEWAY)
            return
        self.send_response(status)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        if body:
            self.send_header("Content-Type", "application/json")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        self.send_error(HTTPStatus.NOT_FOUND)

    def log_message(self, *_args):
        pass


if __name__ == "__main__":
    with ThreadingHTTPServer(("127.0.0.1", 8765), SmsRelayHandler) as server:
        server.serve_forever()
