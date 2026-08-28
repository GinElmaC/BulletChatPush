<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import {
  Activity,
  BarChart3,
  Bell,
  ChevronDown,
  ChevronRight,
  FileSearch,
  MessageSquare,
  RefreshCw,
  Search,
  Server,
  UserRound,
  X
} from 'lucide-vue-next';

const navItems = ['概览', '节点管理', '消息监控', '连接管理', '日志中心', '系统设置'];
const apiBaseUrl = import.meta.env.VITE_PUSH_ADMIN_API_BASE_URL || 'http://localhost:9090/admin/api';
const nodes = ref([]);
const logCenterResults = ref([]);
const expandedLogIds = ref([]);
const apiError = ref('');

const statusMap = {
  normal: { text: '正常', className: 'normal', color: '#00b42a' },
  abnormal: { text: '异常', className: 'abnormal', color: '#f53f3f' },
  offline: { text: '离线', className: 'offline', color: '#c9cdd4' },
  restarting: { text: '重启中', className: 'restarting', color: '#ff7d00' }
};

const statusFilter = ref('all');
const keyword = ref('');
const selectedIds = ref([]);
const activePage = ref('节点管理');
const detailNode = ref(null);
// Agent 抽屉及流式回复状态。
const agentPanelOpen = ref(false);
const agentRunning = ref(false);
const agentThinkingSeconds = ref(0);
// 用户的路由偏好：auto 交给后端路由，固定值则指定模型。
const agentModelMode = ref('auto');
// 当前实际使用模型，Auto 模式会展示后端路由结果。
const agentModelName = ref('Auto');
const agentMessages = ref([]);
const logLevelFilter = ref('all');
const logQueryMode = ref('logId');
const logIdQuery = ref('');
const machineIdQuery = ref('');
const logSearchKeyword = ref('');
const refreshing = ref(false);
let refreshTimer;

const filteredNodes = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  return nodes.value.filter((node) => {
    const matchedStatus = statusFilter.value === 'all' || node.status === statusFilter.value;
    const matchedQuery = !query || `${node.id} ${node.machineId} ${node.ip} ${node.port}`.toLowerCase().includes(query);
    return matchedStatus && matchedQuery;
  });
});

const onlineNodes = computed(() => nodes.value.filter((node) => node.status !== 'offline'));
const stats = computed(() => {
  const activeNodes = onlineNodes.value;
  const totalMessages = activeNodes.reduce((sum, node) => sum + node.messages, 0);
  const totalConnections = activeNodes.reduce((sum, node) => sum + node.connections, 0);

  return [
    { label: '节点总数', value: nodes.value.length, unit: '个', icon: Server, tone: 'blue' },
    { label: '累计消息数', value: totalMessages.toLocaleString(), unit: '条', icon: MessageSquare, tone: 'green' },
    { label: '异常节点', value: nodes.value.filter((node) => node.status === 'abnormal').length, unit: '个', icon: Bell, tone: 'red' },
    { label: '当前连接数', value: totalConnections.toLocaleString(), unit: '个', icon: Activity, tone: 'orange' }
  ];
});

const allFilteredSelected = computed(() => {
  return filteredNodes.value.length > 0 && filteredNodes.value.every((node) => selectedIds.value.includes(node.id));
});

const nodePositions = computed(() => {
  const positions = {};
  nodes.value.forEach((node, index) => {
    positions[node.id] = {
      x: 140 + (index % 4) * 210,
      y: 110 + Math.floor(index / 4) * 130
    };
  });
  return positions;
});

let agentThinkingTimer;

function cpuClass(cpu) {
  if (cpu >= 80) {
    return 'high';
  }
  if (cpu >= 60) {
    return 'mid';
  }
  return 'low';
}

function toggleSelectAll(event) {
  const ids = filteredNodes.value.map((node) => node.id);
  if (event.target.checked) {
    selectedIds.value = Array.from(new Set([...selectedIds.value, ...ids]));
    return;
  }
  selectedIds.value = selectedIds.value.filter((id) => !ids.includes(id));
}

