package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.console.TaskConsoleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({GitReportProperties.class, AgentBridgeRunnerProperties.class, TaskConsoleProperties.class})
public class GitReportApplication {
    public static void main(String[] args) {
        SpringApplication.run(GitReportApplication.class, args);
    }
}
