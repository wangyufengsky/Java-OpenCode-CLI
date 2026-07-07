package com.sonnet.wyf.gitreport.agentbridge;

public record ValidationCheck(boolean ok, String error) {
    public static ValidationCheck success() {
        return new ValidationCheck(true, "");
    }

    public static ValidationCheck failed(String error) {
        return new ValidationCheck(false, error == null ? "" : error);
    }
}