function refreshData() {
  refreshing.value = true;
  const request = activePage.value === '日志中心'
    ? searchLogs()
    : loadNodes();
  request.finally(() => {
    refreshing.value = false;
  });
}

function openLog(node) {
  // 从节点入口进入后，将该节点作为日志中心的默认查询范围。
  selectedIds.value = [node.id];
  detailNode.value = null;
  activePage.value = '日志中心';
  logQueryMode.value = 'machineId';
  machineIdQuery.value = String(node.machineId);
  logLevelFilter.value = 'all';
  logSearchKeyword.value = '';
  searchLogs();
}

function switchPage(item) {
  // 当前仅节点管理和日志中心已落地，其他导航先保持在节点管理页。
  activePage.value = item === '日志中心' ? item : '节点管理';
}

async function searchLogs() {
  expandedLogIds.value = [];
  apiError.value = '';
  const target = logQueryMode.value === 'logId'
    ? logIdQuery.value.trim()
    : machineIdQuery.value.trim();
  if (!target) {
    logCenterResults.value = [];
    return;
  }
  const params = new URLSearchParams({
    mode: logQueryMode.value,
    level: logLevelFilter.value,
    keyword: logSearchKeyword.value.trim(),
    limit: '100'
  });
  params.set(logQueryMode.value, target);
  try {
    const records = await apiGet(`/logs?${params.toString()}`);
    logCenterResults.value = records;
  } catch (error) {
    logCenterResults.value = [];
    apiError.value = '查询日志失败';
  }
}

function setLogLevel(level) {
  logLevelFilter.value = level;
  if (logQueryMode.value === 'logId' ? logIdQuery.value.trim() : machineIdQuery.value.trim()) {
    searchLogs();
  }
}

function toggleLogDetail(logId) {
  expandedLogIds.value = expandedLogIds.value.includes(logId)
    ? expandedLogIds.value.filter((id) => id !== logId)
    : [...expandedLogIds.value, logId];
}

function analyzeLogCenter() {
  if (logQueryMode.value !== 'logId' || !logIdQuery.value.trim()) {
    openAgentPanel(
      `[LogID] 请先输入唯一 LogID 后再发起日志智能分析。`,
      [],
      Promise.resolve({ analysis: '服务分析失败' })
    );
    return;
  }
  const logId = logIdQuery.value.trim();
  openAgentPanel(
    `[logId=${logId}] [模型=${selectedModel()}] 请分析该日志链路。`,
    [],
    apiGet(`/log-analysis?${new URLSearchParams({ logId, model: agentModelMode.value }).toString()}`)
  );
}

function openDetail(node) {
  detailNode.value = node;
  // 从节点入口进入后，将该节点作为后续默认分析范围。
  selectedIds.value = [node.id];
}

function closeDetail() {
  detailNode.value = null;
}

function openClusterAnalysis() {
  openNodeAnalysis();
}

function openNodeAnalysis() {
  openAgentPanel(
    `[machineId=${nodes.value.map((node) => node.machineId).join(',')}] [模型=${selectedModel()}] 请分析当前节点状态。`,
    nodes.value.map((node) => node.machineId),
    apiGet(`/node-analysis?${new URLSearchParams({ model: agentModelMode.value }).toString()}`)
  );
}

function closeAnalysis() {
  agentPanelOpen.value = false;
  stopAgentTimers();
}

function openAgentPanel(prompt, machineIds, request) {
  stopAgentTimers();
  agentPanelOpen.value = true;
  agentRunning.value = true;
  agentThinkingSeconds.value = 0;
  agentModelName.value = selectedModel();
  agentMessages.value = [
    {
      role: 'user',
      machineIds,
      model: selectedModel(),
      content: prompt
    },
    {
      role: 'assistant',
      machineIds,
      model: '',
      content: '',
      streaming: false
    }
  ];

  agentThinkingTimer = window.setInterval(() => {
    agentThinkingSeconds.value += 1;
  }, 1000);

  request
    .then((result) => {
      const assistantMessage = agentMessages.value[agentMessages.value.length - 1];
      assistantMessage.model = result.modelName || selectedModel();
      assistantMessage.content = result.analysis || result.llmSummary || result.conclusion || '服务分析失败';
      assistantMessage.streaming = false;
      agentModelName.value = result.modelName || selectedModel();
    })
    .catch(() => {
      const assistantMessage = agentMessages.value[agentMessages.value.length - 1];
      assistantMessage.content = '服务分析失败';
      assistantMessage.streaming = false;
    })
    .finally(() => {
      agentRunning.value = false;
      stopAgentTimers(false);
    });
}

