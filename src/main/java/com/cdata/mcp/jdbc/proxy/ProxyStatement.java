package com.cdata.mcp.jdbc.proxy;

import com.cdata.mcp.jdbc.ConnectionSession;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Statement;

public class ProxyStatement implements InvocationHandler {

    private final Statement real;
    private final ConnectionSession session;
    private final String preparedSql;

    public ProxyStatement(Statement real, ConnectionSession session, String preparedSql) {
        this.real = real;
        this.session = session;
        this.preparedSql = preparedSql;
    }

    public static Statement wrap(Statement real, ConnectionSession session, String preparedSql) {
        return (Statement) Proxy.newProxyInstance(
                real.getClass().getClassLoader(),
                new Class[]{Statement.class},
                new ProxyStatement(real, session, preparedSql));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();

        if (name.equals("executeQuery") || name.equals("executeUpdate") || name.equals("execute")) {
            String sql = (preparedSql != null) ? preparedSql
                    : (args != null && args.length > 0 ? (String) args[0] : null);
            long start = System.currentTimeMillis();
            String error = null;
            Object result = null;
            try {
                result = ProxyInvoke.call(method, real, args);
            } catch (Throwable t) {
                error = ProxyInvoke.describe(t);
                throw t;
            } finally {
                long dur = System.currentTimeMillis() - start;
                int rows = -1;
                if (result instanceof ResultSet rs) {
                    // row count not available yet; recorded after full iteration
                } else if (result instanceof Integer i) {
                    rows = i;
                }
                session.addCall(new InterceptedCall(name, sql, null, dur, rows, error));
            }
            return wrapResultSet(result);
        }

        // getResultSet() feeds the EXEC path, which reaches rows without executeQuery().
        return wrapResultSet(ProxyInvoke.call(method, real, args));
    }

    /** Route every ResultSet leaving this statement through the budget-enforcing proxy. */
    private Object wrapResultSet(Object result) {
        return (result instanceof ResultSet rs) ? ProxyResultSet.wrap(rs, session) : result;
    }
}
