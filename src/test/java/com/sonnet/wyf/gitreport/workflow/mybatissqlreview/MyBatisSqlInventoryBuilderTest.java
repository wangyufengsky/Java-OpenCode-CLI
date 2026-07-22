package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MyBatisSqlInventoryBuilderTest {
    @TempDir
    Path repository;

    private final MyBatisSqlInventoryBuilder builder = new MyBatisSqlInventoryBuilder();

    @Test
    void doubleStarSlashMatchesRootAndNestedMapperXml() throws Exception {
        write("RootMapper.xml", """
                <mapper namespace="demo.RootMapper">
                  <select id="root">SELECT 1</select>
                </mapper>
                """);
        write("nested/NestedMapper.xml", """
                <mapper namespace="demo.NestedMapper">
                  <select id="nested">SELECT 2</select>
                </mapper>
                """);

        MyBatisSqlInventory inventory = builder.build(repository, List.of("**/*Mapper.xml"), List.of());

        assertThat(inventory.mappers()).extracting(MyBatisMapperInventory::mapperRelativePath)
                .containsExactly("RootMapper.xml", "nested/NestedMapper.xml");
    }

    @Test
    void rejectsRepositoriesWithNoMatchingMapperXml() {
        assertThatThrownBy(() -> builder.build(repository, List.of("**/*Mapper.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no MyBatis mapper XML files matched");
    }

    @Test
    void allowsDiscoveredMappersWithNoMappedStatements() throws Exception {
        write("mapper/OnlyFragmentsMapper.xml", """
                <mapper namespace="demo.OnlyFragmentsMapper">
                  <sql id="columns">id, name</sql>
                </mapper>
                """);

        MyBatisSqlInventory inventory = builder.build(repository, List.of("**/*Mapper.xml"), List.of());

        assertThat(inventory.mappers()).singleElement()
                .extracting(MyBatisMapperInventory::mapperRelativePath)
                .isEqualTo("mapper/OnlyFragmentsMapper.xml");
        assertThat(inventory.statements()).isEmpty();
    }

    @Test
    void inventoriesOnlyTopLevelStatementsInStablePathNamespaceIdOrder() throws Exception {
        write("zeta/ZMapper.xml", """
                <mapper namespace="com.example.ZMapper">
                  <delete id="remove">DELETE FROM users WHERE id = #{id}</delete>
                  <sql id="notATask">ignored_fragment</sql>
                  <select id="find">SELECT 1; SELECT 2</select>
                  <insert id="create">INSERT INTO users(id) VALUES (#{id})</insert>
                  <update id="change">UPDATE users SET name = #{name}</update>
                </mapper>
                """);
        Path firstMapper = write("alpha/AMapper.xml", """
                <mapper namespace="com.example.AMapper">
                  <select id="zLast">SELECT * FROM alpha</select>
                </mapper>
                """);

        MyBatisSqlInventory inventory = builder.build(repository, List.of("**/*.xml"), List.of());

        assertThat(inventory.mappers())
                .extracting(MyBatisMapperInventory::mapperRelativePath)
                .containsExactly("alpha/AMapper.xml", "zeta/ZMapper.xml");
        assertThat(inventory.statements())
                .extracting(statement -> statement.mapperRelativePath() + ":" + statement.id())
                .containsExactly(
                        "alpha/AMapper.xml:zLast",
                        "zeta/ZMapper.xml:change",
                        "zeta/ZMapper.xml:create",
                        "zeta/ZMapper.xml:find",
                        "zeta/ZMapper.xml:remove");
        assertThat(inventory.statements())
                .extracting(MyBatisSqlStatement::commandType)
                .containsExactly("SELECT", "UPDATE", "INSERT", "SELECT", "DELETE");

        MyBatisSqlStatement statement = inventory.statements().getFirst();
        assertThat(statement.namespace()).isEqualTo("com.example.AMapper");
        assertThat(statement.selectKey()).isFalse();
        assertThat(statement.selectKeyOrdinal()).isZero();
        assertThat(statement.startLine()).isEqualTo(2);
        assertThat(statement.endLine()).isEqualTo(2);
        assertThat(statement.rawXml()).contains("<select id=\"zLast\">", "</select>");
        assertThat(statement.normalizedSql()).isEqualTo("SELECT * FROM alpha");
        assertThat(statement.dynamicNodeNames()).isEmpty();
        assertThat(statement.parameterPlaceholders()).isEmpty();
        assertThat(statement.resolvedFragmentIds()).isEmpty();
        assertThat(statement.sourceSha256()).isEqualTo(sha256(Files.readAllBytes(firstMapper)));
        assertThat(statement.mapperKey()).matches("a-mapper-com-example-a-mapper-[0-9a-f]{12}");
        assertThat(statement.statementKey()).matches("com-example-a-mapper-z-last-[0-9a-f]{12}");
        assertThat(inventory.mappers().getFirst().sourceSha256()).isEqualTo(statement.sourceSha256());
        assertThat(inventory.mappers().getFirst().mapperKey()).isEqualTo(statement.mapperKey());

        MyBatisSqlStatement semicolonStatement = inventory.statements().stream()
                .filter(candidate -> candidate.id().equals("find"))
                .findFirst()
                .orElseThrow();
        assertThat(semicolonStatement.normalizedSql()).isEqualTo("SELECT 1; SELECT 2");
        assertThat(builder.build(repository, List.of("**/*.xml"), List.of())).isEqualTo(inventory);
        assertThatThrownBy(() -> inventory.mappers().add(inventory.mappers().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> inventory.statements().add(statement))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> inventory.mappers().getFirst().statements().add(statement))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesCdataAndDiscoversDynamicNodesAndPlaceholders() throws Exception {
        write("mapper/DynamicMapper.xml", """
                <mapper namespace="demo.DynamicMapper">
                  <select id="search">
                    SELECT * FROM users
                    <where>
                      <if test="name != null">name = #{name}</if>
                      <if test="minScore != null">AND score <![CDATA[>=]]> #{minScore}</if>
                    </where>
                    <if test="orderBy != null">ORDER BY ${orderBy}</if>
                  </select>
                </mapper>
                """);

        MyBatisSqlStatement statement = builder.build(repository, List.of("**/*.xml"), List.of())
                .statements().getFirst();

        assertThat(statement.normalizedSql())
                .contains("SELECT * FROM users")
                .contains("name = #{name}")
                .contains("AND score >= #{minScore}")
                .contains("ORDER BY ${orderBy}")
                .contains("\n");
        assertThat(statement.dynamicNodeNames()).containsExactly("where", "if", "if", "if");
        assertThat(statement.parameterPlaceholders()).containsExactly("#{name}", "#{minScore}", "${orderBy}");
        assertThat(statement.rawXml()).contains("<![CDATA[>=]]>");
        assertThat(statement.startLine()).isEqualTo(2);
        assertThat(statement.endLine()).isEqualTo(9);
    }

    @Test
    void derivesLinesFromRawOffsetsForMultilineAttributesAndCrLf() throws Exception {
        write("mapper/LinesMapper.xml", String.join("\r\n",
                "<mapper namespace=\"demo.LinesMapper\">",
                "  <select",
                "      id=\"multiline\"",
                "      parameterType=\"map\">",
                "    SELECT 1",
                "  </select>",
                "</mapper>",
                ""));

        MyBatisSqlStatement statement = builder.build(repository, List.of("**/*.xml"), List.of())
                .statements().getFirst();

        assertThat(statement.startLine()).isEqualTo(2);
        assertThat(statement.endLine()).isEqualTo(6);
        assertThat(statement.rawXml())
                .startsWith("<select\r\n")
                .endsWith("</select>");
    }

    @Test
    void preservesSemanticWhitespaceInsideSqlTokensAndComments() throws Exception {
        write("mapper/WhitespaceMapper.xml", """
                <mapper namespace="demo.WhitespaceMapper">
                  <select id="preserve">
                    SELECT 'left  right', "quoted  identifier", q'[vendor  value]'
                    -- keep   line-comment spacing
                    FROM users /* keep   block-comment spacing */
                  </select>
                </mapper>
                """);

        String sql = builder.build(repository, List.of("**/*.xml"), List.of())
                .statements().getFirst().normalizedSql();

        assertThat(sql)
                .contains("'left  right'")
                .contains("\"quoted  identifier\"")
                .contains("q'[vendor  value]'")
                .contains("-- keep   line-comment spacing")
                .contains("/* keep   block-comment spacing */")
                .contains("\n");
    }

    @Test
    void parsesTheStandardMyBatisDoctypeWithoutNetworkAccess() throws Exception {
        write("mapper/DoctypeMapper.xml", """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper
                  PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                  "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="demo.DoctypeMapper">
                  <select id="ping">SELECT 1</select>
                </mapper>
                """);

        MyBatisSqlInventory inventory = builder.build(repository, List.of("**/*.xml"), List.of());

        assertThat(inventory.statements()).singleElement()
                .extracting(MyBatisSqlStatement::normalizedSql)
                .isEqualTo("SELECT 1");
    }

    @Test
    void rejectsMalformedUtf8Bytes() throws Exception {
        byte[] prefix = """
                <mapper namespace="demo.InvalidUtf8Mapper">
                  <select id="broken">SELECT '
                """.getBytes(StandardCharsets.UTF_8);
        byte[] suffix = """
                '</select>
                </mapper>
                """.getBytes(StandardCharsets.UTF_8);
        byte[] invalid = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, invalid, 0, prefix.length);
        invalid[prefix.length] = (byte) 0xc3;
        invalid[prefix.length + 1] = 0x28;
        System.arraycopy(suffix, 0, invalid, prefix.length + 2, suffix.length);
        writeBytes("mapper/InvalidUtf8Mapper.xml", invalid);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid UTF-8")
                .hasMessageContaining("mapper/InvalidUtf8Mapper.xml");
    }

    @Test
    void rejectsXmlDeclarationsWithNonUtf8Encoding() throws Exception {
        write("mapper/LatinMapper.xml", """
                <?xml version="1.0" encoding="ISO-8859-1"?>
                <mapper namespace="demo.LatinMapper">
                  <select id="find">SELECT 1</select>
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires UTF-8")
                .hasMessageContaining("mapper/LatinMapper.xml");
    }

    @Test
    void recursivelyExpandsIncludesAndTracksResolvedFragmentIds() throws Exception {
        write("mapper/IncludeMapper.xml", """
                <mapper namespace="demo.IncludeMapper">
                  <sql id="tableName">users</sql>
                  <sql id="baseColumns">id, name FROM <include refid="tableName"/></sql>
                  <select id="findById">
                    SELECT <include refid="baseColumns"/> WHERE id = #{id}
                  </select>
                </mapper>
                """);

        MyBatisSqlInventory inventory = builder.build(repository, List.of("**/*.xml"), List.of());

        assertThat(inventory.statements()).hasSize(1);
        MyBatisSqlStatement statement = inventory.statements().getFirst();
        assertThat(statement.normalizedSql()).isEqualTo("SELECT id, name FROM users WHERE id = #{id}");
        assertThat(statement.resolvedFragmentIds())
                .containsExactly("demo.IncludeMapper.baseColumns", "demo.IncludeMapper.tableName");
        assertThat(statement.parameterPlaceholders()).containsExactly("#{id}");
    }

    @Test
    void scopesLocalIncludePropertiesAndExcludesThemFromRuntimeParameters() throws Exception {
        write("mapper/PropertyMapper.xml", """
                <mapper namespace="demo.PropertyMapper">
                  <sql id="columns">${alias}.id, ${alias}.name FROM users ${alias}</sql>
                  <select id="find">SELECT <include refid="columns"><property name="alias" value="u"/></include> WHERE ${runtimeColumn} = #{id}</select>
                </mapper>
                """);

        MyBatisSqlStatement statement = builder.build(repository, List.of("**/*.xml"), List.of())
                .statements().getFirst();

        assertThat(statement.normalizedSql())
                .isEqualTo("SELECT u.id, u.name FROM users u WHERE ${runtimeColumn} = #{id}");
        assertThat(statement.parameterPlaceholders()).containsExactly("${runtimeColumn}", "#{id}");
        assertThat(statement.resolvedFragmentIds()).containsExactly("demo.PropertyMapper.columns");
    }

    @Test
    void scopesPropertiesAcrossNamespaceQualifiedAndNestedIncludes() throws Exception {
        write("shared/SharedMapper.xml", """
                <mapper namespace="demo.Shared">
                  <sql id="table">accounts ${alias}</sql>
                  <sql id="query">${alias}.id FROM <include refid="${tableFragment}"><property name="alias" value="${alias}"/></include></sql>
                </mapper>
                """);
        write("mapper/CallerMapper.xml", """
                <mapper namespace="demo.Caller">
                  <select id="find">SELECT <include refid="demo.Shared.query"><property name="alias" value="a"/><property name="tableFragment" value="demo.Shared.table"/></include></select>
                </mapper>
                """);

        assertThatCode(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .doesNotThrowAnyException();
        MyBatisSqlStatement statement = builder.build(repository, List.of("**/*.xml"), List.of())
                .statements().getFirst();

        assertThat(statement.normalizedSql()).isEqualTo("SELECT a.id FROM accounts a");
        assertThat(statement.parameterPlaceholders()).isEmpty();
        assertThat(statement.resolvedFragmentIds())
                .containsExactly("demo.Shared.query", "demo.Shared.table");
    }

    @Test
    void rejectsDuplicatePropertyNamesOnOneInclude() throws Exception {
        write("mapper/DuplicatePropertyMapper.xml", """
                <mapper namespace="demo.DuplicatePropertyMapper">
                  <sql id="columns">${alias}.id</sql>
                  <select id="find"><include refid="columns"><property name="alias" value="a"/><property name="alias" value="b"/></include></select>
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate include property 'alias'")
                .hasMessageContaining("mapper/DuplicatePropertyMapper.xml");
    }

    @Test
    void preservesUnknownRuntimePropertiesInFragmentBodies() throws Exception {
        write("mapper/RuntimePropertyMapper.xml", """
                <mapper namespace="demo.RuntimePropertyMapper">
                  <sql id="predicate">WHERE ${column}=#{value}</sql>
                  <select id="find">SELECT * FROM users <include refid="predicate"/></select>
                </mapper>
                """);

        assertThatCode(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .doesNotThrowAnyException();
        MyBatisSqlStatement statement = builder.build(repository, List.of("**/*.xml"), List.of())
                .statements().getFirst();

        assertThat(statement.normalizedSql()).isEqualTo("SELECT * FROM users WHERE ${column}=#{value}");
        assertThat(statement.parameterPlaceholders()).containsExactly("${column}", "#{value}");
    }

    @Test
    void rejectsUnresolvedPropertiesInIncludeRefids() throws Exception {
        write("mapper/UnresolvedRefidMapper.xml", """
                <mapper namespace="demo.UnresolvedRefidMapper">
                  <sql id="predicate">WHERE enabled = true</sql>
                  <select id="find">SELECT * FROM users <include refid="${fragment}"/></select>
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved include property 'fragment'")
                .hasMessageContaining("mapper/UnresolvedRefidMapper.xml");
    }

    @Test
    void rejectsUnresolvedPropertiesInIncludePropertyValues() throws Exception {
        write("mapper/UnresolvedPropertyValueMapper.xml", """
                <mapper namespace="demo.UnresolvedPropertyValueMapper">
                  <sql id="columns">${alias}.id</sql>
                  <select id="find">SELECT <include refid="columns"><property name="alias" value="${missing}"/></include></select>
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved include property 'missing'")
                .hasMessageContaining("mapper/UnresolvedPropertyValueMapper.xml");
    }

    @Test
    void rejectsIncludeCyclesWithTheResolutionPath() throws Exception {
        write("mapper/CycleMapper.xml", """
                <mapper namespace="demo.CycleMapper">
                  <sql id="a"><include refid="b"/></sql>
                  <sql id="b"><include refid="a"/></sql>
                  <select id="find"><include refid="a"/></select>
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("include cycle")
                .hasMessageContaining("demo.CycleMapper.a")
                .hasMessageContaining("demo.CycleMapper.b");
    }

    @Test
    void rejectsMissingIncludesWithMapperAndStatementContext() throws Exception {
        write("mapper/MissingMapper.xml", """
                <mapper namespace="demo.MissingMapper">
                  <select id="find"><include refid="doesNotExist"/></select>
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing include")
                .hasMessageContaining("demo.MissingMapper.doesNotExist")
                .hasMessageContaining("mapper/MissingMapper.xml")
                .hasMessageContaining("find");
    }

    @Test
    void rejectsMissingIncludesInsideUnreferencedFragments() throws Exception {
        write("mapper/UnusedMissingMapper.xml", """
                <mapper namespace="demo.UnusedMissingMapper">
                  <sql id="unused"><include refid="doesNotExist"/></sql>
                  <select id="find">SELECT 1</select>
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing include")
                .hasMessageContaining("demo.UnusedMissingMapper.doesNotExist")
                .hasMessageContaining("demo.UnusedMissingMapper.unused");
    }

    @Test
    void rejectsCyclesInsideUnreferencedFragments() throws Exception {
        write("mapper/UnusedCycleMapper.xml", """
                <mapper namespace="demo.UnusedCycleMapper">
                  <sql id="a"><include refid="b"/></sql>
                  <sql id="b"><include refid="a"/></sql>
                  <select id="find">SELECT 1</select>
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("include cycle")
                .hasMessageContaining("demo.UnusedCycleMapper.a")
                .hasMessageContaining("demo.UnusedCycleMapper.b");
    }

    @Test
    void rejectsDuplicateStatementIdentitiesAcrossMapperFiles() throws Exception {
        write("one/FirstMapper.xml", """
                <mapper namespace="demo.DuplicateMapper">
                  <select id="same">SELECT 1</select>
                </mapper>
                """);
        write("two/SecondMapper.xml", """
                <mapper namespace="demo.DuplicateMapper">
                  <select id="same">SELECT 2</select>
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate statement identity")
                .hasMessageContaining("demo.DuplicateMapper.same")
                .hasMessageContaining("one/FirstMapper.xml")
                .hasMessageContaining("two/SecondMapper.xml");
    }

    @Test
    void emitsEachNestedSelectKeyAsASeparateOrdinalTask() throws Exception {
        write("mapper/KeyMapper.xml", """
                <mapper namespace="demo.KeyMapper">
                  <insert id="create">
                    <selectKey keyProperty="id" resultType="long" order="BEFORE">SELECT nextval('seq_a')</selectKey>
                    INSERT INTO users(id) VALUES (#{id})
                    <selectKey keyProperty="auditId" resultType="long" order="AFTER">SELECT nextval('seq_b')</selectKey>
                  </insert>
                </mapper>
                """);

        List<MyBatisSqlStatement> statements = builder.build(repository, List.of("**/*.xml"), List.of()).statements();

        assertThat(statements).hasSize(3);
        assertThat(statements).extracting(MyBatisSqlStatement::id)
                .containsExactly("create", "create", "create");
        assertThat(statements).extracting(MyBatisSqlStatement::selectKeyOrdinal)
                .containsExactly(0, 1, 2);
        assertThat(statements).extracting(MyBatisSqlStatement::commandType)
                .containsExactly("INSERT", "SELECT", "SELECT");
        assertThat(statements).extracting(MyBatisSqlStatement::selectKey)
                .containsExactly(false, true, true);
        assertThat(statements.getFirst().normalizedSql()).isEqualTo("INSERT INTO users(id) VALUES (#{id})");
        assertThat(statements.get(1).normalizedSql()).isEqualTo("SELECT nextval('seq_a')");
        assertThat(statements.get(2).normalizedSql()).isEqualTo("SELECT nextval('seq_b')");
        assertThat(statements).extracting(MyBatisSqlStatement::statementKey).doesNotHaveDuplicates();
    }

    @Test
    void appliesIncludesAndExcludesToXmlFilesOnly() throws Exception {
        write("mappers/KeepMapper.xml", """
                <mapper namespace="demo.KeepMapper">
                  <select id="keep">SELECT 1</select>
                </mapper>
                """);
        write("mappers/generated/DropMapper.xml", """
                <mapper namespace="demo.DropMapper">
                  <select id="drop">SELECT 2</select>
                </mapper>
                """);
        write("mappers/AnnotationSql.java", """
                interface AnnotationSql { String SQL = "SELECT 3"; }
                """);

        MyBatisSqlInventory inventory = builder.build(
                repository,
                List.of("**/*Mapper.xml"),
                List.of("**/generated/**"));

        assertThat(inventory.mappers()).extracting(MyBatisMapperInventory::mapperRelativePath)
                .containsExactly("mappers/KeepMapper.xml");
        assertThat(inventory.statements()).extracting(MyBatisSqlStatement::id).containsExactly("keep");
    }

    @Test
    void rejectsXmlSymlinksToRepositoryFiles() throws Exception {
        Path target = write("targets/RealMapper.xml", """
                <mapper namespace="demo.RealMapper">
                  <select id="real">SELECT 1</select>
                </mapper>
                """);
        Path link = repository.resolve("LinkedMapper.xml");
        createSymlinkOrSkip(link, target);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/LinkedMapper.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic links are forbidden")
                .hasMessageContaining("LinkedMapper.xml");
    }

    @Test
    void rejectsXmlSymlinksThatEscapeTheRepository() throws Exception {
        Path outsideDirectory = Files.createTempDirectory("mybatis-inventory-symlink-");
        Path outsideMapper = outsideDirectory.resolve("OutsideMapper.xml");
        Files.writeString(outsideMapper, """
                <mapper namespace="demo.OutsideMapper">
                  <select id="outside">SELECT 1</select>
                </mapper>
                """, StandardCharsets.UTF_8);
        Path link = repository.resolve("EscapeMapper.xml");
        try {
            createSymlinkOrSkip(link, outsideMapper);
            assertThatThrownBy(() -> builder.build(repository, List.of("**/EscapeMapper.xml"), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("symbolic links are forbidden")
                    .hasMessageContaining("EscapeMapper.xml");
        } finally {
            Files.deleteIfExists(outsideMapper);
            Files.deleteIfExists(outsideDirectory);
        }
    }

    @Test
    void rejectsMalformedXmlWithItsRelativePath() throws Exception {
        write("mapper/BrokenMapper.xml", """
                <mapper namespace="demo.BrokenMapper">
                  <select id="broken">SELECT 1
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failed to parse MyBatis mapper XML")
                .hasMessageContaining("mapper/BrokenMapper.xml");
    }

    @Test
    void rejectsUnreadableXmlWithItsRelativePath() throws Exception {
        Path mapper = write("mapper/UnreadableMapper.xml", """
                <mapper namespace="demo.UnreadableMapper">
                  <select id="unreadable">SELECT 1</select>
                </mapper>
                """);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(mapper);
        Files.setPosixFilePermissions(mapper, Set.of());
        try {
            assumeFalse(Files.isReadable(mapper), "filesystem still reports a mode-000 file as readable");
            assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unable to read MyBatis mapper XML")
                    .hasMessageContaining("mapper/UnreadableMapper.xml");
        } finally {
            Files.setPosixFilePermissions(mapper, original);
        }
    }

    @Test
    void rejectsExternalEntitiesBeforeResolvingThem() throws Exception {
        write("mapper/XxeMapper.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE mapper [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <mapper namespace="demo.XxeMapper">
                  <select id="leak">SELECT '&xxe;'</select>
                </mapper>
                """);

        assertThatThrownBy(() -> builder.build(repository, List.of("**/*.xml"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("external entities are forbidden")
                .hasMessageContaining("mapper/XxeMapper.xml");
    }

    @Test
    void propertiesDefensivelyCopyInventoryFilters() {
        MyBatisSqlReviewProperties properties = new MyBatisSqlReviewProperties();
        List<String> includes = new ArrayList<>(List.of("src/**/*.xml"));
        List<String> excludes = new ArrayList<>(List.of("target/**"));

        properties.setIncludes(includes);
        properties.setExcludes(excludes);
        includes.add("later/**/*.xml");
        excludes.clear();

        assertThat(properties.getIncludes()).containsExactly("src/**/*.xml");
        assertThat(properties.getExcludes()).containsExactly("target/**");
        assertThatThrownBy(() -> properties.getIncludes().add("mutate"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private Path write(String relativePath, String content) throws IOException {
        Path path = repository.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private Path writeBytes(String relativePath, byte[] content) throws IOException {
        Path path = repository.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, content);
        return path;
    }

    private void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException ex) {
            assumeTrue(false, "symbolic links are not supported: " + ex.getMessage());
        } catch (IOException ex) {
            assumeTrue(false, "symbolic links are unavailable: " + ex.getMessage());
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
