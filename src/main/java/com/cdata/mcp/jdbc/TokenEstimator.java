package com.cdata.mcp.jdbc;

public class TokenEstimator {
    public static long estimate(String json) {
        return json == null ? 0 : json.length() / 4;
    }
}
