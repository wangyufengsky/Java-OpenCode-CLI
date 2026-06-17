package com.sonnet.wyf.gitreport.opencode;

@FunctionalInterface
public interface ValidationProbe {
    ValidationCheck validate() throws Exception;
}
