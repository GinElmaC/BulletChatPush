ALTER TABLE push_log
    ADD COLUMN source_file_path VARCHAR(512) DEFAULT NULL COMMENT '触发日志的源码文件路径' AFTER thread_name,
    ADD COLUMN source_line INT DEFAULT NULL COMMENT '触发日志的源码行号' AFTER source_file_path,
    ADD KEY idx_source_location (source_file_path(191), source_line);
