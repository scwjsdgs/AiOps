# 贡献指南

感谢你对 AIOps 智能运维平台的关注！欢迎通过 Issue 或 Pull Request 参与贡献。

## 开发流程

### 1. Fork 本仓库

打开 [https://github.com/scwjsdgs/AiOps](https://github.com/scwjsdgs/AiOps)，点击右上角 `Fork`。

### 2. 克隆并配置上游仓库

```bash
git clone https://github.com/你的用户名/AiOps.git
cd AiOps
git remote add upstream https://github.com/scwjsdgs/AiOps.git
git remote -v
3. 同步主分支并创建功能分支
bash
git checkout main
git pull upstream main
git checkout -b feature/xxx
分支命名示例：

feature/alert-dashboard

feature/ai-log-analysis

fix/websocket-reconnect

docs/update-readme

4. 完成开发并提交
bash
git add .
git commit -m "feat: 新增告警自动分析接口"
5. 运行测试和检查
目前仓库已配置 GitHub Actions CI，会检查项目结构完整性。如果你新增了测试代码，请在 PR 描述中注明运行了哪些测试及结果。后续会逐步加入 Java、Python 和前端构建的自动化测试。

6. 推送分支并创建 PR
bash
git push origin feature/xxx
然后在 GitHub 上创建 Pull Request。

7. PR 标题规范
本项目采用 Conventional Commits 规范：

前缀	含义
feat:	新功能
fix:	修复 Bug
docs:	文档修改
refactor:	代码重构
test:	测试相关
chore:	构建、依赖、杂项
示例：

text
feat: 新增告警自动分析接口
fix: 修复 WebSocket 断连后不重连的问题
docs: 更新快速启动说明
8. 等待 Review 并持续修改
根据维护者意见继续提交，直到 PR 被合并。

9. 合并后同步主分支
bash
git checkout main
git pull upstream main
git push origin main
git branch -d feature/xxx
项目结构
text
AiOps/
├── .github/           # GitHub Actions CI 配置与 Issue/PR 模板
├── aiops-agent/       # Python AI 大脑（FastAPI + LangChain）
├── ops_agent/         # Java 后端（Spring Boot WebFlux）
├── ops-agent-front/   # Vue 3 前端
├── docs/              # 项目文档
├── CONTRIBUTING.md    # 贡献指南
├── SECURITY.md        # 安全策略
├── LICENSE            # 许可证
└── README.md
行为准则
尊重每一位贡献者

提交前先搜索是否已有相关 Issue

PR 尽量小而聚焦，避免一次提交大量不相关改动

许可证
本项目采用 Apache-2.0 许可证，贡献即表示你同意以该许可证发布你的代码。