package com.sonnet.wyf.gitreport.orchestration;

import java.nio.file.Path;

class AuthorTaskResult {
    private final String authorKey;
    private final String author;
    private final Path statusPath;
    private final boolean success;
    private final String error;

    private AuthorTaskResult(String authorKey, String author, Path statusPath, boolean success, String error) {
        this.authorKey = authorKey;
        this.author = author;
        this.statusPath = statusPath;
        this.success = success;
        this.error = error == null ? "" : error;
    }

    static AuthorTaskResult success(String authorKey, String author, Path statusPath) {
        return new AuthorTaskResult(authorKey, author, statusPath, true, "");
    }

    static AuthorTaskResult failed(String authorKey, String author, Path statusPath, String error) {
        return new AuthorTaskResult(authorKey, author, statusPath, false, error);
    }

    String authorKey() {
        return authorKey;
    }

    String author() {
        return author;
    }

    Path statusPath() {
        return statusPath;
    }

    boolean success() {
        return success;
    }

    String error() {
        return error;
    }
}
