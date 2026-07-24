(function exposeConsoleCommon() {
  function field(key, label, description, type = 'text', group = 'advanced', required = false, summary = false) {
    return { key, label, description, type, group, required, summary };
  }

  const groupDefinitions = [
    { group: 'project', label: '项目与输出', description: '标识当前项目并确定运行产物位置。' },
    { group: 'scope', label: '范围与输入', description: '限定本次运行读取的仓库、源码和统计窗口。' },
    { group: 'validation', label: '校验与质量', description: '调整校验标准、输入规模和质量门槛。' },
    { group: 'agentbridge', label: 'AgentBridge', description: '配置任务消息、连接地址、超时和尝试次数。' }
  ];

  const chainConfigDefinitions = {
    'git-code-contribution-report': {
      label: '代码贡献报告',
      description: '读取 Git 仓库改动，生成个人贡献明细和中文总报告。',
      fields: [
        field('project.id', '项目标识', '写入报告元信息的项目 ID。', 'text', 'project', true, true),
        field('project.name', '项目名称', '写入报告标题和摘要的项目名称。', 'text', 'project', true, true),
        field('project.run-id', '运行标识', '可选；用于把本次运行和外部批次关联。', 'text', 'project'),
        field('paths.out', '输出目录', '报告、任务文件和运行状态的输出目录。', 'path', 'project', true, true),
        field('paths.repo', 'Git 仓库路径', '要统计代码贡献的本地仓库。', 'text', 'scope', true, true),
        field('git.since', '统计开始日期', 'Git 统计窗口开始日期，格式为 yyyy-MM-dd。', 'text', 'scope'),
        field('git.until', '统计结束日期', 'Git 统计窗口结束日期，格式为 yyyy-MM-dd。', 'text', 'scope'),
        field('git.include', '包含路径', '可选；每行一个 include glob，留空表示不限制。', 'list', 'scope'),
        field('git.exclude', '排除路径', '每行一个 exclude glob，用于过滤构建产物或锁文件。', 'list', 'scope'),
        field('git.revision', 'Git 修订点', '用于统计的 revision，通常保持 HEAD。', 'text', 'validation'),
        field('git.include-merges', '包含合并提交', '是否把 merge commit 纳入贡献统计。', 'checkbox', 'validation'),
        field('git.author-map', '作者映射', '可选；需要合并作者身份时填写映射配置。', 'textarea', 'validation'),
        field('detail-input.top-files', '明细 Top 文件数', '每个作者明细输入保留的高影响文件数量。', 'number', 'validation'),
        field('detail-input.commits', '明细提交数', '每个作者明细输入保留的提交数量。', 'number', 'validation'),
        field('detail-input.changed-regions', '改动区域数', '每个作者明细输入保留的 changed_regions 数量。', 'number', 'validation'),
        field('detail-input.changed-region-lines', '改动区域行数', '每个 changed_region 保留的上下文行数。', 'number', 'validation'),
        field('synthesis-input.person-report-excerpt-chars', '个人报告摘录字符数', '汇总阶段读取每份个人报告的最大字符数。', 'number', 'validation'),
        field('synthesis-input.snippets-per-author', '每人代码片段数', '汇总阶段每个作者最多保留的代码片段数。', 'number', 'validation'),
        field('synthesis-input.snippets-total', '总代码片段数', '汇总阶段最多保留的代码片段总数。', 'number', 'validation'),
        field('synthesis-input.snippet-lines', '代码片段行数', '每个代码片段最多保留的行数。', 'number', 'validation'),
        field('agentbridge.task-message', '明细任务消息', '发送给个人明细 AgentBridge task 的附加执行消息。', 'textarea', 'agentbridge'),
        field('agentbridge.synthesis-task-message', '汇总任务消息', '发送给汇总 AgentBridge task 的附加执行消息。', 'textarea', 'agentbridge')
      ]
    },
    'smartesb-rewrite-code-review': {
      label: 'SmartESB 改造评审',
      description: '审查 SmartESB 8583 到 JSON 改造结果，按交易或模块生成明细和汇总报告。',
      fields: [
        field('out', '逻辑输出目录', '报告中的逻辑输出目录，会在其下按运行日期创建子目录。', 'path', 'project', true, true),
        field('local-out', '本机输出目录', '可选；本机实际落盘目录，留空时直接使用逻辑输出目录。', 'path', 'project'),
        field('new-project', '新 JSON 项目根目录', '新项目根目录，也是 AgentBridge task 工作目录。', 'path', 'project', true, true),
        field('transaction-plan-dir', '每日计划目录', '交易/模块计划根目录，运行日期下应存在 transactions.yml。', 'path', 'scope', true),
        field('old-8583-doc', '老 8583 设计文档', '老 8583/老代码详细设计文档。', 'text', 'scope', true),
        field('doc-root', '文档根目录', 'mapping-doc 或 reconstructed-design 为空时用它拼默认文档路径。', 'path', 'scope'),
        field('mapping-doc', '映射文档', '可选；8583 到 JSON 映射文档，留空使用默认路径。', 'text', 'scope'),
        field('reconstructed-design', '重构设计文档', '可选；重构项目详细设计，留空使用默认路径。', 'text', 'scope'),
        field('task-message', '单项任务消息', '发送给单项 AgentBridge task 的附加执行消息。', 'textarea', 'agentbridge'),
        field('synthesis-task-message', '汇总任务消息', '发送给汇总 AgentBridge task 的附加执行消息。', 'textarea', 'agentbridge')
      ]
    },
    'smartesb-code-reader': {
      label: 'SmartESB 代码阅读',
      description: '读取 SmartESB 交易 XML、模块和 Java 源码，生成代码阅读索引。',
      fields: [
        field('out', '逻辑输出目录', '报告逻辑输出目录，可以是 Linux 或 Windows 绝对路径。', 'path', 'project', true, true),
        field('local-out', '本机输出目录', '可选；本机实际落盘目录，留空时直接使用逻辑输出目录。', 'path', 'project'),
        field('service-identify', 'serviceIdentify.xml', '一个或多个 serviceIdentify.xml，每行一个路径。', 'list', 'scope', true),
        field('xml-root', 'XML 根目录', '交易 XML 和 base XML 根目录。', 'text', 'scope', true),
        field('biz-root', '.biz 根目录', '可选；留空时等于 XML 根目录。', 'text', 'scope'),
        field('java-root', 'Java 源码根目录', 'Java 源码根目录，也是 AgentBridge task 工作目录。', 'text', 'scope', true, true),
        field('mode', 'Switch Mode', 'serviceIdentify.xml 中要读取的 switch mode。', 'text', 'validation'),
        field('task-message', '阅读任务消息', '发送给模块/交易 AgentBridge task 的附加执行消息。', 'textarea', 'agentbridge'),
        field('synthesis-task-message', '索引任务消息', '发送给索引 AgentBridge task 的附加执行消息。', 'textarea', 'agentbridge')
      ]
    },
    'weekly-engineering-report': {
      label: '研发周报',
      description: '读取指定统计窗口内的 Git 改动，生成周度工程报告和管理视角材料。',
      fields: [
        field('project.id', '项目标识', '写入周报元信息的项目 ID。', 'text', 'project', true, true),
        field('project.name', '项目名称', '写入周报标题和摘要的项目名称。', 'text', 'project', true, true),
        field('paths.out', '输出目录', '周报和证据文件输出目录。', 'path', 'project', true),
        field('project.repo', 'Git 仓库路径', '周报统计使用的本地仓库。', 'text', 'scope', true, true),
        field('startday', '统计开始日期', '周报统计窗口开始日期，格式为 yyyy-MM-dd。', 'text', 'scope'),
        field('endday', '统计结束日期', '周报统计窗口结束日期，格式为 yyyy-MM-dd。', 'text', 'scope'),
        field('git.exclude', '排除路径', '每行一个 exclude glob，用于过滤构建产物或锁文件。', 'list', 'scope'),
        field('review.max-hunk-lines', '每个 hunk 最大行数', '审查输入中每个 hunk 保留的最大行数。', 'number', 'validation'),
        field('review.concurrency', '审查并发数', '周报链路内部代码审查任务并发数。', 'number', 'validation'),
        field('review.grouping.target-task-count', '目标任务数', '期望收缩后的 review unit 数量。', 'number', 'validation'),
        field('review.grouping.max-regions-per-task', '每任务最大区域数', '单个 review unit 最多包含的 changed_regions 数量。', 'number', 'validation'),
        field('review.grouping.max-files-per-task', '每任务最大文件数', '单个 review unit 最多覆盖的文件数量。', 'number', 'validation'),
        field('review.grouping.max-hunk-chars-per-task', '每任务最大 hunk 字符数', '单个 review unit 的 hunk 内容字符上限。', 'number', 'validation'),
        field('review.grouping.max-commits-per-task', '每任务最大提交数', '单个 review unit 最多覆盖的 commit 数量。', 'number', 'validation'),
        field('agentbridge.timeout-minutes', 'AgentBridge 超时分钟数', '单个 AgentBridge task 的超时时间。', 'number', 'agentbridge')
      ]
    },
    'project-unit-test-generation': {
      label: '单元测试生成',
      description: '默认全量扫描项目源码，也可按包路径串行生成单元测试；每任务一个类，一个 AgentBridge agent 只写一个类。',
      fields: [
        field('project.id', '项目标识', '写入任务和报告元信息的项目 ID。', 'text', 'project', true, true),
        field('project.name', '项目名称', '写入任务和报告标题的项目名称。', 'text', 'project', true, true),
        field('paths.out', '输出目录', '测试任务包、运行状态和验收报告输出目录。', 'path', 'project', true),
        field('project.repo', '项目仓库路径', '要生成单元测试的本地项目仓库。', 'text', 'scope', true, true),
        field('docs.agents', 'AGENTS.md', '可选；相对项目仓库的 AGENTS.md 路径。', 'text', 'scope'),
        field('docs.project-map', 'project-map.md', '可选；相对项目仓库的项目地图文档路径。', 'text', 'scope'),
        field('docs.reconstructed-design', '重构设计文档', '可选；相对项目仓库的重构项目详细设计文档路径。', 'text', 'scope'),
        field('source.package-paths', '包路径', '每行一个包名或源码路径，留空表示全量。', 'list', 'scope'),
        field('source.include', '包含路径', '可选；每行一个 include glob。', 'list', 'scope'),
        field('source.exclude', '排除路径', '每行一个 exclude glob，用于过滤构建输出或生成代码。', 'list', 'scope'),
        field('test.require-coverage', '要求覆盖率', '开启后才执行 JaCoCo 覆盖率验收；默认关闭。', 'checkbox', 'validation'),
        field('test.coverage-threshold-percent', '覆盖率阈值', '要求覆盖率时，目标源码类达到该覆盖率百分比后当前 batch 才算达标。', 'number', 'validation'),
        field('test.jacoco-version', 'JaCoCo 版本', '要求覆盖率时，run_command 验收测试使用的 JaCoCo 版本。', 'text', 'validation'),
        field('test.jacoco-jvm-arg-property', 'JaCoCo JVM 参数属性', '目标 Maven Surefire 实际读取的 JVM 参数属性。', 'text', 'validation'),
        field('test.jacoco-jvm-arg-base', 'JaCoCo JVM 基础参数', '可选；会和 -javaagent 一起写入 Maven 属性。', 'text', 'validation'),
        field('agentbridge.web-base-url', 'AgentBridge Web URL', '提交 prompt 并监听运行状态的 Web Access 地址。', 'text', 'agentbridge'),
        field('agentbridge.mcp-url', 'AgentBridge MCP URL', 'Java 侧验收测试使用的 MCP JSON-RPC 地址。', 'text', 'agentbridge'),
        field('agentbridge.timeout-minutes', 'AgentBridge 超时分钟数', '单轮 agent 最大等待时间。', 'number', 'agentbridge'),
        field('agentbridge.max-attempts', '最大尝试轮次', '当前 batch 未达标时最多重新启动 agent 的次数。', 'number', 'agentbridge')
      ]
    },
    'mybatis-sql-review': {
      label: 'MyBatis SQL 审查',
      description: '发现 MyBatis XML 映射语句；review agent 通过 AgentBridge MCP 调用 AgentBridge Custom MCP 注册的数据库只读工具，生成逐 SQL 审查和项目汇总报告。',
      fields: [
        field('project.id', '项目标识', '写入清单和报告元信息的项目 ID。', 'text', 'project', true, true),
        field('project.name', '项目名称', '写入报告标题和摘要的项目名称。', 'text', 'project', true, true),
        field('paths.out', '输出目录', '运行隔离目录、稳定报告和 output_path 对应的输出根目录。', 'path', 'project', true, true),
        field('project.repo', '项目仓库路径', '包含 MyBatis XML mapper 的本地项目仓库。', 'text', 'scope', true, true),
        field('source.include', 'Mapper 包含路径', '每行一个 XML include glob；至少保留一个 mapper 范围。', 'list', 'scope', true, true),
        field('source.exclude', 'Mapper 排除路径', '每行一个 exclude glob，用于过滤构建输出。', 'list', 'scope'),
        field('database.connection-name', '数据库连接名', 'AgentBridge Custom MCP 注册的 Database MCP 数据源名称。', 'text', 'validation', true),
        field('database.database-name', '数据库名', '集中式 GaussDB 物理只读副本中的数据库名。', 'text', 'validation', true),
        field('database.schema-name', 'Schema 名', '数据库证据只允许绑定到该 schema。', 'text', 'validation', true),
        field('database.scope', '数据源范围', 'Database MCP 数据源范围：GLOBAL、PROJECT 或 ALL；默认 ALL。', 'text', 'validation', true),
        field('database.safety-mode', '数据库安全模式', '开发库使用 connectivity-only；严格环境使用 strict。', 'text', 'validation', true),
        field('database.environment', '数据库环境', 'connectivity-only 必须为 test；strict 必须为 read-replica。', 'text', 'validation', true),
        field('database.non-owner-non-admin-read-only-account', '专用只读账号确认', '确认运行凭证属于非 owner、非管理员的专用只读账号。', 'checkbox', 'validation', true),
        field('database.row-level-security-disabled-for-safe-base-tables', '安全表已禁用 RLS', '确认所有可取证基础表均已禁用行级安全策略。', 'checkbox', 'validation', true),
        field('database.user-defined-and-security-definer-function-execution-revoked-including-public', '函数 EXECUTE 已撤销', '确认用户函数及 SECURITY DEFINER 函数的 EXECUTE 已从账号和 PUBLIC 撤销。', 'checkbox', 'validation', true),
        field('database.statement-timeout-seconds', 'SQL 超时秒数', '数据库、server 或 role 级 statement_timeout，必须在 1 到 30 秒。', 'number', 'validation', true),
        field('database.statement-timeout-scope', 'SQL 超时作用域', '已确认的 timeout 作用域：database、server 或 role。', 'text', 'validation', true),
        field('database.max-rows', '每次最多保留行数', '固定为 20；Java 会拒绝其它值。', 'number', 'validation', true),
        field('database.max-scenarios-per-sql', '每条 SQL 最多场景数', '固定为 3；Java 会拒绝其它值。', 'number', 'validation', true),
        field('database.max-evidence-bytes', '证据最大字节数', '固定为 262144；Java 会拒绝其它值。', 'number', 'validation', true),
        field('database.retain-raw-rows', '保留原始证据行', '固定开启；确保报告证据可复核。', 'checkbox', 'validation', true),
        field('database.allow-agent-select', '允许受限 SELECT', '固定开启；仅允许 Java 审计策略规定的简单只读 SELECT。', 'checkbox', 'validation', true),
        field('agentbridge.web-base-url', 'AgentBridge Web URL', '提交 prompt、清理会话和读取 tool-call 历史的 Web Access 地址。', 'text', 'agentbridge', true),
        field('agentbridge.mcp-url', 'AgentBridge MCP URL', '应用预检和 review agent 都通过 AgentBridge MCP 调用 AgentBridge Custom MCP 注册的数据库工具；默认 http://127.0.0.1:8642/mcp。', 'text', 'agentbridge', true),
        field('agentbridge.concurrency', '任务并发数', '固定为 1；SQL 审查任务严格串行执行。', 'number', 'agentbridge', true),
        field('agentbridge.max-concurrency', '最大并发数', '固定为 1；Java 会拒绝其它值。', 'number', 'agentbridge', true),
        field('agentbridge.timeout-minutes', '任务超时分钟数', '单个 AgentBridge SQL 审查 task 的最大等待时间。', 'number', 'agentbridge', true),
        field('agentbridge.poll-millis', '轮询间隔毫秒数', '等待 AgentBridge 空闲状态时的轮询间隔。', 'number', 'agentbridge', true),
        field('agentbridge.validation-settle-seconds', '校验等待秒数', 'AgentBridge 空闲后读取工具历史和候选产物前的等待时间。', 'number', 'agentbridge', true),
        field('agentbridge.validation-max-corrections', '最大修正轮次', '固定为 0；本链路每个 task 只发送一个完整 prompt。', 'number', 'agentbridge', true),
        field('agentbridge.task-message', 'SQL 审查任务消息', '追加到每个完整 SQL review prompt 前的执行说明。', 'textarea', 'agentbridge', true)
      ]
    }
  };

  const rerunTypeDefinitions = {
    'git-code-contribution-report': [
      { value: 'author', label: '作者', requiresId: true, idPlaceholder: 'author_key，多个用英文逗号分隔' },
      { value: 'synthesis', label: '总报告', requiresId: false, idPlaceholder: '总报告重跑不需要编号' }
    ],
    'smartesb-rewrite-code-review': [
      { value: 'transaction', label: '交易', requiresId: true, idPlaceholder: '交易名，多个用英文逗号分隔' },
      { value: 'module', label: '模块', requiresId: true, idPlaceholder: '模块名，多个用英文逗号分隔' },
      { value: 'index', label: '总报告', requiresId: false, idPlaceholder: '总报告重跑不需要编号' }
    ],
    'smartesb-code-reader': [
      { value: 'transaction', label: '交易', requiresId: true, idPlaceholder: '交易名，多个用英文逗号分隔' },
      { value: 'module', label: '模块', requiresId: true, idPlaceholder: '模块名，多个用英文逗号分隔' },
      { value: 'index', label: '总报告', requiresId: false, idPlaceholder: '总报告重跑不需要编号' }
    ],
    'weekly-engineering-report': [
      { value: 'review-batch', label: '审查批次', requiresId: true, idPlaceholder: 'review batch id，多个用英文逗号分隔' },
      { value: 'synthesis', label: '总报告', requiresId: false, idPlaceholder: '总报告重跑不需要编号' }
    ],
    'project-unit-test-generation': [
      { value: 'test-batch', label: '测试批次', requiresId: true, idPlaceholder: 'test batch id，多个用英文逗号分隔' },
      { value: 'verification', label: '验证', requiresId: false, idPlaceholder: '验证重跑不需要编号' }
    ],
    'mybatis-sql-review': [
      { value: 'sql', label: 'SQL 语句', requiresId: true, idPlaceholder: 'statement key，多个用英文逗号分隔' },
      { value: 'xml', label: 'Mapper XML', requiresId: true, idPlaceholder: 'mapper key，多个用英文逗号分隔' },
      { value: 'index', label: '总报告', requiresId: false, idPlaceholder: '总报告重跑不需要编号' }
    ]
  };

  window.ConsoleCommon = {
    chainConfigDefinitions,
    groupDefinitions,
    rerunTypeDefinitions
  };
}());
