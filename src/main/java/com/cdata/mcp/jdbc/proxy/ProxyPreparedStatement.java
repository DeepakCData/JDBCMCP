package com.cdata.mcp.jdbc.proxy;

import com.cdata.mcp.jdbc.ConnectionSession;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProxyPreparedStatement implements InvocationHandler {

    private final PreparedStatement real;
    private final ConnectionSession session;
    private final String sql;
    private final Map<Integer, Object> params = new LinkedHashMap<>();

    public ProxyPreparedStatement(PreparedStatement real, ConnectionSession session, String sql) {
        this.real = real;
        this.session = session;
        this.sql = sql;
    }

    public static PreparedStatement wrap(PreparedStatement real, ConnectionSession session, String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(
                real.getClass().getClassLoader(),
                new Class[]{PreparedStatement.class},
                new ProxyPreparedStatement(real, session, sql));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();

        // Capture bound parameters: setXxx(int paramIndex, value)
        if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer idx) {
            params.put(idx, args[1]);
        }

        if (name.equals("executeQuery") || name.equals("executeUpdate") || name.equals("execute")) {
            long start = System.currentTimeMillis();
            String error = null;
            Object result = null;
            try {
                result = method.invoke(real, args);
            } catch (Exception ex) {
                error = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                throw ex;
            } finally {
                long dur = System.currentTimeMillis() - start;
                int rows = (result instanceof Integer i) ? i : -1;
                session.addCall(new InterceptedCall(name, sql, new LinkedHashMap<>(params), dur, rows, error));
            }
            return result;
        }

        return method.invoke(real, args);
    }
}
