package com.sonnet.wyf.gitreport.validation;

public class AuthorValidationResult {
    private final boolean ok;
    private final String error;

    private AuthorValidationResult(boolean ok, String error) {
        this.ok = ok;
        this.error = error;
    }

    public static AuthorValidationResult success() {
        return new AuthorValidationResult(true, "");
    }

    public static AuthorValidationResult failed(String error) {
        return new AuthorValidationResult(false, error);
    }

    public boolean ok() {
        return ok;
    }

    public String error() {
        return error;
    }
}
