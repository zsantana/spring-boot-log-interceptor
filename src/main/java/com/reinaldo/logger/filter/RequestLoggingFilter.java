package com.reinaldo.logger.filter;

import com.reinaldo.logger.config.RequestLoggerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.*;
import java.util.regex.Pattern;

public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final RequestLoggerProperties properties;
    
    // Headers sensíveis que não devem ser logados
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-auth-token", 
            "x-api-key", "proxy-authorization", "www-authenticate"
    );
    
    // Padrão para URLs que devem ser excluídas do log
    private static final Pattern EXCLUDED_PATHS = Pattern.compile(
            ".*/actuator/.*|.*/health|.*/metrics|.*\\.css|.*\\.js|.*\\.ico|.*\\.png|.*\\.jpg|.*\\.gif"
    );

    public RequestLoggingFilter(RequestLoggerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        
        if (!shouldLog(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String correlationId = generateCorrelationId();
        MDC.put("correlationId", correlationId);
        
        long startTime = System.currentTimeMillis();
        
        try {
            ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
            ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

            logRequestStart(wrappedRequest, correlationId);

            filterChain.doFilter(wrappedRequest, wrappedResponse);

            long duration = System.currentTimeMillis() - startTime;
            logRequestEnd(wrappedRequest, wrappedResponse, correlationId, duration);
            
            // Importante: copiar o conteúdo de volta para a response original
            wrappedResponse.copyBodyToResponse();
            
        } catch (Exception e) {
            logger.error("Erro durante o processamento da requisição [{}]: {}", correlationId, e.getMessage());
            throw e;
        } finally {
            MDC.clear();
        }
    }

    private boolean shouldLog(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return false;
        }
        
        String path = request.getRequestURI();
        return !EXCLUDED_PATHS.matcher(path).matches();
    }

    private String generateCorrelationId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void logRequestStart(ContentCachingRequestWrapper request, String correlationId) {
        try {
            if (logger.isInfoEnabled()) {
                logger.info("=== REQUEST START [{}] ===", correlationId);
                logger.info("Method: {} | URI: {} | Remote: {}", 
                    request.getMethod(), 
                    request.getRequestURI(),
                    getClientIpAddress(request));
                
                if (request.getQueryString() != null) {
                    logger.info("Query: {}", request.getQueryString());
                }

                logHeaders(request);
                logRequestBody(request);
            }
        } catch (Exception e) {
            logger.warn("Erro ao logar início da requisição [{}]: {}", correlationId, e.getMessage());
        }
    }

    private void logRequestEnd(ContentCachingRequestWrapper request, 
                              ContentCachingResponseWrapper response, 
                              String correlationId, 
                              long duration) {
        try {
            if (logger.isInfoEnabled()) {
                logger.info("=== REQUEST END [{}] ===", correlationId);
                logger.info("Status: {} | Duration: {}ms | Content-Type: {}", 
                    response.getStatus(),
                    duration,
                    response.getContentType());

                logResponseBody(response);
            }
        } catch (Exception e) {
            logger.warn("Erro ao logar fim da requisição [{}]: {}", correlationId, e.getMessage());
        }
    }

    private void logHeaders(ContentCachingRequestWrapper request) {
        if (!properties.isLogHeaders()) {
            return;
        }

        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                if (!isSensitiveHeader(name)) {
                    String value = request.getHeader(name);
                    logger.info("Header: {} = {}", name, value);
                }
            }
        }
    }

    private void logRequestBody(ContentCachingRequestWrapper request) {
        if (!properties.isLogBody()) {
            return;
        }

        try {
            byte[] content = request.getContentAsByteArray();
            if (content.length > 0) {
                String body = getContentAsString(content, request.getCharacterEncoding());
                if (body.length() > properties.getMaxBodySize()) {
                    body = body.substring(0, properties.getMaxBodySize()) + "... [TRUNCATED]";
                }
                logger.info("Request Body: {}", body);
            }
        } catch (Exception e) {
            logger.warn("Erro ao logar body da requisição: {}", e.getMessage());
        }
    }

    private void logResponseBody(ContentCachingResponseWrapper response) {
        if (!properties.isLogResponseBody()) {
            return;
        }

        try {
            byte[] content = response.getContentAsByteArray();
            if (content.length > 0) {
                String body = getContentAsString(content, response.getCharacterEncoding());
                if (body.length() > properties.getMaxBodySize()) {
                    body = body.substring(0, properties.getMaxBodySize()) + "... [TRUNCATED]";
                }
                logger.info("Response Body: {}", body);
            }
        } catch (Exception e) {
            logger.warn("Erro ao logar body da resposta: {}", e.getMessage());
        }
    }

    private String getContentAsString(byte[] content, String encoding) {
        try {
            String charsetName = (encoding != null) ? encoding : StandardCharsets.UTF_8.name();
            return new String(content, charsetName);
        } catch (Exception e) {
            logger.warn("Erro ao converter conteúdo para string: {}", e.getMessage());
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    private boolean isSensitiveHeader(String headerName) {
        return SENSITIVE_HEADERS.contains(headerName.toLowerCase());
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
}
