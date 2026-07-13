package com.sonnet.wyf.gitreport.console;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public class PathPreflightService {
    public Result inspect(String value) {
        if (value == null || value.isBlank()) {
            return new Result(false, false, false, "请输入要检查的路径");
        }
        try {
            Path path = Path.of(value.trim()).normalize();
            boolean exists = Files.exists(path);
            boolean directory = exists && Files.isDirectory(path);
            boolean accessible = exists && Files.isReadable(path);
            boolean mavenProject = directory && Files.exists(path.resolve("pom.xml"));
            String message = message(exists, directory, accessible, mavenProject);
            return new Result(accessible, directory, mavenProject, message);
        } catch (InvalidPathException exception) {
            return new Result(false, false, false, "路径格式无效");
        }
    }

    private static String message(boolean exists, boolean directory, boolean accessible, boolean mavenProject) {
        if (!exists) {
            return "路径不存在";
        }
        if (!directory) {
            return "路径不是目录";
        }
        if (!accessible) {
            return "目录不可读";
        }
        return mavenProject ? "路径可读，已找到 pom.xml" : "路径可读，未找到 pom.xml";
    }

    public record Result(boolean accessible, boolean directory, boolean mavenProject, String message) {
    }
}
