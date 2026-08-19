"""
JDBC Platform MCP Server — mitmproxy addon (auto-extracted from JAR on first HTTP-driver connect).
Logs every intercepted HTTP request + response as a JSON-lines entry.

Log path is resolved from the JDBC_MCP_MITM_LOG_PATH env var, falling back to
<system-temp>/jdbc_mcp_proxy.jsonl so it works on both Windows and Linux/macOS.

Two things are deliberately NOT written verbatim:

  * Credential headers. Authorization/Cookie carry live bearer tokens, and this file lands in the
    system temp directory where anything on the machine can read it. They are replaced with a
    fingerprint that still lets you tell two tokens apart, correlate a refresh, or confirm a header
    was sent at all — without the secret itself.
  * Whole bodies. An uncapped resp_body turned a single file download into a 100 MB log line, which
    is how the capture grew to hundreds of MB. Bodies are capped, with the original size recorded.

Both limits are tunable via JDBC_MCP_MITM_MAX_BODY and JDBC_MCP_MITM_REDACT_HEADERS.
"""
import datetime
import hashlib
import json
import os

LOG_PATH = os.environ.get(
    "JDBC_MCP_MITM_LOG_PATH",
    os.path.join(os.environ.get("TEMP", os.environ.get("TMPDIR", "/tmp")), "jdbc_mcp_proxy.jsonl"),
)

# Max characters kept per body. 0 disables the cap.
try:
    MAX_BODY = int(os.environ.get("JDBC_MCP_MITM_MAX_BODY", "65536"))
except ValueError:
    MAX_BODY = 65536

# Headers whose values are fingerprinted rather than logged.
_DEFAULT_REDACT = "authorization,proxy-authorization,cookie,set-cookie,x-api-key,api-key,x-auth-token"
REDACT_HEADERS = {
    h.strip().lower()
    for h in os.environ.get("JDBC_MCP_MITM_REDACT_HEADERS", _DEFAULT_REDACT).split(",")
    if h.strip()
}


def _now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def _safe_text(content):
    if not content:
        return ""
    try:
        return content.decode("utf-8", errors="replace")
    except Exception:
        return "<binary>"


def _body(content):
    """Decoded body, capped. Returns (text, original_char_len, truncated)."""
    text = _safe_text(content)
    if MAX_BODY > 0 and len(text) > MAX_BODY:
        return text[:MAX_BODY], len(text), True
    return text, len(text), False


def _headers(raw):
    """Header dict with credential values replaced by a stable, non-reversible fingerprint."""
    out = {}
    for key, value in raw.items():
        if key.lower() in REDACT_HEADERS:
            digest = hashlib.sha256(value.encode("utf-8", errors="replace")).hexdigest()[:12]
            # Scheme (Bearer/Basic/…) is kept: it is not secret and identifies the auth flow.
            scheme = value.split(" ", 1)[0] if " " in value else ""
            prefix = scheme + " " if scheme and scheme.lower() in ("bearer", "basic", "digest") else ""
            out[key] = "<redacted %s(len=%d, sha256:%s)>" % (prefix, len(value), digest)
        else:
            out[key] = value
    return out


class JdbcMcpLogger:
    def response(self, flow):
        req = flow.request
        resp = flow.response
        req_body, req_len, req_cut = _body(req.content)
        resp_body, resp_len, resp_cut = _body(resp.content)

        entry = {
            "ts":           _now(),
            "method":       req.method,
            "url":          req.pretty_url,
            "req_headers":  _headers(req.headers),
            "req_body":     req_body,
            "status":       resp.status_code,
            "resp_headers": _headers(resp.headers),
            "resp_body":    resp_body,
        }
        # Only present when something was cut, so untruncated entries stay unchanged in shape.
        if req_cut:
            entry["req_body_truncated"] = True
            entry["req_body_full_chars"] = req_len
        if resp_cut:
            entry["resp_body_truncated"] = True
            entry["resp_body_full_chars"] = resp_len

        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")


addons = [JdbcMcpLogger()]
