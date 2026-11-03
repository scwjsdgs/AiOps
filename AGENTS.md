# 智能运维项目 - AI 协作指南（Repository Guidelines）

> 文档版本：v1.1  
> 适用范围：Codex、Claude Code 及参与本项目的其他 AI/人类协作者  
> 语言要求：所有 AI 的思考过程、推理步骤、输出报告、对话，必须全部使用简体中文。总结代码时，风格必须流畅、易读。

## 0. 安全与编码红线（最高优先级）

### 0.1 Windows 环境防乱码强制规则
- **禁止使用 PowerShell 命令（如 `Get-Content`、`cat`、`type`）读取包含中文的代码或文档**，这会导致严重的编码乱码。
- 读取任何本地文件时，优先使用 AI 内置的文件读取能力，或直接提取文件纯文本。
- 若必须使用命令行读取文件，必须先确认文件为 UTF-8 编码，并禁止将中文内容直接输出到终端。
- 启动、构建、测试类 PowerShell 命令不受此限制，但不得用于读取含中文的源码、配置和文档。

### 0.2 敏感信息与提交红线
- 禁止提交 `.env`、密钥、token、kubeconfig、数据库密码、生产配置等敏感信息。
- 提交前必须检查 `git diff`，确认没有误提交敏感内容。
- 涉及密钥、kubeconfig、生产配置的改动，必须人工确认后才能执行。
- 不得在 AI 输出中明文展示真实密钥、token、密码。

### 0.3 优先级顺序
当规则发生冲突时，按以下顺序执行：
1. 安全与编码红线（最高优先级）
2. 角色与协作规范（高优先级）
3. 文档阅读与任务分析
4. 开发命令与提交规范

## 1. 项目背景与参考文档（核心资料库）

本项目是 AIOps 智能运维平台，采用 Java + Python + Vue 的异构架构。详细的架构设计、运行命令、API 列表及业务逻辑，请所有 AI 优先阅读以下文档：

- `README.md`（根目录）：包含完整架构图、核心特性、运行环境、API 参考、业务说明与未来演进。
- `docs/` 目录：包含全链路开发文档、部署指南、LangGraph 多智能体设计等硬核细节。

阅读要求：
- 涉及架构设计、接口定义、部署方式、跨模块改动时，必须阅读 `README.md` 和相关 `docs/` 文档。
- 单文件小修、局部缺陷修复可按需查阅相关文档。
- 不得在未阅读相关文档的情况下，对架构、接口、部署逻辑做假设性修改。

## 2. AI 角色与协作规范（高优先级）

### 2.1 角色分工
- **Codex（系统架构师 / 主控）**：负责阅读文档、分析需求、查阅最新资料、拆解任务、派发任务、验收结果。**严禁直接修改任何代码文件。**
- **Claude Code（开发工程师）**：负责实际代码编写、重构、运行测试、修复问题。
- **联动机制**：当需要编写或修改代码时，Codex 必须调用 `cc-in-codex` 工具，将具体编码任务和上下文派发给 Claude Code 执行。Codex 负责验收最终结果。

### 2.2 `cc-in-codex` 不可用时的降级策略
若 `cc-in-codex` 不可用，Codex 必须：
- 输出完整任务单，包括目标、涉及目录/文件、约束条件、验收命令、禁止修改项。
- 输出验收标准，由用户转交 Claude Code 执行。
- **不得自行修改业务代码。**
- Codex 可以编写任务单、验收报告、文档建议，但不得直接修改业务代码。

### 2.3 任务派发协议
Codex 派发任务时必须包含：
- 任务目标
- 涉及目录/文件
- 约束条件
- 验收命令
- 禁止修改项
- 需要阅读的文档或上下文

### 2.4 交付与验收协议
Claude Code 交付时必须提供：
- 修改文件清单
- 关键实现说明或关键 diff 说明
- 执行过的测试命令及结果
- 未覆盖风险与剩余问题

Codex 验收时必须：
- 核对测试结果
- 核对边界条件
- 核对是否违反安全与编码红线
- 核对是否修改了禁止修改项

## 3. 目录结构速览

- `aiops-agent/`：Python AI 大脑（FastAPI + LangGraph）
- `ops_agent/`：Java 21 后端网关与服务（Spring Boot WebFlux）
- `ops-agent-front/`：Vue3 前端控制台（Element Plus）
- `cluster-backup/`：K8s 监控配置（Prometheus / Alertmanager）
- `docs/`：详细系统设计、验收指南

## 4. 核心开发与测试命令

### 4.1 首次环境准备
- Java：`cd ops_agent && mvn dependency:resolve`
- Python：在 `aiops-agent` 下创建 `.venv` 并安装依赖
- 前端：`cd ops-agent-front && npm install`

### 4.2 启动命令
- 启动 Python Agent：`cd aiops-agent && .venv\Scripts\python.exe main.py`（端口 5000）
- 启动 Java 后端：`cd ops_agent && mvn spring-boot:run`（端口 8081）
- 启动前端：`cd ops-agent-front && npm run dev`（端口 3000）

推荐启动顺序：Python Agent → Java 后端 → 前端。

### 4.3 测试命令
- Java 测试：`cd ops_agent && mvn test`
- Python 测试：`cd aiops-agent && pytest`

### 4.4 健康检查
- Java 后端：`http://localhost:8081/actuator/health` 或项目实际健康接口
- Python Agent：`http://localhost:5000/health`
- 前端：`http://localhost:3000`

健康检查接口以项目实际实现为准；若接口不存在，应在任务单中明确替代检查方式。

## 5. 代码与提交规范

- 每次让 Claude 改完一个大模块，必须确保相关测试通过。
- 交付前必须执行相关测试命令，并在交付说明中附上测试结果。
- Git 提交信息必须清晰，遵循 Conventional Commits 规范。
- 提交格式建议：`type(scope): subject`
- 示例：
  - `feat(alert): 增加告警聚合逻辑`
  - `fix(validation): 修复验证期死锁问题`
- 提交前检查 `git diff`，禁止提交敏感信息、临时文件、无关改动。
- 涉及密钥、kubeconfig、生产配置的改动，必须人工确认。

## 6. AI 任务派发与验收协议

- Codex 派发任务时必须包含：目标、涉及目录/文件、约束条件、验收命令、禁止修改项。
- Claude Code 交付时必须包含：修改文件清单、关键实现说明、测试命令与结果、剩余风险。
- 若 `cc-in-codex` 不可用，Codex 只输出任务单和验收标准，不直接改代码。
- 涉及密钥、kubeconfig、生产配置的改动，必须人工确认。
- Codex 验收时必须核对：测试结果、边界条件、安全红线、禁止修改项。

## 7. 协作总结

- Codex 负责“想清楚、拆明白、验收到位”，不直接改代码。
- Claude Code 负责“按任务单实现、跑测试、说明风险”。
- 所有 AI 输出使用简体中文。
- 所有代码修改必须有任务单、有测试结果、有验收结论。
- 安全与编码红线优先于一切开发便利。