function stopAgentTimers(clearRunning = true) {
  window.clearInterval(agentThinkingTimer);
  if (clearRunning) {
    agentRunning.value = false;
  }
}

function selectedModel() {
  return agentModelMode.value === 'auto' ? 'Auto' : 'deepseek-flash';
}

async function apiGet(path) {
  const response = await fetch(`${apiBaseUrl}${path}`);
  if (!response.ok) {
    throw new Error('api request failed');
  }
  return response.json();
}

async function loadNodes() {
  try {
    const records = await apiGet('/nodes');
    nodes.value = records.map((node) => ({
      id: `node-${node.machineId}`,
      machineId: node.machineId,
      serverName: node.serverName,
      ip: node.hostIp,
      port: node.port,
      status: node.status === 'RUNNING' ? 'normal' : 'offline',
      startTime: node.startTime || '-',
      uptime: node.uptimeSeconds == null ? '-' : `${node.uptimeSeconds}s`,
      nettyMode: node.nettyMode,
      bossThreads: node.bossThreadCount,
      workerThreads: node.workerThreadCount,
      messages: node.totalMessageCount || 0,
      connections: node.connectionCount || 0,
      cpu: Number(node.cpuUsage || 0),
      heapUsed: node.heapUsed == null ? 0 : Math.round(node.heapUsed / 1024 / 1024),
      heapMax: node.heapMax == null ? 0 : Math.round(node.heapMax / 1024 / 1024),
      threadCount: node.threadCount || 0,
      gcCount: node.gcCount || 0,
      gcTimeMs: node.gcTimeMs || 0,
      lastHeartbeat: node.lastHeartbeatTime || '-',
      lastError: node.lastErrorMessage || '-'
    }));
    apiError.value = '';
  } catch (error) {
    nodes.value = [];
    apiError.value = '读取节点数据失败';
  }
}

function closeOnEscape(event) {
  if (event.key === 'Escape') {
    closeDetail();
    closeAnalysis();
  }
}

onMounted(() => {
  window.addEventListener('keydown', closeOnEscape);
  loadNodes();
  refreshTimer = window.setInterval(refreshData, 30000);
});

onUnmounted(() => {
  window.removeEventListener('keydown', closeOnEscape);
  window.clearInterval(refreshTimer);
  stopAgentTimers();
});
</script>

