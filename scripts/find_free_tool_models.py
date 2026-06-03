#!/usr/bin/env python3
"""
Find FREE, tool-capable OpenRouter models that are actually UP right now.

For each free (:free / zero-priced) model whose supported_parameters include "tools",
runs a live tool-calling chat and reports UP/DOWN, so we pick one that works.

Usage:
    python3 find_free_tool_models.py sk-or-v1-...
"""
import json
import sys
import urllib.request
import urllib.error

BASE = "https://openrouter.ai/api/v1"


def req(url, key=None, body=None, timeout=45):
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


def is_free(m):
    if m.get("id", "").endswith(":free"):
        return True
    p = m.get("pricing", {})
    return p.get("prompt") == "0" and p.get("completion") == "0"


def tool_capable(m):
    return "tools" in (m.get("supported_parameters") or [])


def live(model_id, key):
    body = {
        "model": model_id,
        "messages": [{"role": "user", "content": "set a timer for 5 minutes"}],
        "tools": [{"type": "function", "function": {
            "name": "set_timer", "description": "Set a countdown timer",
            "parameters": {"type": "object", "properties": {"seconds": {"type": "integer"}}, "required": ["seconds"]}}}],
        "tool_choice": "auto",
    }
    status, data = req(f"{BASE}/chat/completions", key, body, timeout=60)
    if status == 200:
        msg = (data.get("choices") or [{}])[0].get("message", {})
        if msg.get("tool_calls"):
            return True, "tool_call OK"
        return True, f"text only: {(msg.get('content') or '')[:50]}"
    err = data.get("error", {})
    m = err.get("message", str(err)) if isinstance(err, dict) else str(err)
    return False, f"HTTP {status}: {m[:60]}"


def main():
    key = sys.argv[1] if len(sys.argv) > 1 else ""
    if not key.startswith("sk-or-"):
        sys.exit("pass key as arg1")
    status, data = req(f"{BASE}/models")
    models = data.get("data", [])
    free_tool = [m for m in models if is_free(m) and tool_capable(m)]
    print(f"{len(models)} total models; {len(free_tool)} are FREE + tool-capable.\n")
    # Prefer well-known, larger/instruct models first for quality
    def score(m):
        i = m["id"].lower()
        kw = ["llama-3.3", "llama-4", "qwen", "gemma", "deepseek", "mistral", "gpt-oss", "kimi", "nemotron"]
        return next((len(kw) - n for n, k in enumerate(kw) if k in i), 0)
    free_tool.sort(key=score, reverse=True)

    print("Live-testing free tool-capable models (tool call = best):\n")
    up = []
    for m in free_tool:
        ok, detail = live(m["id"], key)
        flag = "UP ✅" if ok else "DOWN ❌"
        ctx = m.get("context_length", 0)
        print(f"  {flag:<8} {m['id']:<55} ctx={ctx:<7} {detail}")
        if ok and "tool_call OK" in detail:
            up.append(m["id"])
    print("\n=== RECOMMENDED (free, tool-capable, UP, returns tool calls) ===")
    for x in up[:8]:
        print(f"  {x}")
    if not up:
        print("  (none returned a clean tool call right now)")


if __name__ == "__main__":
    main()
