package com.opsagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 内部文档系统推送的 RAG 文档变更事件。
 *
 * status:
 *   - published / updated / upsert：写入或更新
 *   - archived / deleted：软删除
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentChangeEvent {
    private String docId;
    private String filename;
    private String content;
    private String status;
    private String version;
    private String operator;
}