<template>
  <div class="app-shell">
    <header class="top-nav">
      <div class="brand">
        <span class="brand-mark"><BarChart3 :size="16" /></span>
        <span>推送节点监控平台</span>
      </div>
      <nav class="nav-menu">
        <button
          v-for="item in navItems"
          :key="item"
          class="nav-item"
          :class="{ active: item === activePage }"
          type="button"
          @click="switchPage(item)"
        >
          {{ item }}
        </button>
      </nav>
      <div class="operator">
        <span class="avatar"><UserRound :size="15" /></span>
        <span>王壹硕</span>
      </div>
    </header>

    <div class="breadcrumb">
      {{ activePage }} / <strong>{{ activePage === '日志中心' ? 'LogID 日志' : '节点列表' }}</strong>
    </div>
    <div v-if="apiError" class="api-error">{{ apiError }}</div>

    <main v-if="activePage === '节点管理'" class="main-content">
      <section class="stats-row" aria-label="节点统计">
        <article v-for="item in stats" :key="item.label" class="stat-card">
          <span class="stat-icon" :class="item.tone">
            <component :is="item.icon" :size="22" />
          </span>
          <div>
            <div class="stat-label">{{ item.label }}</div>
            <div class="stat-value">
              {{ item.value }}
              <span class="stat-unit">{{ item.unit }}</span>
            </div>
          </div>
        </article>
      </section>

      <section class="panel">
        <div class="panel-header">
          <div class="panel-title">节点拓扑图</div>
          <div class="header-tools">
            <button class="btn btn-text" type="button" @click="openClusterAnalysis">
              {{ agentRunning ? '分析中...' : '智能分析' }}
            </button>
            <span>当前实例节点状态</span>
          </div>
        </div>
        <div class="topology">
          <svg class="topology-svg" viewBox="0 0 900 300" role="img" aria-label="推送节点状态">
            <g
              v-for="node in nodes"
              :key="node.id"
              class="node-group"
              @click="openLog(node)"
            >
              <circle
                class="node-circle"
                :cx="nodePositions[node.id].x"
                :cy="nodePositions[node.id].y"
                r="42"
                :stroke="statusMap[node.status].color"
              />
              <circle
                class="node-status-dot"
                :cx="nodePositions[node.id].x + 30"
                :cy="nodePositions[node.id].y - 30"
                r="5"
                :fill="statusMap[node.status].color"
                :style="{ animation: node.status === 'offline' ? 'none' : undefined }"
              />
              <text class="node-label" :x="nodePositions[node.id].x" :y="nodePositions[node.id].y - 5">
                machineId={{ node.machineId }}
              </text>
              <text class="node-sub-label" :x="nodePositions[node.id].x" :y="nodePositions[node.id].y + 12">
                :{{ node.port }}
              </text>
            </g>
          </svg>

          <div class="topology-legend">
            <div class="legend-item"><span class="legend-dot normal"></span>正常运行</div>
            <div class="legend-item"><span class="legend-dot abnormal"></span>异常告警</div>
            <div class="legend-item"><span class="legend-dot offline"></span>离线状态</div>
          </div>

        </div>
      </section>

      <section class="panel">
        <div class="panel-header">
          <div class="panel-title">节点列表</div>
          <div class="header-tools">共 {{ filteredNodes.length }} 个节点</div>
        </div>

        <div class="table-toolbar">
          <button class="btn btn-default" type="button" @click="refreshData">
            <RefreshCw :size="15" :class="{ spinning: refreshing }" />刷新
          </button>
          <div class="toolbar-right">
            <select v-model="statusFilter" class="select" aria-label="节点状态筛选">
              <option value="all">全部状态</option>
              <option value="normal">正常</option>
              <option value="abnormal">异常</option>
              <option value="offline">离线</option>
            </select>
            <label class="search-box">
              <Search class="search-icon" :size="15" />
              <input v-model="keyword" class="search-input" type="search" placeholder="搜索节点ID/IP/端口" />
            </label>
          </div>
        </div>

        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th class="checkbox-col">
                  <input type="checkbox" :checked="allFilteredSelected" @change="toggleSelectAll" />
                </th>
                <th>节点ID</th>
                <th>机器ID</th>
                <th>IP地址</th>
                <th>端口</th>
                <th>状态</th>
                <th>消息数量</th>
                <th>连接数</th>
                <th>CPU</th>
                <th>最后心跳</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="node in filteredNodes" :key="node.id">
                <td class="checkbox-col">
                  <input v-model="selectedIds" type="checkbox" :value="node.id" />
                </td>
                <td class="node-id">{{ node.id }}</td>
                <td>{{ node.machineId }}</td>
                <td>{{ node.ip }}</td>
                <td>{{ node.port }}</td>
                <td>
                  <span class="status-tag" :class="statusMap[node.status].className">
                    {{ statusMap[node.status].text }}
                  </span>
                </td>
                <td>{{ node.messages.toLocaleString() }}</td>
                <td>{{ node.connections.toLocaleString() }}</td>
                <td>
                  <div class="metric-bar">
                    <div class="bar-track">
                      <div class="bar-fill" :class="cpuClass(node.cpu)" :style="{ width: `${node.cpu}%` }"></div>
                    </div>
                    <span class="bar-text">{{ node.cpu }}%</span>
                  </div>
                </td>
                <td style="color:#86909c;">{{ node.lastHeartbeat }}</td>
                <td>
                  <div class="action-cell">
                    <button class="btn btn-text" type="button" @click="openLog(node)">日志</button>
                    <button class="btn btn-text" type="button" @click="openDetail(node)">详情</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

      </section>
    </main>

    <main v-else class="main-content log-center-page">
      <section class="log-center-header">
        <div>
          <div class="log-center-title"><FileSearch :size="20" />LogID 日志</div>
          <div class="log-center-subtitle">按链路 LogID 或节点机器 ID 检索推送中台日志</div>
        </div>
        <button class="btn btn-default" type="button" @click="refreshData">
          <RefreshCw :size="15" :class="{ spinning: refreshing }" />刷新
        </button>
      </section>

      <section class="log-query-panel">
        <div class="query-row">
          <select v-model="logQueryMode" class="select query-mode-select" aria-label="日志查询类型">
            <option value="logId">LogID</option>
            <option value="machineId">机器ID</option>
          </select>
          <label class="query-input">
            <span class="required-mark">*</span>
            <input
              v-if="logQueryMode === 'logId'"
              v-model="logIdQuery"
              type="search"
              placeholder="输入真实日志链路 LogID"
              @keyup.enter="searchLogs"
            />
            <input
              v-else
              v-model="machineIdQuery"
              type="search"
              inputmode="numeric"
              placeholder="输入机器ID，例如 3"
              @keyup.enter="searchLogs"
            />
          </label>
          <button class="btn btn-default" type="button" @click="analyzeLogCenter">智能分析</button>
          <button class="btn btn-primary" type="button" @click="searchLogs"><Search :size="15" />搜索</button>
        </div>
        <div class="query-row query-filters">
          <span class="filter-label">日志等级</span>
          <button
            v-for="level in ['all', 'INFO', 'WARN', 'ERROR']"
            :key="level"
            class="level-filter"
            :class="{ active: logLevelFilter === level, [level]: level !== 'all' }"
            type="button"
            @click="setLogLevel(level)"
          >
            {{ level === 'all' ? '全部' : level }}
          </button>
          <label class="search-box log-center-keyword">
            <Search class="search-icon" :size="15" />
            <input v-model="logSearchKeyword" class="search-input" type="search" placeholder="搜索日志内容 / logger" />
          </label>
        </div>
      </section>

      <section class="log-result-panel">
        <div class="result-summary">
          <span>搜索结果</span>
          <strong>{{ logCenterResults.length }} 条</strong>
          <span class="result-scope">
            {{ logQueryMode === 'logId' ? `LogID = ${logIdQuery || '全部'}` : `machine_id = ${machineIdQuery || '全部'}` }}
          </span>
        </div>
        <div class="log-result-head">
          <span>PSM / Logger</span>
          <span>时间</span>
          <span>机器ID</span>
          <span>IP</span>
        </div>
        <div v-if="logCenterResults.length === 0" class="empty-state">暂无匹配日志</div>
        <article v-for="log in logCenterResults" :key="log.id" class="log-result-item">
          <button class="log-result-meta" type="button" @click="toggleLogDetail(log.id)">
            <component :is="expandedLogIds.includes(log.id) ? ChevronDown : ChevronRight" :size="16" />
            <span class="log-psm">{{ log.logger }}</span>
            <time>{{ log.time }}</time>
            <span>{{ log.machineId }}</span>
            <span>{{ log.hostIp }}</span>
          </button>
          <div v-if="expandedLogIds.includes(log.id)" class="log-content">
            <span class="log-level" :class="log.level">{{ log.level }}</span>
            <code>logId={{ log.logId }} | {{ log.message }}</code>
          </div>
        </article>
      </section>
    </main>

    <div v-if="detailNode" class="modal-overlay" @click.self="closeDetail">
      <div class="modal detail-modal">
        <div class="modal-header">
          <div class="modal-title">节点详情 - {{ detailNode.id }}</div>
          <button class="icon-btn" type="button" title="关闭" @click="closeDetail"><X :size="16" /></button>
        </div>
        <div class="modal-body detail-body">
          <section class="detail-section">
            <h3>基础信息</h3>
            <div class="detail-grid">
              <div><span>机器ID</span><strong>{{ detailNode.machineId }}</strong></div>
              <div><span>服务名</span><strong>{{ detailNode.serverName }}</strong></div>
              <div><span>节点IP</span><strong>{{ detailNode.ip }}</strong></div>
              <div><span>监听端口</span><strong>{{ detailNode.port }}</strong></div>
              <div><span>运行状态</span><strong>{{ statusMap[detailNode.status].text }}</strong></div>
              <div><span>启动时间</span><strong>{{ detailNode.startTime }}</strong></div>
              <div><span>运行时长</span><strong>{{ detailNode.uptime }}</strong></div>
              <div><span>Netty模式</span><strong>{{ detailNode.nettyMode }}</strong></div>
            </div>
          </section>
          <section class="detail-section">
            <h3>运行指标</h3>
            <div class="detail-grid">
              <div><span>当前连接数</span><strong>{{ detailNode.connections.toLocaleString() }}</strong></div>
              <div><span>累计消息数</span><strong>{{ detailNode.messages.toLocaleString() }}</strong></div>
              <div><span>CPU</span><strong>{{ detailNode.cpu }}%</strong></div>
              <div><span>堆内存</span><strong>{{ detailNode.heapUsed }} / {{ detailNode.heapMax }} MB</strong></div>
              <div><span>线程数</span><strong>{{ detailNode.threadCount }}</strong></div>
              <div><span>GC次数</span><strong>{{ detailNode.gcCount }}</strong></div>
              <div><span>GC耗时</span><strong>{{ detailNode.gcTimeMs }} ms</strong></div>
            </div>
          </section>
          <section class="detail-section">
            <h3>Netty与异常</h3>
            <div class="detail-grid">
              <div><span>Boss线程数</span><strong>{{ detailNode.bossThreads }}</strong></div>
              <div><span>Worker线程数</span><strong>{{ detailNode.workerThreads }}</strong></div>
              <div><span>最近心跳</span><strong>{{ detailNode.lastHeartbeat }}</strong></div>
              <div><span>最近错误</span><strong>{{ detailNode.lastError }}</strong></div>
            </div>
          </section>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" type="button" @click="closeDetail">关闭</button>
          <button class="btn btn-default" type="button" @click="openNodeAnalysis(detailNode)">智能分析</button>
          <button class="btn btn-primary" type="button" @click="openLog(detailNode)">查看日志</button>
        </div>
      </div>
    </div>

    <div v-if="agentPanelOpen" class="modal-overlay agent-overlay" @click.self="closeAnalysis">
      <div class="modal agent-modal">
        <div class="modal-header">
          <div class="modal-title">排障助手</div>
          <div class="agent-runtime">
            <label class="agent-model-select">
              <span>模型</span>
              <select v-model="agentModelMode" :disabled="agentRunning">
                <option value="auto">Auto</option>
                <option value="deepseek-flash">deepseek-flash</option>
              </select>
            </label>
            <span>使用：{{ agentModelName }}</span>
            <span>已思考 {{ agentThinkingSeconds }}s</span>
            <span v-if="agentRunning" class="agent-running-dot"></span>
          </div>
          <button class="icon-btn" type="button" title="关闭" @click="closeAnalysis"><X :size="16" /></button>
        </div>
        <div class="modal-body agent-body">
          <div class="agent-thread">
            <div
              v-for="(message, index) in agentMessages"
              :key="index"
              class="agent-message"
              :class="message.role"
            >
              <div class="agent-message-head">
                <span>{{ message.role === 'user' ? '你' : '排障助手' }}</span>
                <em>{{ message.machineIds.length ? `machineId=${message.machineIds.join(',')} · ` : '' }}{{ message.model }}</em>
              </div>
              <pre>{{ message.content }}</pre>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" type="button" @click="closeAnalysis">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>
