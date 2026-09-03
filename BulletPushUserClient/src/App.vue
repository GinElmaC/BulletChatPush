<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { CircleAlert, MessageCircle, RefreshCw, Send, Server, Wifi, WifiOff } from 'lucide-vue-next';

const apiBaseUrl = import.meta.env.VITE_PUSH_ADMIN_API_BASE_URL || 'http://localhost:9090/admin/api';
const nodes = ref([]);
const selectedEndpoint = ref('');
const connectionState = ref('disconnected');
const errorMessage = ref('');
const roomId = ref('');
const content = ref('');
const userId = ref('');
const sentMessages = ref([]);

let socket;

const connected = computed(() => connectionState.value === 'connected');
const connectionText = computed(() => {
  if (connectionState.value === 'connecting') {
    return '连接中';
  }
  if (connectionState.value === 'connected') {
    return '已连接';
  }
  return '未连接';
});

async function loadNodesAndConnect() {
  disconnect();
  errorMessage.value = '';
  try {
    const response = await fetch(`${apiBaseUrl}/client-nodes`);
    if (!response.ok) {
      throw new Error('读取节点失败');
    }
    nodes.value = await response.json();
    if (nodes.value.length === 0) {
      throw new Error('未配置可用消息节点');
    }
    const node = nodes.value[Math.floor(Math.random() * nodes.value.length)];
    selectedEndpoint.value = node.endpoint;
    connect(node.endpoint);
  } catch (error) {
    connectionState.value = 'disconnected';
    errorMessage.value = error.message || '连接消息节点失败';
  }
}

function connect(endpoint) {
  connectionState.value = 'connecting';
  socket = new WebSocket(endpoint);
  socket.onopen = () => {
    connectionState.value = 'connected';
    errorMessage.value = '';
  };
  socket.onmessage = (event) => {
    try {
      const response = JSON.parse(event.data);
      sentMessages.value.unshift({
        messageId: response.messageId || '-',
        logId: response.logId || '-',
        status: response.type,
        message: response.content || response.message
      });
    } catch {
      errorMessage.value = '消息节点返回了无法识别的数据';
    }
  };
  socket.onerror = () => {
    errorMessage.value = '消息节点连接异常';
  };
  socket.onclose = () => {
    connectionState.value = 'disconnected';
  };
}

function disconnect() {
  if (socket) {
    socket.onclose = null;
    socket.close();
    socket = null;
  }
  connectionState.value = 'disconnected';
}

function sendMessage() {
  if (!connected.value) {
    errorMessage.value = '消息节点未连接';
    return;
  }
  const numericRoomId = Number(roomId.value);
  if (!Number.isSafeInteger(numericRoomId) || numericRoomId <= 0 || !content.value.trim()) {
    errorMessage.value = '请填写有效房间 ID 和消息内容';
    return;
  }
  const numericUserId = userId.value.trim() ? Number(userId.value) : undefined;
  if (userId.value.trim() && (!Number.isSafeInteger(numericUserId) || numericUserId <= 0)) {
    errorMessage.value = '用户 ID 必须是正整数';
    return;
  }
  const messageId = crypto.randomUUID();
  socket.send(JSON.stringify({
    type: 'CHAT_MESSAGE',
    messageId,
    userId: numericUserId,
    roomId: numericRoomId,
    content: content.value.trim(),
    sentAt: Date.now()
  }));
  content.value = '';
  errorMessage.value = '';
}

onMounted(loadNodesAndConnect);
onUnmounted(disconnect);
</script>

<template>
  <main class="client-shell">
    <section class="client-header">
      <div class="brand">
        <span class="brand-icon"><MessageCircle :size="20" /></span>
        <div>
          <h1>弹幕消息客户端</h1>
          <p>向房间发送消息</p>
        </div>
      </div>
      <div class="connection" :class="connectionState">
        <component :is="connected ? Wifi : WifiOff" :size="16" />
        <span>{{ connectionText }}</span>
      </div>
    </section>

    <section class="connection-panel">
      <div>
        <span class="field-label">消息节点</span>
        <strong>{{ selectedEndpoint || '未发现节点' }}</strong>
      </div>
      <button class="icon-button" type="button" title="重新选择节点" @click="loadNodesAndConnect">
        <RefreshCw :size="17" />
      </button>
    </section>

    <section v-if="errorMessage" class="error-panel">
      <CircleAlert :size="16" />
      <span>{{ errorMessage }}</span>
    </section>

    <section class="message-panel">
      <label>
        <span class="field-label">房间 ID</span>
        <input v-model="roomId" inputmode="numeric" type="text" placeholder="输入房间 ID" />
      </label>
      <label>
        <span class="field-label">用户 ID</span>
        <input v-model="userId" inputmode="numeric" type="text" placeholder="可选" />
      </label>
      <label class="content-field">
        <span class="field-label">消息内容</span>
        <textarea v-model="content" rows="5" placeholder="输入要发送的消息"></textarea>
      </label>
      <button class="send-button" type="button" :disabled="!connected" @click="sendMessage">
        <Send :size="17" />发送消息
      </button>
    </section>

    <section class="result-panel">
      <div class="result-title"><Server :size="16" />发送结果</div>
      <div v-if="sentMessages.length === 0" class="empty-state">暂无服务端确认结果</div>
      <article v-for="message in sentMessages" :key="message.messageId" class="result-row">
        <div>
          <strong>{{ message.status }}</strong>
          <p>{{ message.message }}</p>
        </div>
        <span>messageId={{ message.messageId }}</span>
        <span>logId={{ message.logId }}</span>
      </article>
    </section>
  </main>
</template>
