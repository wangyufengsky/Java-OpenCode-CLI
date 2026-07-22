package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import java.util.List;
import java.util.Objects;

public record MyBatisMapperInventory(
        String mapperRelativePath,
        String namespace,
        String sourceSha256,
        String mapperKey,
        List<MyBatisSqlStatement> statements) {

    public MyBatisMapperInventory {
        mapperRelativePath = Objects.requireNonNull(mapperRelativePath, "mapperRelativePath");
        namespace = Objects.requireNonNull(namespace, "namespace");
        sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        mapperKey = Objects.requireNonNull(mapperKey, "mapperKey");
        statements = List.copyOf(statements);
    }
}
