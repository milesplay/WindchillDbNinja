package com.custom.dbcapture.diagnostics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Principal;

/**
 * Uses APIs verified in the installed Windchill 13.0.2 / Java 17 classes.
 * Reflection avoids an extra compile-time dependency on the server-only WtJmxServlet jar.
 * No database call, MethodContext creation, monitor configuration, or principal lookup is performed.
 */
final class WindchillRequestProbe implements SqlEvidenceCapture.RequestProbe {
   private final Class<?> httpRequestClass;
   private final Method servletRequest;
   private final Method requestBean;
   private final Method methodContext;
   private final Field contextBean;
   private final Method principal;
   private final Method requestUri;
   private final Method queryString;
   private final Method asyncStarted;
   private final Method requestId;
   private final Method requestThreadId;
   private final Method completed;
   private final Method status;
   private final Method throwable;
   private final Method contextId;
   private final Method contextRequestId;
   private final Method contextUser;
   private final Method contextExceptionClass;
   private final Method targetClass;
   private final Method targetMethod;

   WindchillRequestProbe() throws ReflectiveOperationException {
      ClassLoader loader = WindchillRequestProbe.class.getClassLoader();
      Class<?> servletState = Class.forName("wt.servlet.ServletState", false, loader);
      Class<?> servletRequestClass = Class.forName("jakarta.servlet.ServletRequest", false, loader);
      httpRequestClass = Class.forName("jakarta.servlet.http.HttpServletRequest", false, loader);
      Class<?> requestClass = Class.forName("wt.servlet.Request", false, loader);
      Class<?> methodContextClass = Class.forName("wt.method.MethodContext", false, loader);
      Class<?> contextMBean = Class.forName("wt.method.MethodContextMBean", false, loader);
      servletRequest = servletState.getMethod("getServletRequest");
      requestBean = requestClass.getMethod("getRequestMBean", servletRequestClass);
      methodContext = methodContextClass.getMethod("getContext", Thread.class);
      contextBean = methodContextClass.getField("mbean");
      principal = httpRequestClass.getMethod("getUserPrincipal");
      requestUri = httpRequestClass.getMethod("getRequestURI");
      queryString = httpRequestClass.getMethod("getQueryString");
      asyncStarted = servletRequestClass.getMethod("isAsyncStarted");
      requestId = requestClass.getMethod("getId");
      requestThreadId = requestClass.getMethod("getThreadId");
      completed = requestClass.getMethod("isCompleted");
      status = requestClass.getMethod("getStatusCode");
      throwable = requestClass.getMethod("getThrowableThrown");
      contextId = contextMBean.getMethod("getId");
      contextRequestId = contextMBean.getMethod("getServletRequestId");
      contextUser = contextMBean.getMethod("getUserName");
      contextExceptionClass = contextMBean.getMethod("getExceptionClass");
      targetClass = contextMBean.getMethod("getTargetClass");
      targetMethod = contextMBean.getMethod("getTargetMethod");
   }

   @Override
   public SqlEvidenceCapture.RequestInfo current() throws Exception {
      Thread thread = Thread.currentThread();
      if (!thread.getName().regionMatches(true, 0, "ajp-", 0, 4)) {
         return null;
      }
      Object request = servletRequest.invoke(null);
      if (!httpRequestClass.isInstance(request) || Boolean.TRUE.equals(asyncStarted.invoke(request))) {
         return null;
      }
      Principal authenticated = (Principal) principal.invoke(request);
      if (authenticated == null || authenticated.getName() == null || authenticated.getName().isBlank()) {
         return null;
      }
      Object monitor = requestBean.invoke(null, request);
      Object context = methodContext.invoke(null, thread);
      if (monitor == null || context == null || Boolean.TRUE.equals(completed.invoke(monitor))
            || ((Number) requestThreadId.invoke(monitor)).longValue() != thread.getId()) {
         return null;
      }
      Object mbean = contextBean.get(context);
      if (mbean == null) {
         return null;
      }
      String id = (String) requestId.invoke(monitor);
      if (id == null || !id.equals(contextRequestId.invoke(mbean))) {
         return null;
      }
      int currentStatus = ((Number) status.invoke(monitor)).intValue();
      if (currentStatus >= 400 || throwable.invoke(monitor) != null || hasContextException(mbean)) {
         return null;
      }
      return new SqlEvidenceCapture.RequestInfo(thread.getId(), thread.getName(), id,
            (String) contextId.invoke(mbean), authenticated.getName(), (String) contextUser.invoke(mbean),
            (String) requestUri.invoke(request), (String) queryString.invoke(request),
            (String) targetClass.invoke(mbean), (String) targetMethod.invoke(mbean), true,
            () -> outcome(monitor, mbean));
   }

   private boolean hasContextException(Object mbean) throws Exception {
      String exception = (String) contextExceptionClass.invoke(mbean);
      return exception != null && !exception.isBlank();
   }

   private SqlEvidenceCapture.Outcome outcome(Object monitor, Object mbean) throws Exception {
      if (!Boolean.TRUE.equals(completed.invoke(monitor))) {
         return SqlEvidenceCapture.Outcome.PENDING;
      }
      int code = ((Number) status.invoke(monitor)).intValue();
      if (throwable.invoke(monitor) != null || hasContextException(mbean)
            || code >= 300 || (code >= 0 && code < 200)) {
         return SqlEvidenceCapture.Outcome.REJECTED;
      }
      // The installed Request starts at -1 and may never receive an explicit setStatus(200).
      // -1 means no status recorded, NOT a fabricated HTTP 200 or proof of a DB commit.
      return code == -1 || (code >= 200 && code < 300)
            ? SqlEvidenceCapture.Outcome.SUCCEEDED : SqlEvidenceCapture.Outcome.UNAVAILABLE;
   }
}
