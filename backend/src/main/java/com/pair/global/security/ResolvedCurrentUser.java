package com.pair.global.security;

import java.util.Optional;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;

final class ResolvedCurrentUser {

    private static final String ATTRIBUTE = ResolvedCurrentUser.class.getName() + ".identity";

    private ResolvedCurrentUser() {}

    @SuppressWarnings("unchecked")
    static Optional<CurrentUser> resolve(HttpServletRequest request, Supplier<Optional<CurrentUser>> supplier) {
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached != null) return (Optional<CurrentUser>) cached;
        Optional<CurrentUser> current = supplier.get();
        request.setAttribute(ATTRIBUTE, current);
        return current;
    }
}
