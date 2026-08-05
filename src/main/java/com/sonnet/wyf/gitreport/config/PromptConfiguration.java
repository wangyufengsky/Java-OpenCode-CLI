package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.prompt.PromptBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration
public class PromptConfiguration {
    @Bean
    PromptBuilder promptBuilder(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        return new PromptBuilder(resourceLoader, objectMapper);
    }
}
