package com.cdata.mcp.compiler;

public class SnippetTemplate {

    public static String className(String uid) {
        return "AiSnippet_" + uid;
    }

    public static String wrap(String userCode, String uid) {
        return """
                package com.cdata.mcp.snippets;
                import java.sql.*;
                import java.math.*;
                import java.util.*;
                import java.io.*;
                public class AiSnippet_%s {
                    public static String run(Connection connection) throws Exception {
                        StringBuilder __out = new StringBuilder();
                        %s
                        return __out.toString();
                    }
                }
                """.formatted(uid, userCode);
    }
}
