package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
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
        AgentBridgeRunnerProperties properties = new AgentBridgeRunnerProperties();
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
    void requestParsesCommaSeparatedRerunIds() {
        WorkflowRunRequest request = new WorkflowRunRequest(
                "rerun",
                "transaction",
                "\"CaCheckAcct\", \"CaConsumeRev\", \"CaTransferOuter\"",
                null,
                new com.sonnet.wyf.gitreport.runner.AgentBridgeSettings()
        );

        assertThat(request.rerunIds()).containsExactly("CaCheckAcct", "CaConsumeRev", "CaTransferOuter");
    }

    @Test
    void failsClearlyWhenActiveChainIsUnknown() {
        AgentBridgeRunnerProperties properties = new AgentBridgeRunnerProperties();
        properties.setEnabled(true);
        properties.setActiveChain("missing-chain");

        assertThatThrownBy(() -> new WorkflowRunner(properties, List.of()).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown agentbridge-runner.active-chain")
                .hasMessageContaining("missing-chain");
    }
}
