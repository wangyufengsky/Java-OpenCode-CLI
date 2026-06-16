package com.sonnet.wyf.gitreport;

@FunctionalInterface
interface CompletionProbe {
    boolean isComplete() throws Exception;
}
