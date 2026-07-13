package com.sonnet.wyf.gitreport.console;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class WorkflowRerunContract {
    record RerunTypeOption(String value, String label, boolean requiresId, String idPlaceholder) {
    }

    private static final Map<String, List<RerunTypeOption>> TYPE_OPTIONS = Map.of(
            "git-code-contribution-report", List.of(
                    new RerunTypeOption("author", "作者", true, "author_key，多个用英文逗号分隔"),
                    new RerunTypeOption("synthesis", "总报告", false, "总报告重跑不需要编号")
            ),
            "smartesb-rewrite-code-review", List.of(
                    new RerunTypeOption("transaction", "交易", true, "交易名，多个用英文逗号分隔"),
                    new RerunTypeOption("module", "模块", true, "模块名，多个用英文逗号分隔"),
                    new RerunTypeOption("index", "总报告", false, "总报告重跑不需要编号")
            ),
            "smartesb-code-reader", List.of(
                    new RerunTypeOption("transaction", "交易", true, "交易名，多个用英文逗号分隔"),
                    new RerunTypeOption("module", "模块", true, "模块名，多个用英文逗号分隔"),
                    new RerunTypeOption("index", "总报告", false, "总报告重跑不需要编号")
            ),
            "weekly-engineering-report", List.of(
                    new RerunTypeOption("review-batch", "审查批次", true, "review batch id，多个用英文逗号分隔"),
                    new RerunTypeOption("synthesis", "总报告", false, "总报告重跑不需要编号")
            ),
            "project-unit-test-generation", List.of(
                    new RerunTypeOption("test-batch", "测试批次", true, "test batch id，多个用英文逗号分隔"),
                    new RerunTypeOption("verification", "验证", false, "验证重跑不需要编号")
            )
    );

    private static final Map<String, Map<String, String>> TYPE_ALIASES = Map.of(
            "git-code-contribution-report", Map.of(
                    "作者", "author",
                    "人员", "author",
                    "author", "author",
                    "总报告", "synthesis",
                    "汇总", "synthesis",
                    "synthesis", "synthesis"
            ),
            "smartesb-rewrite-code-review", Map.of(
                    "交易", "transaction",
                    "transaction", "transaction",
                    "模块", "module",
                    "module", "module",
                    "总报告", "index",
                    "索引", "index",
                    "index", "index"
            ),
            "smartesb-code-reader", Map.of(
                    "交易", "transaction",
                    "transaction", "transaction",
                    "模块", "module",
                    "module", "module",
                    "总报告", "index",
                    "索引", "index",
                    "index", "index"
            ),
            "weekly-engineering-report", Map.of(
                    "批次", "review-batch",
                    "review-batch", "review-batch",
                    "总报告", "synthesis",
                    "汇总", "synthesis",
                    "synthesis", "synthesis"
            ),
            "project-unit-test-generation", Map.of(
                    "批次", "test-batch",
                    "测试批次", "test-batch",
                    "test-batch", "test-batch",
                    "验证", "verification",
                    "verification", "verification"
            )
    );

    private static final Map<String, Set<String>> TYPES_REQUIRING_ID = Map.of(
            "git-code-contribution-report", Set.of("author"),
            "smartesb-rewrite-code-review", Set.of("transaction", "module"),
            "smartesb-code-reader", Set.of("transaction", "module"),
            "weekly-engineering-report", Set.of("review-batch"),
            "project-unit-test-generation", Set.of("test-batch")
    );

    private WorkflowRerunContract() {
    }

    static String normalizeType(String chainId, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return TYPE_ALIASES.getOrDefault(chainId, Map.of()).getOrDefault(normalized, normalized);
    }

    static boolean isKnownType(String chainId, String rerunType) {
        Set<String> knownTypes = TYPE_ALIASES.getOrDefault(chainId, Map.of()).values().stream().collect(java.util.stream.Collectors.toSet());
        return knownTypes.isEmpty() || knownTypes.contains(rerunType);
    }

    static boolean requiresRerunId(String chainId, String rerunType) {
        return TYPES_REQUIRING_ID.getOrDefault(chainId, Set.of()).contains(rerunType);
    }

    static Optional<String> failedTaskRerunType(String chainId, String taskPhase) {
        String normalizedType = normalizeType(chainId, taskPhase);
        if (isKnownType(chainId, normalizedType)) {
            return requiresRerunId(chainId, normalizedType)
                    ? Optional.of(normalizedType)
                    : Optional.empty();
        }
        List<String> taskTypes = typeOptions(chainId).stream()
                .map(RerunTypeOption::value)
                .filter(type -> requiresRerunId(chainId, type))
                .toList();
        return taskTypes.size() == 1 ? Optional.of(taskTypes.getFirst()) : Optional.empty();
    }

    static List<RerunTypeOption> typeOptions(String chainId) {
        return TYPE_OPTIONS.getOrDefault(chainId, List.of());
    }
}
