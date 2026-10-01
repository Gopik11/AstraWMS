package com.astrawms.common.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** Writes RFC 9457 problem responses from servlet filters, where {@link ProblemHandler} does not apply. */
public final class Problems {

    private Problems() {
    }

    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"https://astrawms.com/problems/%s","title":"%s","status":%d,"detail":"%s","code":"%s"}"""
                .formatted(code.toLowerCase().replace('_', '-'), status.getReasonPhrase(), status.value(),
                        detail.replace("\\", "\\\\").replace("\"", "\\\""), code));
    }
}
