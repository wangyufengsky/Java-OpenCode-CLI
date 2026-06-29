const chainConfigDefinitions = {
  'git-code-contribution-report': {
    description: '读取 Git 仓库改动，生成个人贡献明细和中文总报告。',
    fields: [
      field('project.id', '项目标识', 'upfs-production', '写入报告元信息的项目 ID。'),
      field('project.name', '项目名称', 'UPFS Production', '写入报告标题和摘要的项目名称。'),
      field('project.run-id', '运行标识', '', '可选；用于把本次运行和外部批次关联。'),
      field('paths.repo', 'Git 仓库路径', '/home/wangyufeng/workspace/upfs-production', '要统计代码贡献的本地仓库。'),
      field('paths.out', '输出目录', '/home/wangyufeng/reports/git-code-contribution/2026-06-15', '报告、任务文件和运行状态的输出目录。'),
      field('git.since', '统计开始日期', '2026-06-01', 'Git 统计窗口开始日期，格式为 yyyy-MM-dd。'),
      field('git.until', '统计结束日期', '2026-06-15', 'Git 统计窗口结束日期，格式为 yyyy-MM-dd。'),
      field('git.revision', 'Git 修订点', 'HEAD', '用于统计的 revision，通常保持 HEAD。'),
      field('git.include-merges', '包含合并提交', false, '是否把 merge commit 纳入贡献统计。', 'checkbox'),
      field('git.author-map', '作者映射', '', '可选；需要合并作者身份时填写映射配置。', 'textarea'),
      field('git.include', '包含路径', [], '可选；每行一个 include glob，留空表示不限制。', 'list'),
      field('git.exclude', '排除路径', ['target/**', '*.lock'], '每行一个 exclude glob，用于过滤构建产物或锁文件。', 'list'),
      field('opencode.worker-message', '明细任务消息', '严格执行附件 worker-prompt.md 中的任务，只输出 DONE 或 BLOCKED。', '发送给个人明细 OpenCode session 的附加执行消息。', 'textarea'),
      field('opencode.synthesis-message', '汇总任务消息', '严格执行附件 synthesis-prompt.md 中的任务，生成最终中文总报告。', '发送给汇总 OpenCode session 的附加执行消息。', 'textarea'),
      field('detail-input.top-files', '明细 Top 文件数', 10, '每个作者明细输入保留的高影响文件数量。', 'number'),
      field('detail-input.commits', '明细提交数', 20, '每个作者明细输入保留的提交数量。', 'number'),
      field('detail-input.changed-regions', '改动区域数', 40, '每个作者明细输入保留的 changed_regions 数量。', 'number'),
      field('detail-input.changed-region-lines', '改动区域行数', 24, '每个 changed_region 保留的上下文行数。', 'number'),
      field('synthesis-input.person-report-excerpt-chars', '个人报告摘录字符数', 8192, '汇总阶段读取每份个人报告的最大字符数。', 'number'),
      field('synthesis-input.snippets-per-author', '每人代码片段数', 5, '汇总阶段每个作者最多保留的代码片段数。', 'number'),
      field('synthesis-input.snippets-total', '总代码片段数', 30, '汇总阶段最多保留的代码片段总数。', 'number'),
      field('synthesis-input.snippet-lines', '代码片段行数', 20, '每个代码片段最多保留的行数。', 'number')
    ]
  },
  'smartesb-rewrite-code-review': {
    description: '审查 SmartESB 8583 到 JSON 改造结果，按交易或模块生成明细和汇总报告。',
    fields: [
      field('out', '逻辑输出目录', '/home/wangyufeng/review-output/smartesb-rewrite-review', '报告中的逻辑输出目录，会在其下按运行日期创建子目录。'),
      field('local-out', '本机输出目录', '', '可选；本机实际落盘目录，留空时直接使用逻辑输出目录。'),
      field('transaction-plan-dir', '每日计划目录', 'src/main/resources/smartesb-transactions', '交易/模块计划根目录，运行日期下应存在 transactions.yml。'),
      field('new-project', '新 JSON 项目根目录', '/home/wangyufeng/upfs-nl-json', '新项目根目录，也是 OpenCode session directory。'),
      field('old-8583-doc', '老 8583 设计文档', '/home/wangyufeng/upfs-nl-json/doc/docment/old-8583.md', '老 8583/老代码详细设计文档。'),
      field('doc-root', '文档根目录', '/home/wangyufeng/upfs-nl-json/doc/docment', 'mapping-doc 或 reconstructed-design 为空时用它拼默认文档路径。'),
      field('mapping-doc', '映射文档', '', '可选；8583 到 JSON 映射文档，留空使用默认路径。'),
      field('reconstructed-design', '重构设计文档', '', '可选；重构项目详细设计，留空使用默认路径。'),
      field('worker-message', '单项任务消息', '严格执行附件 worker-prompt.md 中的 SmartESB 单项审查任务，只输出 DONE 或 BLOCKED。', '发送给单项 OpenCode session 的附加执行消息。', 'textarea'),
      field('synthesis-message', '汇总任务消息', '严格执行附件 synthesis-prompt.md 中的 SmartESB 汇总任务，生成中文 index.md 和 summary.md。', '发送给汇总 OpenCode session 的附加执行消息。', 'textarea')
    ]
  },
  'smartesb-code-reader': {
    description: '读取 SmartESB 交易 XML、模块和 Java 源码，生成代码阅读索引。',
    fields: [
      field('out', '逻辑输出目录', '/home/wangyufeng/review-output/smartesb-code-reader', '报告逻辑输出目录，可以是 Linux 或 Windows 绝对路径。'),
      field('local-out', '本机输出目录', '', '可选；本机实际落盘目录，留空时直接使用逻辑输出目录。'),
      field('service-identify', 'serviceIdentify.xml', ['/home/wangyufeng/upfs-production/serviceIdentify.xml'], '一个或多个 serviceIdentify.xml，每行一个路径。', 'list'),
      field('xml-root', 'XML 根目录', '/home/wangyufeng/upfs-production', '交易 XML 和 base XML 根目录。'),
      field('biz-root', '.biz 根目录', '', '可选；留空时等于 XML 根目录。'),
      field('java-root', 'Java 源码根目录', '/home/wangyufeng/upfs-production', 'Java 源码根目录，也是 OpenCode session directory。'),
      field('mode', 'Switch Mode', '8583', 'serviceIdentify.xml 中要读取的 switch mode。'),
      field('worker-message', '阅读任务消息', '严格执行附件 worker-prompt.md 中的 SmartESB code-reader 单项阅读任务，只输出 DONE 或 BLOCKED。', '发送给模块/交易 OpenCode session 的附加执行消息。', 'textarea'),
      field('synthesis-message', '索引任务消息', '严格执行附件 synthesis-prompt.md 中的 SmartESB code-reader 索引任务，生成中文 index.md。', '发送给索引 OpenCode session 的附加执行消息。', 'textarea')
    ]
  },
  'weekly-engineering-report': {
    description: '读取指定统计窗口内的 Git 改动，生成周度工程报告和管理视角材料。',
    fields: [
      field('project.id', '项目标识', 'upfs-production', '写入周报元信息的项目 ID。'),
      field('project.name', '项目名称', 'UPFS Production', '写入周报标题和摘要的项目名称。'),
      field('project.repo', 'Git 仓库路径', '/home/wangyufeng/workspace/upfs-production', '周报统计使用的本地仓库。'),
      field('paths.out', '输出目录', '/home/wangyufeng/reports/weekly-engineering/2026-W26', '周报和证据文件输出目录。'),
      field('startday', '统计开始日期', '2026-06-19', '周报统计窗口开始日期，格式为 yyyy-MM-dd。'),
      field('endday', '统计结束日期', '2026-06-26', '周报统计窗口结束日期，格式为 yyyy-MM-dd。'),
      field('git.exclude', '排除路径', ['target/**', '*.lock'], '每行一个 exclude glob，用于过滤构建产物或锁文件。', 'list'),
      field('review.max-regions-per-batch', '每批最大改动区域', 8, '代码审查批次中最多包含的 changed_regions 数量。', 'number'),
      field('review.max-hunk-lines', '每个 hunk 最大行数', 24, '审查输入中每个 hunk 保留的最大行数。', 'number'),
      field('review.concurrency', '审查并发数', 3, '周报链路内部代码审查任务并发数。', 'number'),
      field('opencode.timeout-minutes', 'OpenCode 超时分钟数', 40, '单个 OpenCode session 的超时时间。', 'number')
    ]
  }
};

