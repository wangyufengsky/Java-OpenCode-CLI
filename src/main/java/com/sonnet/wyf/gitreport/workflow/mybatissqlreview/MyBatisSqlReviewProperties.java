package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import java.util.List;

public final class MyBatisSqlReviewProperties {
    private List<String> includes = List.of("**/*.xml");
    private List<String> excludes = List.of();

    public List<String> getIncludes() {
        return includes;
    }

    public void setIncludes(List<String> includes) {
        this.includes = includes == null || includes.isEmpty()
                ? List.of("**/*.xml")
                : List.copyOf(includes);
    }

    public List<String> getExcludes() {
        return excludes;
    }

    public void setExcludes(List<String> excludes) {
        this.excludes = excludes == null ? List.of() : List.copyOf(excludes);
    }
}
