"""
Exercises the mitmproxy addon's response-mocking engine without mitmproxy or a network.

    python src/test/python/test_proxy_addon.py

There is no JUnit/surefire in this project, so this runs standalone and exits non-zero on failure.
It is worth having: the engine rewrites real API responses in flight, and a path bug there does not
announce itself — it produces a test that quietly passes against live data.

The fixture is the DRIVERS-63278 shape: a HubSpot MarketingEmails page whose first record lacks
subscriptionId/subscriptionName while later records have them.
"""
import json
import os
import sys
import tempfile

TMP = tempfile.mkdtemp()
os.environ["JDBC_MCP_MITM_LOG_PATH"] = os.path.join(TMP, "cap.jsonl")
os.environ["JDBC_MCP_MOCK_RULES_PATH"] = os.path.join(TMP, "rules.json")

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.normpath(os.path.join(_HERE, "..", "..", "main", "resources")))
import proxy_addon as A  # noqa: E402


class Headers(dict):
    def pop(self, k, d=None):
        return dict.pop(self, k, d)


class Req:
    def __init__(self, url, method="GET"):
        self.pretty_url = url
        self.method = method
        self.content = b""
        self.headers = Headers()
        self.timestamp_start = 1.0
        self.pretty_host = url.split("/")[2]
        self.path = "/" + "/".join(url.split("/")[3:])


class Resp:
    def __init__(self, text, status=200):
        self._t = text
        self.status_code = status
        self.headers = Headers({"content-type": "application/json"})
        self.timestamp_end = 2.0

    def get_text(self, strict=True):
        return self._t

    def set_text(self, t):
        self._t = t

    @property
    def content(self):
        return self._t.encode()


class Flow:
    def __init__(self, url, body, status=200, method="GET"):
        self.request = Req(url, method)
        self.response = Resp(body, status)
        self.metadata = {}


def arm(rules):
    with open(os.environ["JDBC_MCP_MOCK_RULES_PATH"], "w") as f:
        json.dump({"rules": rules}, f)


HUB = "https://api.hubapi.com/marketing/v3/emails/?limit=100"
BODY = json.dumps({"results": [
    {"id": "1", "subscriptionDetails": {"officeLocationId": "629", "subscriptionId": "744",
                                        "subscriptionName": "Marketing Information"}},
    {"id": "2", "subscriptionDetails": {"officeLocationId": "629"}},
    {"id": "3", "subscriptionDetails": {"officeLocationId": "629", "subscriptionId": "999",
                                        "subscriptionName": "Other"}},
]})

fails = []


def check(name, cond, extra=""):
    print(("  PASS  " if cond else "  FAIL  ") + name + ("" if cond else "   " + str(extra)))
    if not cond:
        fails.append(name)


m = A.ResponseMocker()
log = A.JdbcMcpLogger()

# 1 — remove touches only the record the path names
arm([{"id": "r1", "match": {"url_contains": "api.hubapi.com"}, "ops": [
    {"op": "remove", "path": "results[0].subscriptionDetails.subscriptionId"},
    {"op": "remove", "path": "results[0].subscriptionDetails.subscriptionName"}]}])
f = Flow(HUB, BODY)
m.response(f)
d = json.loads(f.response.get_text())
check("remove: first record stripped", "subscriptionId" not in d["results"][0]["subscriptionDetails"], d["results"][0])
check("remove: first keeps officeLocationId", d["results"][0]["subscriptionDetails"]["officeLocationId"] == "629")
check("remove: later records untouched", d["results"][2]["subscriptionDetails"]["subscriptionId"] == "999")
check("remove: metadata recorded", f.metadata["jdbc_mcp_mock"][0]["ops"][0]["affected"] == 1, f.metadata)

# 2 — the capture must show the mutation and the body the driver actually parsed
log.response(f)
entry = json.loads(open(os.environ["JDBC_MCP_MITM_LOG_PATH"]).read().strip().split("\n")[-1])
check("log: mock block present", "mock" in entry and entry["mock"][0]["rule"] == "r1")
check("log: resp_body is post-mutation", "744" not in entry["resp_body"], entry["resp_body"][:120])

