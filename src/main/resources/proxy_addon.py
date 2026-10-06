"""
JDBC Platform MCP Server — mitmproxy addon (auto-extracted from JAR on first HTTP-driver connect).

Two addons, in this order:

  1. ResponseMocker — optionally rewrites a backend response before the driver sees it, so a QA
     run can force an edge case the live account will not produce on demand (a field missing from
     the first record, an empty page, a 429, a slow call). Driven by a rules file that is reloaded
     whenever it changes, because one mitmdump serves every session in a server run and restarting
     it would cut the others off.
  2. JdbcMcpLogger — logs every intercepted request + response as a JSON-lines entry.

The logger runs second deliberately: the capture records what the driver actually received, and
mocked entries carry a "mock" block naming the rule and the operations applied. A mutated response
that looked untouched in the capture would turn a mocking mistake into a confident, wrong QA
verdict, so every mutation is evidence in the same file as the traffic.

Log path is resolved from the JDBC_MCP_MITM_LOG_PATH env var, falling back to
<system-temp>/jdbc_mcp_proxy.jsonl so it works on both Windows and Linux/macOS. The mock rules file
is JDBC_MCP_MOCK_RULES_PATH, defaulting to <system-temp>/jdbc_mcp_mock_rules.json.

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
import re
import time

_TEMP = os.environ.get("TEMP", os.environ.get("TMPDIR", "/tmp"))

LOG_PATH = os.environ.get("JDBC_MCP_MITM_LOG_PATH", os.path.join(_TEMP, "jdbc_mcp_proxy.jsonl"))

MOCK_RULES_PATH = os.environ.get(
    "JDBC_MCP_MOCK_RULES_PATH", os.path.join(_TEMP, "jdbc_mcp_mock_rules.json")
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


# ---------------------------------------------------------------------------
# Response mocking
# ---------------------------------------------------------------------------

_MISSING = object()

# "results[*].subscriptionDetails.subscriptionId" → key/index tokens.
_TOKEN_RE = re.compile(r"\[(-?\d+|\*)\]|([^.\[\]]+)")


def _parse_path(path):
    toks = []
    for m in _TOKEN_RE.finditer(path or ""):
        if m.group(1) is not None:
            toks.append(("idx", m.group(1)))
        else:
            toks.append(("key", m.group(2)))
    return toks


def _step(container, kind, val):
    """One token's worth of descent. Returns a list of child values (possibly empty)."""
    if kind == "key":
        if isinstance(container, dict) and val in container:
            return [container[val]]
        return []
    if not isinstance(container, list):
        return []
    if val == "*":
        return list(container)
    i = int(val)
    return [container[i]] if -len(container) <= i < len(container) else []


def _targets(root, toks):
    """Yield (container, key_or_index) pairs addressed by the final token."""
    if not toks:
        return
    cursors = [root]
    for kind, val in toks[:-1]:
        nxt = []
        for c in cursors:
            nxt.extend(_step(c, kind, val))
        cursors = nxt
        if not cursors:
            return
    kind, val = toks[-1]
    for c in cursors:
        if kind == "key":
            if isinstance(c, dict):
                yield c, val
        elif isinstance(c, list):
            if val == "*":
                for i in range(len(c)):
                    yield c, i
            else:
                i = int(val)
                if -len(c) <= i < len(c):
                    yield c, i % len(c)


def _lookup(root, path):
    """First value at a relative path, or _MISSING."""
    for container, key in _targets(root, _parse_path(path)):
        try:
            return container[key]
        except (KeyError, IndexError, TypeError):
            return _MISSING
    return _MISSING


def _node(root, path):
    """The single value a path addresses (used by ops that act on a container), or _MISSING."""
    return _lookup(root, path)


def _op_remove(doc, op):
    toks = _parse_path(op.get("path"))
    dict_hits, list_hits = [], {}
    for container, key in _targets(doc, toks):
        if isinstance(container, dict):
            if key in container:
                dict_hits.append((container, key))
        elif isinstance(container, list):
            list_hits.setdefault(id(container), (container, []))[1].append(key)
    for container, key in dict_hits:
        del container[key]
    # Descending, so earlier deletions do not shift the indices still to be removed.
    for container, indices in list_hits.values():
        for i in sorted(set(indices), reverse=True):
            del container[i]
    return len(dict_hits) + sum(len(v[1]) for v in list_hits.values())


