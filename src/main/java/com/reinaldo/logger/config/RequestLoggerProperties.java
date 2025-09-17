package com.reinaldo.logger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "request-logger")
public class RequestLoggerProperties {

    private boolean enabled = true;
    private boolean logHeaders = true;
    private boolean logBody = false;
    private boolean logResponseBody = false;
    private int maxBodySize = 1000; // Máximo de caracteres para o body
    private List<String> excludedPaths = List.of("/actuator/**", "/health", "/metrics");
    private List<String> excludedHeaders = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLogHeaders() {
        return logHeaders;
    }

    public void setLogHeaders(boolean logHeaders) {
        this.logHeaders = logHeaders;
    }

    public boolean isLogBody() {
        return logBody;
    }

    public void setLogBody(boolean logBody) {
        this.logBody = logBody;
    }

    public boolean isLogResponseBody() {
        return logResponseBody;
    }

    public void setLogResponseBody(boolean logResponseBody) {
        this.logResponseBody = logResponseBody;
    }

    public int getMaxBodySize() {
        return maxBodySize;
    }

    public void setMaxBodySize(int maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }

    public List<String> getExcludedHeaders() {
        return excludedHeaders;
    }

    public void setExcludedHeaders(List<String> excludedHeaders) {
        this.excludedHeaders = excludedHeaders;
    }
}
