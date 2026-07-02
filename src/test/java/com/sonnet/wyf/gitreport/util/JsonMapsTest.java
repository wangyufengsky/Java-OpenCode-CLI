package com.sonnet.wyf.gitreport.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonMapsTest {
    @Test
    void readsNestedMapsListsStringsAndNumbersSafely() {
        Map<String, Object> row = Map.of(
                "name", "Alice",
                "count", 7,
                "nested", Map.of("ok", true),
                "items", List.of(Map.of("id", "a"), Map.of("id", "b")),
                "plainList", List.of("x")
        );

        assertThat(JsonMaps.string(row.get("name"))).isEqualTo("Alice");
        assertThat(JsonMaps.string(null)).isEmpty();
        assertThat(JsonMaps.number(row.get("count"))).isEqualTo(7);
        assertThat(JsonMaps.number("bad")).isZero();
        assertThat(JsonMaps.mapValue(row.get("nested"))).containsEntry("ok", true);
        assertThat(JsonMaps.mapValue("bad")).isEmpty();
        assertThat(JsonMaps.listOfMaps(row.get("items"))).hasSize(2);
        assertThat(JsonMaps.listOfMaps(row.get("plainList"))).isEmpty();
        assertThat(JsonMaps.listValue(row.get("plainList")).stream().map(Object::toString).toList()).containsExactly("x");
        assertThat(JsonMaps.listValue(null)).isEmpty();
    }
}
