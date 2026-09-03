CREATE TABLE agent_conversation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    conversation_id VARCHAR(64) NOT NULL COMMENT 'Agent 会话ID',
    uid BIGINT NOT NULL COMMENT '发起分析的用户ID，单用户模式默认值为1',

    scope_type VARCHAR(16) NOT NULL COMMENT '分析范围类型: node/log',
    scope_value VARCHAR(512) NOT NULL COMMENT '固定分析范围描述',
    machine_ids_json JSON DEFAULT NULL COMMENT '节点分析范围机器ID列表',
    log_id VARCHAR(64) DEFAULT NULL COMMENT '日志分析固定LogID',
    model VARCHAR(64) NOT NULL COMMENT '会话固定模型路由配置',

    summary MEDIUMTEXT DEFAULT NULL COMMENT '超过最近十轮后的压缩短期记忆',
    turn_count INT NOT NULL DEFAULT 0 COMMENT '累计对话轮数',

    created_at DATETIME(3) NOT NULL COMMENT '会话创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '最后更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_id (conversation_id),
    KEY idx_uid_updated_at (uid, updated_at),
    KEY idx_log_id_updated_at (log_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent排障会话归档表';

CREATE TABLE agent_conversation_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    conversation_id VARCHAR(64) NOT NULL COMMENT 'Agent 会话ID',
    uid BIGINT NOT NULL COMMENT '发起分析的用户ID',
    turn_no INT NOT NULL COMMENT '会话轮次，从1开始递增',
    role VARCHAR(16) NOT NULL COMMENT '消息角色: user/assistant',
    content MEDIUMTEXT NOT NULL COMMENT '原始消息内容',
    model VARCHAR(64) DEFAULT NULL COMMENT '本轮实际使用模型',
    created_at DATETIME(3) NOT NULL COMMENT '消息生成时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_turn_role (conversation_id, turn_no, role),
    KEY idx_conversation_turn (conversation_id, turn_no),
    KEY idx_uid_created_at (uid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent排障会话消息审计表';
