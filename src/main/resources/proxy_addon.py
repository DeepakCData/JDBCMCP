"""
JDBC MCP Server — mitmproxy addon (auto-extracted from JAR on first HTTP-driver connect).
Logs every intercepted HTTP request + response as a JSON-lines entry.

Log path is resolved from the JDBC_MCP_MITM_LOG_PATH env var, falling back to
<system-temp>/jdbc_mcp_proxy.jsonl so it works on both Windows and Linux/macOS.
"""
import json
import datetime
import os

LOG_PATH = os.environ.get(
    "JDBC_MCP_MITM_LOG_PATH",
    os.path.join(os.environ.get("TEMP", os.environ.get("TMPDIR", "/tmp")), "jdbc_mcp_proxy.jsonl"),
)


def _now():
    return datetime.datetime.utcnow().isoformat() + "Z"


def _safe_text(content):
    if not content:
        return ""
    try:
        return content.decode("utf-8", errors="replace")
    except Exception:
        return "<binary>"


class JdbcMcpLogger:
    def response(self, flow):
        req = flow.request
        resp = flow.response
        entry = {
            "ts":           _now(),
            "method":       req.method,
            "url":          req.pretty_url,
            "req_headers":  dict(req.headers),
            "req_body":     _safe_text(req.content),
            "status":       resp.status_code,
            "resp_headers": dict(resp.headers),
            "resp_body":    _safe_text(resp.content),
        }
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")


addons = [JdbcMcpLogger()]