# 3 — move_to_front reproduces the ticket without editing any record
arm([{"id": "r2", "match": {"url_contains": "hubapi"}, "ops": [
    {"op": "move_to_front", "path": "results", "where_missing": "subscriptionDetails.subscriptionId"}]}])
f = Flow(HUB, BODY)
m.response(f)
d = json.loads(f.response.get_text())
check("move_to_front: incomplete record first", d["results"][0]["id"] == "2", [r["id"] for r in d["results"]])
check("move_to_front: others keep order", [r["id"] for r in d["results"]] == ["2", "1", "3"])

# 4 — set spans [*] and creates a key that was absent
arm([{"id": "r3", "match": {"url_contains": "hubapi"}, "ops": [
    {"op": "set", "path": "results[*].subscriptionDetails.subscriptionName", "value": "ZZZ"}]}])
f = Flow(HUB, BODY)
m.response(f)
d = json.loads(f.response.get_text())
check("set: all rows set incl. created key",
      all(r["subscriptionDetails"]["subscriptionName"] == "ZZZ" for r in d["results"]))

# 5 — truncate gives the empty-page case
arm([{"id": "r4", "match": {"url_contains": "hubapi"}, "ops": [{"op": "truncate", "path": "results", "keep": 0}]}])
f = Flow(HUB, BODY)
m.response(f)
check("truncate: empty page", json.loads(f.response.get_text())["results"] == [])

# 6 — response-level ops, and no reach beyond the match
arm([{"id": "r5", "match": {"url_contains": "hubapi", "method": "GET"}, "ops": [
    {"op": "status", "value": 429}, {"op": "header", "name": "Retry-After", "value": "30"}]}])
f = Flow(HUB, BODY)
m.response(f)
check("status: 429 forced", f.response.status_code == 429)
check("header: Retry-After set", f.response.headers.get("Retry-After") == "30")
f2 = Flow("https://example.com/other", BODY)
m.response(f2)
check("match: unrelated url untouched", f2.response.status_code == 200 and f2.metadata == {})

# 7 — max_hits stops after the cap
arm([{"id": "r6", "match": {"url_contains": "hubapi"}, "max_hits": 1, "ops": [{"op": "status", "value": 500}]}])
a = Flow(HUB, BODY)
m.response(a)
b = Flow(HUB, BODY)
m.response(b)
check("max_hits: first applied", a.response.status_code == 500)
check("max_hits: second untouched", b.response.status_code == 200)

# 8 — a wrong path is reported, not silently ignored: this is the one that saves a bogus PASS
arm([{"id": "r7", "match": {"url_contains": "hubapi"}, "ops": [{"op": "remove", "path": "results[0].nope.alsoNope"}]}])
f = Flow(HUB, BODY)
m.response(f)
check("bad path: affected 0 recorded", f.metadata["jdbc_mcp_mock"][0]["ops"][0]["affected"] == 0, f.metadata)
check("bad path: body unchanged", json.loads(f.response.get_text()) == json.loads(BODY))

# 9 — a non-JSON body must survive a JSON op untouched
arm([{"id": "r8", "match": {"url_contains": "hubapi"}, "ops": [{"op": "remove", "path": "results[0].x"}]}])
f = Flow(HUB, "<html>not json</html>")
m.response(f)
check("non-json: body preserved", f.response.get_text() == "<html>not json</html>")
check("non-json: skip recorded",
      f.metadata["jdbc_mcp_mock"][0]["ops"][0].get("skipped") == "response_body_is_not_json")

# 10 — clearing the file disarms on the next response, with no restart
os.remove(os.environ["JDBC_MCP_MOCK_RULES_PATH"])
f = Flow(HUB, BODY)
m.response(f)
check("cleared: passthrough", f.metadata == {} and json.loads(f.response.get_text()) == json.loads(BODY))

# 11 — a half-written file must fail closed (mutate nothing), never partially
with open(os.environ["JDBC_MCP_MOCK_RULES_PATH"], "w") as fh:
    fh.write("{not json")
f = Flow(HUB, BODY)
m.response(f)
check("malformed rules: passthrough", f.metadata == {})

print("\n" + ("ALL PASS" if not fails else "FAILED: " + ", ".join(fails)))
sys.exit(1 if fails else 0)
