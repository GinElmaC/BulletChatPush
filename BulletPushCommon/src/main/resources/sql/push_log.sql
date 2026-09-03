CREATE TABLE push_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',

    log_time DATETIME(3) NOT NULL COMMENT '日志发生时间',
    level TINYINT NOT NULL COMMENT '日志等级: 1-INFO, 2-WARN, 3-ERROR',
    level_name VARCHAR(10) NOT NULL COMMENT '日志等级名称: INFO/WARN/ERROR',

    server_name VARCHAR(64) NOT NULL DEFAULT 'BulletChat_Push' COMMENT '服务名',
    machine_id INT DEFAULT NULL COMMENT '推送节点机器ID',
    node_name VARCHAR(64) DEFAULT NULL COMMENT '节点名称',
    host_ip VARCHAR(64) DEFAULT NULL COMMENT '节点IP',

    logger_name VARCHAR(128) DEFAULT NULL COMMENT '日志所属类或模块',
    thread_name VARCHAR(128) DEFAULT NULL COMMENT '输出日志的线程名',
    source_file_path VARCHAR(512) DEFAULT NULL COMMENT '触发日志的源码文件路径',
    source_line INT DEFAULT NULL COMMENT '触发日志的源码行号',

    trace_id VARCHAR(64) DEFAULT NULL COMMENT '日志链路LogID/链路追踪ID',
    msg_id VARCHAR(64) DEFAULT NULL COMMENT '消息ID',
    uid BIGINT DEFAULT NULL COMMENT '用户ID',
    room_id BIGINT DEFAULT NULL COMMENT '直播间ID',

    message TEXT NOT NULL COMMENT '日志内容',
    throwable MEDIUMTEXT DEFAULT NULL COMMENT '异常堆栈',
    context_json JSON DEFAULT NULL COMMENT '扩展上下文',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间',

    PRIMARY KEY (id),
    KEY idx_node_time (machine_id, log_time),
    KEY idx_level_time (level, log_time),
    KEY idx_trace_id (trace_id),
    KEY idx_msg_id (msg_id),
    KEY idx_source_location (source_file_path(191), source_line),
    KEY idx_log_time (log_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送中台日志表';
