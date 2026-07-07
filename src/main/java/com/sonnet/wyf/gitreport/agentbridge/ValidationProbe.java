package com.sonnet.wyf.gitreport.agentbridge;

@FunctionalInterface
public interface ValidationProbe {
    ValidationCheck validate() throws Exception;
}