function field(key, label, defaultValue, description, type = 'text') {
  return { key, label, defaultValue, description, type };
}

const chainSelect = document.querySelector('#chainId');
const configFields = document.querySelector('#chain-config-fields');
const configDescription = document.querySelector('#chain-config-description');
const runForm = document.querySelector('#run-form');

if (chainSelect && configFields) {
  renderConfigFields(chainSelect.value);
  chainSelect.addEventListener('change', () => {
    renderConfigFields(chainSelect.value);
    const url = new URL(window.location.href);
    url.searchParams.set('chainId', chainSelect.value);
    history.replaceState(null, '', url);
  });
}

function renderConfigFields(chainId) {
  const definition = chainConfigDefinitions[chainId];
  configFields.replaceChildren();
  configDescription.textContent = definition ? definition.description : '';
  if (!definition) return;
  definition.fields.forEach((definitionField) => {
    configFields.appendChild(createConfigField(definitionField));
  });
}

function createConfigField(definitionField) {
  const wrapper = document.createElement('label');
  wrapper.className = `config-field config-field-${definitionField.type}`;
  wrapper.htmlFor = fieldId(definitionField.key);

  const title = document.createElement('span');
  title.className = 'config-field-title';
  title.textContent = definitionField.label;
  wrapper.appendChild(title);

  const control = createControl(definitionField);
  wrapper.appendChild(control);

  const description = document.createElement('small');
  description.textContent = `${definitionField.description} 默认值：${formatDefault(definitionField.defaultValue)}`;
  wrapper.appendChild(description);
  return wrapper;
}