def _op_set(doc, op):
    toks = _parse_path(op.get("path"))
    value = op.get("value")
    count = 0
    if not toks:
        return 0
    # A dict key that does not exist yet is created, so a rule can add a field the API omitted.
    parent_toks, (kind, val) = toks[:-1], toks[-1]
    parents = [doc]
    for p_kind, p_val in parent_toks:
        nxt = []
        for c in parents:
            nxt.extend(_step(c, p_kind, p_val))
        parents = nxt
    for c in parents:
        if kind == "key" and isinstance(c, dict):
            c[val] = value
            count += 1
        elif kind == "idx" and isinstance(c, list):
            if val == "*":
                for i in range(len(c)):
                    c[i] = value
                    count += 1
            else:
                i = int(val)
                if -len(c) <= i < len(c):
                    c[i] = value
                    count += 1
    return count


def _op_move_to_front(doc, op):
    target = _node(doc, op.get("path"))
    if target is _MISSING or not isinstance(target, list) or not target:
        return 0
    missing_path = op.get("where_missing")
    present_path = op.get("where_present")
    index = op.get("index")

    if index is not None:
        i = int(index)
        if not (-len(target) <= i < len(target)):
            return 0
        target.insert(0, target.pop(i))
        return 1

    def selected(elem):
        if missing_path:
            v = _lookup(elem, missing_path)
            return v is _MISSING or v is None
        if present_path:
            v = _lookup(elem, present_path)
            return v is not _MISSING and v is not None
        return False

    if not (missing_path or present_path):
        return 0
    head = [e for e in target if selected(e)]
    if not head:
        return 0
    tail = [e for e in target if not selected(e)]
    target[:] = head + tail
    return len(head)


def _op_truncate(doc, op):
    target = _node(doc, op.get("path"))
    if target is _MISSING or not isinstance(target, list):
        return 0
    keep = max(0, int(op.get("keep", 0)))
    if len(target) <= keep:
        return 0
    removed = len(target) - keep
    del target[keep:]
    return removed


_JSON_OPS = {
    "remove": _op_remove,
    "set": _op_set,
    "move_to_front": _op_move_to_front,
    "truncate": _op_truncate,
}


class _RuleStore:
    """Rules file, reloaded whenever it changes. Hit counts are in-memory and reset on reload."""

    def __init__(self, path):
        self.path = path
        self._stamp = None
        self._rules = []
        self.hits = {}

    def rules(self):
        try:
            st = os.stat(self.path)
            stamp = (st.st_mtime_ns, st.st_size)
        except OSError:
            if self._stamp is not None:
                self._stamp, self._rules, self.hits = None, [], {}
            return self._rules

        if stamp != self._stamp:
            self._stamp = stamp
            self.hits = {}  # a changed file is a new experiment; old counts would cap it early
            try:
                with open(self.path, encoding="utf-8") as f:
                    doc = json.load(f)
                raw = doc.get("rules", []) if isinstance(doc, dict) else doc
                self._rules = [r for r in raw if isinstance(r, dict) and r.get("enabled", True)]
            except Exception:
                self._rules = []  # a half-written or invalid file must not mutate anything
        return self._rules

    def exhausted(self, rule):
        cap = int(rule.get("max_hits") or 0)
        return cap > 0 and self.hits.get(rule.get("id"), 0) >= cap

    def record_hit(self, rule):
        rid = rule.get("id")
        self.hits[rid] = self.hits.get(rid, 0) + 1
        return self.hits[rid]


def _matches(rule, flow):
    m = rule.get("match") or {}
    url = flow.request.pretty_url

    needle = m.get("url_contains")
    if needle and needle not in url:
        return False

    pattern = m.get("url_regex")
    if pattern:
        try:
            if not re.search(pattern, url):
                return False
        except re.error:
            return False

    method = m.get("method")
    if method and method.upper() != flow.request.method.upper():
        return False

    status = m.get("status")
    if status is not None and int(status) != flow.response.status_code:
        return False

    body_needle = m.get("body_contains")
    if body_needle:
        try:
            if body_needle not in (flow.response.get_text(strict=False) or ""):
                return False
        except Exception:
            return False

    return True


