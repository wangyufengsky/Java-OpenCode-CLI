package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.runner.WorkflowRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRunnerTest {
    @Test
    void dispatchesSelectedWorkflowChainWithRunnerRequest() throws Exception {
        OpenCodeRunnerProperties properties = new OpenCodeRunnerProperties();
        properties.setEnabled(true);
        properties.setActiveChain("demo-chain");
        properties.setMode("rerun");
        properties.getRerun().setType("index");
        AtomicReference<WorkflowRunRequest> requestRef = new AtomicReference<>();
        WorkflowChain chain = new WorkflowChain() {
            @Override
            public String id() {
                return "demo-chain";
            }

            @Override
            public void run(WorkflowRunRequest request) {
                requestRef.set(request);
            }
        };

        new WorkflowRunner(properties, List.of(chain)).run(new DefaultApplicationArguments());

        assertThat(requestRef.get()).isNotNull();
        assertThat(requestRef.get().mode()).isEqualTo("rerun");
        assertThat(requestRef.get().rerunType()).isEqualTo("index");
    }

    @Test
    void failsClearlyWhenActiveChainIsUnknown() {
        OpenCodeRunnerProperties properties = new OpenCodeRunnerProperties();
        properties.setEnabled(true);
        properties.setActiveChain("missing-chain");

        assertThatThrownBy(() -> new WorkflowRunner(properties, List.of()).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown opencode-runner.active-chain")
                .hasMessageContaining("missing-chain");
    }
}
