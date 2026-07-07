package com.cdata.mcp.tools;

import com.cdata.mcp.compiler.JavaSnippetCompiler;
import com.cdata.mcp.compiler.SnippetRunner;
import com.cdata.mcp.compiler.SnippetTemplate;
import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.SessionManager;
import com.cdata.mcp.jdbc.TokenEstimator;
import com.cdata.mcp.jdbc.proxy.InterceptedCall;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.cdata.mcp.tools.JsonUtil.*;

public class ExecuteJavaTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("execute_java")
                .description("""
                        Compile and run a Java code snippet against the active JDBC connection.
                        The snippet runs inside: public static String run(Connection connection) throws Exception { ... }
                        Use 'connection' for JDBC calls. Append output to '__out' (StringBuilder).
                        Standard imports included: java.sql.*, java.math.*, java.util.*, java.io.*
                        Each call is stateless — variables don't persist between calls, but the Connection does.

                        To record the outcome into the session test report (visible in get_test_report), supply:
                          • criterion — label for the check (e.g. 'TC1: DownloadObjects creates missing dirs')
                          • passed    — true/false verdict you evaluated from the snippet output/error
                        If passed is omitted but criterion is given, the check is recorded as passed when
                        there is no runtime_error and failed when there is one.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id", strProp("Session ID from connect"),
                                "code",       strProp("Java code to execute. Use 'connection' and '__out.append(...)' for output."),
                                "criterion",  strProp("(Optional) Label for this check; records it into the test report."),
                                "passed",     boolProp("(Optional) Explicit pass/fail verdict; defaults to no-error=pass.")
                        ),
                        List.of("session_id", "code")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String sessionId = (String) args.get("session_id");
        String code      = (String) args.get("code");
        String criterion = asStr(args.get("criterion"));
        Object passedRaw = args.get("passed");

        if (sessionId == null) return error("session_id is required");
        if (code == null || code.isBlank()) return error("code is required");

        ConnectionSession session = SessionManager.get(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        String uid = UUID.randomUUID().toString().replace("-", "");
        String className = SnippetTemplate.className(uid);
        String source = SnippetTemplate.wrap(code, uid);

        JavaSnippetCompiler.CompileResult compiled;
        try {
            compiled = JavaSnippetCompiler.compile(source, className);
        } catch (Exception e) {
            if (criterion != null && !criterion.isBlank()) {
                session.addCheck(AssertQueryTool.check(criterion, false,
                        "compile error: " + e.getMessage(), null));
            }
            return error("Compiler error: " + e.getMessage());
        }

        if (!compiled.success()) {
            if (criterion != null && !criterion.isBlank()) {
                session.addCheck(AssertQueryTool.check(criterion, false,
                        "compile errors: " + String.join("; ", compiled.errors()), null));
            }
            return McpSchema.CallToolResult.builder()
                    .addTextContent(toJson(Map.of("compile_errors", compiled.errors())))
                    .isError(true)
                    .build();
        }

        session.beginCall();
        SnippetRunner.RunResult run = SnippetRunner.run(compiled.classDir(), className, session.getProxyConnection());
        List<InterceptedCall> calls = session.endCall(0, 0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("output", run.output());
        if (!run.stdout().isEmpty()) response.put("stdout", run.stdout());
        if (!run.stderr().isEmpty()) response.put("stderr", run.stderr());
        if (run.error() != null) response.put("runtime_error", run.error());
        response.put("intercepted_calls", calls.stream().map(InterceptedCall::toMap).collect(Collectors.toList()));

        // Record into test report when a criterion is provided.
        if (criterion != null && !criterion.isBlank()) {
            boolean passed;
            if (passedRaw != null) {
                // Caller supplied an explicit verdict.
                passed = asBool(passedRaw, false);
            } else {
                // Default: no runtime error = pass.
                passed = (run.error() == null);
            }
            String detail = run.error() != null ? "runtime_error: " + run.error()
                    : (run.output().isBlank() ? "executed successfully (no output)" : "output: " + run.output());
            session.addCheck(AssertQueryTool.check(criterion, passed, detail, null));
            response.put("criterion", criterion);
            response.put("recorded_as", passed ? "pass" : "fail");
        }

        long tokens = TokenEstimator.estimate(toJson(response));
        session.addEstimatedTokens(tokens);
        response.put("_meta", Map.of("estimated_tokens", tokens,
                "session_total_tokens", session.getTotalEstimatedTokens()));

        return ok(response);
    }
}