class ResponseMocker:
    def __init__(self):
        self.store = _RuleStore(MOCK_RULES_PATH)

    def response(self, flow):
        rules = self.store.rules()
        if not rules or flow.response is None:
            return

        applied = []
        for rule in rules:
            try:
                if self.store.exhausted(rule) or not _matches(rule, flow):
                    continue
                changes = self._apply(flow, rule)
            except Exception as exc:  # a broken rule must never break the request
                applied.append({"rule": rule.get("id"), "error": "%s: %s" % (type(exc).__name__, exc)})
                continue
            if changes:
                applied.append({
                    "rule": rule.get("id"),
                    "hit": self.store.record_hit(rule),
                    "ops": changes,
                })

        if applied:
            meta = getattr(flow, "metadata", None)
            if isinstance(meta, dict):
                meta["jdbc_mcp_mock"] = applied

    def _apply(self, flow, rule):
        """Run a rule's ops in order. Returns a list of op descriptions that changed something."""
        changes = []
        doc = None
        doc_loaded = False
        dirty = False

        def flush():
            nonlocal dirty
            if dirty:
                flow.response.set_text(json.dumps(doc))
                dirty = False

        for op in rule.get("ops") or []:
            kind = (op.get("op") or "").lower()

            if kind == "delay":
                ms = max(0, int(op.get("ms", 0)))
                if ms:
                    time.sleep(ms / 1000.0)
                    changes.append({"op": "delay", "ms": ms})

            elif kind == "status":
                flow.response.status_code = int(op.get("value"))
                changes.append({"op": "status", "value": flow.response.status_code})

            elif kind == "header":
                name = op.get("name")
                if name:
                    if op.get("remove"):
                        flow.response.headers.pop(name, None)
                        changes.append({"op": "header", "name": name, "removed": True})
                    else:
                        flow.response.headers[name] = str(op.get("value", ""))
                        changes.append({"op": "header", "name": name})

            elif kind == "body":
                flush()
                doc, doc_loaded = None, False  # raw override discards any parsed document
                flow.response.set_text(str(op.get("value", "")))
                changes.append({"op": "body", "chars": len(str(op.get("value", "")))})

            elif kind in _JSON_OPS:
                if not doc_loaded:
                    doc_loaded = True
                    try:
                        doc = json.loads(flow.response.get_text(strict=False))
                    except Exception:
                        doc = None
                if doc is None:
                    changes.append({"op": kind, "skipped": "response_body_is_not_json"})
                    continue
                affected = _JSON_OPS[kind](doc, op)
                if affected:
                    dirty = True
                    changes.append({"op": kind, "path": op.get("path"), "affected": affected})
                else:
                    changes.append({"op": kind, "path": op.get("path"), "affected": 0})

            elif kind:
                changes.append({"op": kind, "skipped": "unknown_op"})

        flush()
        # Ops that matched nothing still belong in the record: "the rule ran and changed nothing"
        # is exactly what someone debugging an unexpected PASS needs to see.
        return changes


class JdbcMcpLogger:
    def response(self, flow):
        req = flow.request
        resp = flow.response
        req_body, req_len, req_cut = _body(req.content)
        resp_body, resp_len, resp_cut = _body(resp.content)

        # Round-trip time, so a timed-out query can be attributed to backend latency rather than
        # to row volume or client-side work. mitmproxy timestamps are epoch seconds (floats).
        duration_ms = None
        try:
            if req.timestamp_start and resp.timestamp_end:
                duration_ms = int((resp.timestamp_end - req.timestamp_start) * 1000)
        except Exception:
            duration_ms = None

        entry = {
            "ts":           _now(),
            "duration_ms":  duration_ms,
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

        # Present only on a mutated response. resp_body above is post-mutation — what the driver
        # actually parsed — so this block is the only thing distinguishing a mock from live data.
        meta = getattr(flow, "metadata", None)
        if isinstance(meta, dict) and meta.get("jdbc_mcp_mock"):
            entry["mock"] = meta["jdbc_mcp_mock"]

        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")


addons = [ResponseMocker(), JdbcMcpLogger()]
