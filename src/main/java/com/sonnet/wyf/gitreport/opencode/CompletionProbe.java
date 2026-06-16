package com.sonnet.wyf.gitreport.opencode;

@FunctionalInterface
public interface CompletionProbe {
    boolean isComplete() throws Exception;
}
