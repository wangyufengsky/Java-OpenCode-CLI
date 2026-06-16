package com.sonnet.wyf.gitreport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GitReportProperties.class)
public class GitReportApplication {
    public static void main(String[] args) {
        SpringApplication.run(GitReportApplication.class, args);
    }
}
