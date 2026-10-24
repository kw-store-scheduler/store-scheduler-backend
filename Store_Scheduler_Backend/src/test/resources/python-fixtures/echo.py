import json, sys
config = json.loads(sys.stdin.read())
print("warning from engine", file=sys.stderr)
print(json.dumps({"status": "OPTIMAL", "echo": config}, ensure_ascii=False))
