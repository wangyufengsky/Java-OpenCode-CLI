package com.sonnet.wyf.gitreport;

class AuthorValidationResult {
    private final boolean ok;
    private final String error;

    private AuthorValidationResult(boolean ok, String error) {
        this.ok = ok;
        this.error = error;
    }

    static AuthorValidationResult success() {
        return new AuthorValidationResult(true, "");
    }

    static AuthorValidationResult failed(String error) {
        return new AuthorValidationResult(false, error);
    }

    boolean ok() {
        return ok;
    }

    String error() {
        return error;
    }
}
