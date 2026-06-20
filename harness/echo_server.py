#!/usr/bin/env python3
"""
Simple robust echo server. Logs everything to stdout and stderr.
"""
import sys
import json
import hashlib
import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 9999

class EchoHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"  # simpler, avoids keep-alive issues

    def _handle(self, method):
        sys.stdout.write(f"\n{'='*60}\n")
        sys.stdout.write(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] {method} {self.path}\n")
        sys.stdout.write(f"{'='*60}\n")
        sys.stdout.flush()

        try:
            # Read body
            cl = self.headers.get('Content-Length')
            content_length = int(cl) if cl else 0
            sys.stdout.write(f"Content-Length header: {cl}\n")
            sys.stdout.flush()

            body = b''
            if content_length > 0:
                body = self.rfile.read(content_length)

            # Collect all headers
            headers = {}
            for key in self.headers.keys():
                headers[key] = self.headers[key]

            sys.stdout.write(f"Headers received ({len(headers)} keys):\n")
            for k in sorted(headers.keys()):
                sys.stdout.write(f"  {k}: {headers[k]}\n")
            sys.stdout.flush()

            # Compute body hash
            body_sha = hashlib.sha256(body).hexdigest() if body else ""

            response = {
                "method": method,
                "path": self.path,
                "headers": headers,
                "body_length": len(body),
                "body_sha256": body_sha,
                "body_first100": body[:100].decode('utf-8', errors='replace') if body else "",
                "timestamp": datetime.datetime.now().isoformat(),
            }

            sys.stdout.write(f"\nBody: {len(body)} bytes\n")
            if body:
                sys.stdout.write(f"Body SHA256: {body_sha}\n")
                sys.stdout.write(f"Body first 100: {body[:100].decode('utf-8', errors='replace')}\n")
            sys.stdout.write(f"{'='*60}\n")
            sys.stdout.flush()

            # Send response
            resp_json = json.dumps(response, indent=2).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(resp_json)))
            self.end_headers()
            self.wfile.write(resp_json)
            self.wfile.flush()

        except Exception as e:
            sys.stderr.write(f"HANDLER ERROR: {e}\n")
            import traceback
            traceback.print_exc(file=sys.stderr)
            sys.stderr.flush()
            try:
                err = f"Server error: {e}".encode()
                self.send_response(500)
                self.send_header('Content-Length', str(len(err)))
                self.end_headers()
                self.wfile.write(err)
                self.wfile.flush()
            except:
                pass

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_PUT(self):
        self._handle("PUT")

    def do_DELETE(self):
        self._handle("DELETE")

    def log_message(self, fmt, *args):
        sys.stdout.write(f"[{self.client_address[0]}] {fmt % args}\n")
        sys.stdout.flush()

def main():
    print(f"Echo server on http://0.0.0.0:{PORT}")
    print(f"Listening...", flush=True)
    server = HTTPServer(('0.0.0.0', PORT), EchoHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down")
        server.server_close()

if __name__ == '__main__':
    main()