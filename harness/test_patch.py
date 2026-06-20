"""Test that the wire tap patch actually fires on requests.Session.send"""
import requests, hashlib, json, os
from datetime import datetime

CAPTURE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_capture.json")

_original_send = requests.Session.send

def _get_body_bytes(pr):
    body = pr.body
    if body is None: return b''
    if isinstance(body, bytes): return body
    if isinstance(body, str): return body.encode('utf-8')
    return b''

def _logging_send(self, request, **kwargs):
    is_post = request.method == "POST"
    print(f"[WIRE TAP] {request.method} {request.url}", flush=True)
    if is_post:
        body_bytes = _get_body_bytes(request)
        capture = {
            "method": request.method,
            "url": request.url,
            "headers": dict(request.headers),
            "body": body_bytes.decode('utf-8', errors='replace'),
            "body_length": len(body_bytes),
            "body_sha256": hashlib.sha256(body_bytes).hexdigest(),
            "timestamp": datetime.now().isoformat(),
        }
    response = _original_send(self, request, **kwargs)
    print(f"[WIRE TAP] {request.method} response: {response.status_code}", flush=True)
    if is_post:
        capture["response"] = {
            "status_code": response.status_code,
            "headers": dict(response.headers),
            "body": response.text[:500],
        }
        with open(CAPTURE_FILE, 'w') as f:
            json.dump(capture, f, indent=2)
        print(f"[WIRE TAP] Written to {CAPTURE_FILE}", flush=True)
    return response

requests.Session.send = _logging_send

# Test with a GET
s = requests.Session()
r = s.get("http://httpbin.org/get")
print(f"GET result: {r.status_code}")

# Test with a POST
r = s.post("http://httpbin.org/post", json={"test": "hello"}, headers={"Content-Type": "application/json"})
print(f"POST result: {r.status_code}")