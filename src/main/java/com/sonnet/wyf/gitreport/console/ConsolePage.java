package com.sonnet.wyf.gitreport.console;

import java.util.List;

public record ConsolePage<T>(
        int page,
        int pageSize,
        long total,
        int totalPages,
        List<T> items
) {
    public ConsolePage {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least one");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (total < 0 || totalPages < 1) {
            throw new IllegalArgumentException("total values must not be negative");
        }
        items = List.copyOf(items);
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }
}
