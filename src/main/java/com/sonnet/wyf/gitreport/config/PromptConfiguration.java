package com.sonnet.wyf.gitreport.config;

import com.sonnet.wyf.gitreport.prompt.PromptBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration
public class PromptConfiguration {
    @Bean
    PromptBuilder promptBuilder(ResourceLoader resourceLoader) {
        return new PromptBuilder(resourceLoader);
    }
}
