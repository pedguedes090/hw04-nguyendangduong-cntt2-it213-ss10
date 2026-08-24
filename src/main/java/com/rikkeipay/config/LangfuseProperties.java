package com.rikkeipay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LangfuseProperties - đọc cấu hình Langfuse từ application.yml.
 */
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    private String publicKey;
    private String secretKey;
    private String baseUrl;

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
