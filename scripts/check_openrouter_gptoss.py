#!/usr/bin/env python3
"""
Validate availability of OpenAI gpt-oss-120b on OpenRouter (free vs paid variants).

Checks, for each variant:
  1. that the model exists in the /models catalog and lists which providers serve it
  2. /models/<id>/endpoints provider health/status
  3. a live chat completion (with a tool) to see if it actually responds or 503s

Usage:
    OPENROUTER_API_KEY=sk-or-v1-... python3 check_openrouter_gptoss.py
    # or pass the key as the first arg:
    python3 check_openrouter_gptoss.py sk-or-v1-...
"""
import json
import os
import sys
import urllib.request
import urllib.error

BASE = "https://openrouter.ai/api/v1"
VARIANTS = ["openai/gpt-oss-120b", "openai/gpt-oss-120b:free"]


def get_key() -> str:
    if len(sys.argv) > 1 and sys.argv[1].startswith("sk-or-"):
        return sys.argv[1]
    k = os.environ.get("OPENROUTER_API_KEY", "")
    if not k:
        sys.exit("No key. Set OPENROUTER_API_KEY or pass it as arg 1.")
    return k


def req(url: str, key: str | None = None, body: dict | None = None, timeout: int = 30):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, headers=headers, method="POST" if body else "GET")
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {"error": str(e)}
    except Exception as e:
        return -1, {"error": str(e)}


def check_endpoints(model_id: str, key: str):
    # /models/{author}/{slug}/endpoints
    status, data = req(f"{BASE}/models/{model_id}/endpoints", key)
    if status != 200:
        print(f"    endpoints: HTTP {status} {data.get('error')}")
        return
    eps = (data.get("data") or {}).get("endpoints") or []
    if not eps:
        print("    endpoints: none listed")
        return
    for e in eps:
        name = e.get("provider_name") or e.get("name")
        status_flag = e.get("status", "?")  # 0/None = ok, negative = degraded/disabled
        print(f"    provider={name:<22} status={status_flag}")


def live_chat(model_id: str, key: str):
    body = {
        "model": model_id,
        "messages": [{"role": "user", "content": "set a timer for 5 minutes"}],
        "tools": [{
            "type": "function",
            "function": {
                "name": "set_timer",
                "description": "Set a countdown timer",
                "parameters": {"type": "object",
                               "properties": {"seconds": {"type": "integer"}},
                               "required": ["seconds"]},
            },
        }],
        "tool_choice": "auto",
    }
    status, data = req(f"{BASE}/chat/completions", key, body, timeout=60)
    if status == 200:
        msg = (data.get("choices") or [{}])[0].get("message", {})
        tcs = msg.get("tool_calls")
        if tcs:
            fn = tcs[0].get("function", {})
            return "UP ✅", f"tool_call: {fn.get('name')}({fn.get('arguments','').strip()})"
        return "UP ✅", f"text: {(msg.get('content') or '')[:80]}"
    err = data.get("error", {})
    msg = err.get("message", str(err)) if isinstance(err, dict) else str(err)
    meta = err.get("metadata", {}) if isinstance(err, dict) else {}
    raw = meta.get("raw", "")
    return f"DOWN ❌ (HTTP {status})", f"{msg} {('['+raw+']') if raw else ''}"


def main():
    key = get_key()
    print(f"Key: {key[:14]}…  ({'valid' if key else 'missing'})\n")
    for v in VARIANTS:
        print(f"=== {v} ===")
        check_endpoints(v, key)
        verdict, detail = live_chat(v, key)
        print(f"    live chat: {verdict} — {detail}\n")


if __name__ == "__main__":
    main()
