package com.cdata.mcp.jdbc.proxy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reflective invocation for the JDBC proxies.
 * <p>
 * {@link Method#invoke} wraps whatever the target threw in an {@link InvocationTargetException},
 * which carries no message of its own. Rethrowing that wrapper loses the driver's SQLException:
 * callers report a bare "null" instead of the real error, and because InvocationTargetException
 * is not declared on the JDBC interfaces the JDK re-wraps it again in an
 * UndeclaredThrowableException — so {@code catch (SQLException e)} in client code never matches.
 * These helpers always surface the exception the driver actually threw.
 */
final class ProxyInvoke {

    private ProxyInvoke() {}

    /** Invokes the method, rethrowing what the target threw rather than the reflection wrapper. */
    static Object call(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ite) {
            throw unwrap(ite);
        }
    }

    /** The exception the target actually threw, falling back to the wrapper itself. */
    static Throwable unwrap(InvocationTargetException ite) {
        Throwable target = ite.getTargetException();
        return target != null ? target : ite;
    }

    /** Non-null description of a throwable for the intercepted-call trace. */
    static String describe(Throwable t) {
        String msg = t.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : t.getClass().getSimpleName();
    }
}
