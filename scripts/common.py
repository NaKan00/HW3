"""Shared standard-library helpers for local demonstrations."""
import json
import os
import subprocess
import time
import urllib.request
import urllib.error
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("BOOKING_URL", "http://localhost:8080")
FLIGHT = "11111111-1111-1111-1111-111111111111"
USER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
DATE = "2030-06-15"
def compose(*args):
    return subprocess.check_output(["docker", "compose", *args], cwd=ROOT, text=True).strip()
def api(path, data=None, expected=200):
    request = urllib.request.Request(BASE + path, data=None if data is None else json.dumps(data).encode(), headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status, body = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, body = error.code, json.load(error)
    assert status == expected, (path, status, body)
    return body

def ready(timeout=150):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if api("/actuator/health")["status"] == "UP":
                return
        except (OSError, AssertionError):
            pass
        time.sleep(1)
    raise RuntimeError("Services did not become healthy")

def booking(seats=2):
    return {"user_id": USER, "flight_id": FLIGHT, "passenger_name": "Demo Passenger", "passenger_email": "demo@example.org", "seat_count": seats}

def sql(service, query):
    return compose("exec", "-T", service + "-postgres", "psql", "-U", service, "-d", service, "-At", "-c", query)

def redis(*args):
    host = compose("exec", "-T", "redis-sentinel", "redis-cli", "-p", "26379", "--raw", "sentinel", "get-master-addr-by-name", "mymaster").splitlines()
    return compose("exec", "-T", "redis-sentinel", "redis-cli", "-h", host[0], "-p", host[1], "--raw", *args)
