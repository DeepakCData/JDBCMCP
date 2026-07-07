package com.cdata.mcp;

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

        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("jdbc-mcp-server", "1.0.0")
                .toolCall(LoadDriverTool.tool(),    LoadDriverTool::handle)
                .toolCall(ConnectTool.tool(),       ConnectTool::handle)
                .toolCall(ExecuteQueryTool.tool(),  ExecuteQueryTool::handle)
                .toolCall(ExecuteUpdateTool.tool(), ExecuteUpdateTool::handle)
                .toolCall(GetMetadataTool.tool(),     GetMetadataTool::handle)
                .toolCall(ExecutePreparedTool.tool(), ExecutePreparedTool::handle)
                .toolCall(ExecuteJavaTool.tool(),   ExecuteJavaTool::handle)
                .toolCall(GetUsageStatsTool.tool(), GetUsageStatsTool::handle)
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
