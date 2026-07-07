package com.sonnet.wyf.gitreport.opencode;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenCodeModelMapperTest {

    @Test
    void sessionModelObjectMapsProviderAndModelId() {
        Map<String, Object> model = OpenCodeModelMapper.sessionModelObject("anthropic/claude-sonnet-4");

        assertThat(model).containsExactly(
                Map.entry("providerID", "anthropic"),
                Map.entry("id", "claude-sonnet-4")
        );
    }

    @Test
    void promptModelObjectUsesPromptModelKeyAndTrimsInput() {
        Map<String, Object> model = OpenCodeModelMapper.promptModelObject("  openai/gpt-5  ");

        assertThat(model).containsExactly(
                Map.entry("providerID", "openai"),
                Map.entry("modelID", "gpt-5")
        );
    }

    @Test
    void rejectsModelWithoutProviderAndModelParts() {
        assertThatThrownBy(() -> OpenCodeModelMapper.sessionModelObject("anthropic"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider/model format")
                .hasMessageContaining("anthropic");

        assertThatThrownBy(() -> OpenCodeModelMapper.promptModelObject("anthropic/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider/model format")
                .hasMessageContaining("anthropic/");
    }
}
