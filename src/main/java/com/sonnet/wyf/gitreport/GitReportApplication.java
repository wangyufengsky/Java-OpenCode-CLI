package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@EnableConfigurationProperties({GitReportProperties.class, OpenCodeRunnerProperties.class})
public class GitReportApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(GitReportApplication.class, args);
        System.exit(SpringApplication.exit(context));
    }
}