function createControl(definitionField) {
  let control;
  if (definitionField.type === 'textarea' || definitionField.type === 'list') {
    control = document.createElement('textarea');
    control.rows = definitionField.type === 'list' ? 3 : 4;
    control.value = Array.isArray(definitionField.defaultValue) ? definitionField.defaultValue.join('\n') : definitionField.defaultValue;
  } else {
    control = document.createElement('input');
    control.type = definitionField.type === 'checkbox' ? 'checkbox' : definitionField.type;
    if (definitionField.type === 'checkbox') {
      control.checked = Boolean(definitionField.defaultValue);
    } else {
      control.value = definitionField.defaultValue ?? '';
    }
  }
  control.id = fieldId(definitionField.key);
  control.name = definitionField.key;
  control.dataset.configKey = definitionField.key;
  control.dataset.configType = definitionField.type;
  return control;
}

function collectConfig(chainId) {
  const definition = chainConfigDefinitions[chainId];
  if (!definition) return {};
  return Object.fromEntries(definition.fields.map((definitionField) => {
    const control = document.querySelector(`[data-config-key="${definitionField.key}"]`);
    return [definitionField.key, readValue(control, definitionField.type)];
  }));
}

function readValue(control, type) {
  if (!control) return null;
  if (type === 'checkbox') return control.checked;
  if (type === 'number') return control.value === '' ? null : Number(control.value);
  if (type === 'list') {
    return control.value.split('\n').map((line) => line.trim()).filter(Boolean);
  }
  return control.value;
}

function fieldId(key) {
  return `config-${key.replaceAll('.', '-').replaceAll('/', '-')}`;
}

function formatDefault(value) {
  if (Array.isArray(value)) return value.length === 0 ? '空列表' : value.join('，');
  if (value === '') return '空';
  if (value === true) return '是';
  if (value === false) return '否';
  return String(value);
}

if (runForm) {
  runForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const result = document.querySelector('#submit-result');
    result.textContent = '正在提交...';
    const payload = {
      chainId: document.querySelector('#chainId').value,
      mode: document.querySelector('#mode').value,
      rerunType: document.querySelector('#rerunType').value,
      rerunId: document.querySelector('#rerunId').value,
      runDate: document.querySelector('#runDate').value || null,
      config: collectConfig(document.querySelector('#chainId').value)
    };
    const response = await fetch('/api/runs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const body = await response.json();
    if (response.ok) {
      window.location.href = `/runs/${body.id}`;
    } else {
      result.textContent = body.error || '提交失败';
    }
  });
}

const stateLabels = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '已成功',
  FAILED: '已失败'
};

const eventLabels = {
  QUEUED: '已排队',
  STARTED: '已开始',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
  TASK_GROUP_STARTED: '任务组已开始',
  TASK_GROUP_SUCCEEDED: '任务组已完成',
  TASK_QUEUED: '任务已排队',
  TASK_RUNNING: '任务运行中',
  TASK_SUCCEEDED: '任务已成功',
  TASK_FAILED: '任务已失败'
};

const runId = document.body.dataset.runId;
if (runId && window.EventSource) {
  const streamState = document.querySelector('#stream-state');
  const events = document.querySelector('#events');
  const seenEvents = new Set(Array.from(events.querySelectorAll('li[data-event-id]')).map((item) => item.dataset.eventId));
  const source = new EventSource(`/api/runs/${runId}/events`);
  source.onopen = () => { streamState.textContent = '实时'; };
  source.onerror = () => { streamState.textContent = '重连中'; };
  [
    'QUEUED',
    'STARTED',
    'SUCCEEDED',
    'FAILED',
    'TASK_GROUP_STARTED',
    'TASK_GROUP_SUCCEEDED',
    'TASK_QUEUED',
    'TASK_RUNNING',
    'TASK_SUCCEEDED',
    'TASK_FAILED'
  ].forEach((type) => {
    source.addEventListener(type, appendEvent);
  });
  function appendEvent(message) {
    const event = JSON.parse(message.data);
    const eventId = String(event.id);
    if (seenEvents.has(eventId)) return;
    seenEvents.add(eventId);
    const item = document.createElement('li');
    item.dataset.eventId = eventId;
    const time = document.createElement('time');
    time.textContent = event.createdAt;
    const type = document.createElement('strong');
    type.textContent = eventLabels[event.eventType] || event.eventType;
    const text = document.createElement('span');
    text.textContent = event.message;
    item.append(time, type, text);
    events.appendChild(item);
    if (event.eventType === 'SUCCEEDED' || event.eventType === 'FAILED') {
      const state = document.querySelector('#run-state');
      if (state) state.textContent = stateLabels[event.eventType] || event.eventType;
    }
  }
}
