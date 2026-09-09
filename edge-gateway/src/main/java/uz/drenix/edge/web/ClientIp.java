package uz.drenix.edge.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the caller's address.
 *
 * <p>{@code X-Forwarded-For} is trusted only because this service is expected to sit behind a
 * proxy we control, which overwrites the header. If that stops being true, rate limiting and audit
 * attribution become client-controlled — so the deployment contract matters as much as the code.
 */
final class ClientIp {

    private ClientIp() {
    }

    static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Left-most entry is the original client; the rest are proxies.
            String first = forwarded.split(",")[0].trim();
            if (first.length() <= 45) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
