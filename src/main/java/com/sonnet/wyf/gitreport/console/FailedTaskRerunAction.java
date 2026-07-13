package com.sonnet.wyf.gitreport.console;

public record FailedTaskRerunAction(
        boolean visible,
        boolean available,
        String rerunType,
        String rerunId,
        String reason
) {
    static FailedTaskRerunAction hidden() {
        return new FailedTaskRerunAction(false, false, "", "", "");
    }
}
