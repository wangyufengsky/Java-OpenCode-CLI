package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import java.util.List;

public record MyBatisSqlInventory(
        List<MyBatisMapperInventory> mappers,
        List<MyBatisSqlStatement> statements) {

    public MyBatisSqlInventory {
        mappers = List.copyOf(mappers);
        statements = List.copyOf(statements);
    }
}
