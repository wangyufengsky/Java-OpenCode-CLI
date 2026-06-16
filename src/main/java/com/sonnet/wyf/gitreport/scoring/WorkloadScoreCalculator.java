package com.sonnet.wyf.gitreport.scoring;

import java.util.Map;

public class WorkloadScoreCalculator {
    public double calculate(Map<String, Object> author) {
        double score = number(author, "commit_count") * 3.0
                + number(author, "file_change_count") * 1.5
                + number(author, "non_comment_added") * 1.2
                + number(author, "non_comment_deleted")
                + Math.abs(number(author, "non_comment_net")) * 0.2;
        return round2(score);
    }

    public double adjusted(double baseScore, double qualityAdjustmentPercent) {
        double bounded = Math.clamp(qualityAdjustmentPercent, -30.0, 30.0);
        return round2(baseScore * (1 + bounded / 100.0));
    }

    private double number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
