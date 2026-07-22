package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import java.util.List;
import java.util.Objects;

public record MyBatisSqlStatement(
        String mapperRelativePath,
        String namespace,
        String id,
        String commandType,
        boolean selectKey,
        int selectKeyOrdinal,
        int startLine,
        int endLine,
        String rawXml,
        String normalizedSql,
        List<String> dynamicNodeNames,
        List<String> parameterPlaceholders,
        List<String> resolvedFragmentIds,
        String sourceSha256,
        String mapperKey,
        String statementKey) {

    public MyBatisSqlStatement {
        mapperRelativePath = Objects.requireNonNull(mapperRelativePath, "mapperRelativePath");
        namespace = Objects.requireNonNull(namespace, "namespace");
        id = Objects.requireNonNull(id, "id");
        commandType = Objects.requireNonNull(commandType, "commandType");
        rawXml = Objects.requireNonNull(rawXml, "rawXml");
        normalizedSql = Objects.requireNonNull(normalizedSql, "normalizedSql");
        dynamicNodeNames = List.copyOf(dynamicNodeNames);
        parameterPlaceholders = List.copyOf(parameterPlaceholders);
        resolvedFragmentIds = List.copyOf(resolvedFragmentIds);
        sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        mapperKey = Objects.requireNonNull(mapperKey, "mapperKey");
        statementKey = Objects.requireNonNull(statementKey, "statementKey");
    }
}
