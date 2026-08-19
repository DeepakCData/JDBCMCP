package com.cdata.mcp;

import com.cdata.mcp.log.LogJanitor;
import com.cdata.mcp.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        // Redirect slf4j-simple to stderr so stdout stays clean for MCP JSON-RPC
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");

        // Capture housekeeping, before any connect can write to the logs: start this run with a
        // fresh mitmproxy capture, then clear out stale driver logs. Reported on stderr (never
        // stdout — that channel is MCP JSON-RPC only).
        System.err.println("[jdbc-mcp] " + LogJanitor.rotateMitmLog());
        System.err.println("[jdbc-mcp] " + LogJanitor.sweep().summary());

        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("jdbc-platform", "1.0.0")
                .toolCall(LoadDriverTool.tool(),    LoadDriverTool::handle)
                .toolCall(ConnectTool.tool(),       ConnectTool::handle)
                .toolCall(ExecuteQueryTool.tool(),  ExecuteQueryTool::handle)
                .toolCall(ExecuteUpdateTool.tool(), ExecuteUpdateTool::handle)
                .toolCall(GetMetadataTool.tool(),     GetMetadataTool::handle)
                .toolCall(ExecutePreparedTool.tool(), ExecutePreparedTool::handle)
                .toolCall(ExecuteJavaTool.tool(),   ExecuteJavaTool::handle)
                .toolCall(ListSessionsTool.tool(),  ListSessionsTool::handle)
                .toolCall(RecordCheckTool.tool(),   RecordCheckTool::handle)
                .toolCall(AssertQueryTool.tool(),   AssertQueryTool::handle)
                .toolCall(CompareQueriesTool.tool(), CompareQueriesTool::handle)
                .toolCall(GetTestReportTool.tool(), GetTestReportTool::handle)
                .toolCall(ExportResultsTool.tool(), ExportResultsTool::handle)
                .toolCall(DisconnectTool.tool(),    DisconnectTool::handle)
                .build();

        // StdioServerTransportProvider blocks on stdin in a background thread;
        // keep the main thread alive until the process is killed.
        Thread.currentThread().join();
    }
}
