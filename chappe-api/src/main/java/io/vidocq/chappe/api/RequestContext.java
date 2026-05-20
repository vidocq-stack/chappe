package io.vidocq.chappe.api;

/**
 * ScopedValue-based request context, bound by the server before handler dispatch.
 */
public final class RequestContext {
    public static final ScopedValue<RequestContext> CURRENT = ScopedValue.newInstance();

    private final Request request;

    public RequestContext(Request request) {
        this.request = request;
    }

    public Request request() {
        return request;
    }

    public static RequestContext current() {
        return CURRENT.get();
    }

    public static Request currentRequest() {
        return current().request();
    }
}
