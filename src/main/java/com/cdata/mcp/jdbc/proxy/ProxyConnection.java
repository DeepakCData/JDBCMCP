package com.cdata.mcp.jdbc.proxy;

import com.cdata.mcp.jdbc.ConnectionSession;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

public class ProxyConnection implements InvocationHandler {

    private final Connection real;
    private final ConnectionSession session;

    public ProxyConnection(Connection real, ConnectionSession session) {
        this.real = real;
        this.session = session;
    }

    public static Connection wrap(Connection real, ConnectionSession session) {
        return (Connection) Proxy.newProxyInstance(
                real.getClass().getClassLoader(),
                new Class[]{Connection.class},
                new ProxyConnection(real, session));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "createStatement" -> {
                Statement st = (Statement) method.invoke(real, args);
                return ProxyStatement.wrap(st, session, null);
            }
            case "prepareStatement" -> {
                String sql = (args != null && args.length > 0) ? (String) args[0] : null;
                PreparedStatement ps = (PreparedStatement) method.invoke(real, args);
                return ProxyPreparedStatement.wrap(ps, session, sql);
            }
            default -> {
                return method.invoke(real, args);
            }
        }
    }
}
