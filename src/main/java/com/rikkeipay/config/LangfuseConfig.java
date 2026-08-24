package com.rikkeipay.config;

import io.langfuse.client.LangfuseClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseConfig {

    @Bean
    public LangfuseClient langfuseClient(LangfuseProperties props) {
        return LangfuseClient.builder()
                .publicKey(props.getPublicKey())
                .secretKey(props.getSecretKey())
                .baseUrl(props.getBaseUrl())
                .build();
    }
}
