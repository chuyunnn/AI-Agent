# 高校科研知识库与AI Agent智能检索平台 - searchSmart

searchSmart⾯向⾼校科研团队，整合实验记录、论⽂、合同等多元⽂档资源，构建智能化学术知识库，解决研究资料分散、论⽂检索效率低、实验⽂档权限混乱等痛点，提供语义检索和智能问答⼀体化服务。
该平台围绕“文档上传 -> 解析切片 -> 向量化 -> 混合检索 -> 大模型问答”构建了一套完整的 RAG 链路。项目后端使用 Spring Boot 3 + MySQL + Redis + Kafka + Elasticsearch + MinIO，前端使用 Vue 3 + TypeScript + Vite。

当前仓库同时包含两套聊天能力：

- 旧链路：基于 `ChatHandler + DeepSeekClient + Redis 历史 + HybridSearchService`
- 新链路：基于 Spring AI 的 Agent 聊天链路，支持 `ChatClient`、`ChatMemory`、工具调用、最多 3 轮 think-loop execute 循环，以及 `lastChatResponse` 记忆快照

## 1. 功能概览

### 1.1 现有核心能力

- 分片上传与断点续传
- MinIO 文件存储
- Kafka 异步文件处理
- Apache Tika 文档解析
- 基于语义的文本切片
- Embedding 向量化
- Elasticsearch 混合检索
- 基于组织标签和公开范围的权限过滤
- WebSocket 实时问答
- JWT 登录认证与多租户组织隔离

### 1.2 新增 Spring AI Agent 能力

- 新增独立 Agent WebSocket 链路：`/agent/{token}`
- 新增 Agent 停止指令 token 接口：`/api/v1/agent/websocket-token`
- 使用 Spring AI `ChatClient` 管理模型调用
- 使用 Redis 自定义 `ChatMemoryRepository`
- 支持工具化知识检索：`searchKnowledge(query, topK, fileMd5?, orgTag?)`
- 检索工具固定默认 `topK=10`，最终最多保留 10 条结果
- 支持最多 3 轮工具循环，避免无限执行
- 将最终回答、检索结果、引用、工具执行摘要写入 `lastChatResponse`
- 新增独立 JPA 审计表 `agent_conversation_audit`

## 2. 技术栈

### 2.1 后端

- Java 17
- Spring Boot 3.4.2
- Spring Security + JWT
- Spring Data JPA
- Spring Data Redis
- Spring WebSocket
- Spring WebFlux
- Spring AI 1.0.0
- Maven
- MySQL 8
- Redis 7/8
- Kafka 3.x
- Elasticsearch 8.10.x
- MinIO
- Apache Tika

### 2.2 前端

- Node.js 18.20.0+
- pnpm 8.7.0+
- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Naive UI

## 3. 项目结构

### 3.1 后端结构

```text
src/main/java/com/yizhaoqi/smartpai/
├── agent/              # 新增的 Spring AI Agent 相关代码
│   ├── dto/
│   ├── model/
│   ├── repository/
│   └── service/
├── client/             # DeepSeek / Embedding 外部客户端
├── config/             # Spring / Redis / WebSocket / AI 配置
├── consumer/           # Kafka 消费者
├── controller/         # REST API
├── entity/             # ES/请求响应 DTO
├── handler/            # WebSocket 处理器
├── model/              # JPA 实体
├── repository/         # Repository
├── service/            # 原有业务服务
└── utils/              # 工具类
```

### 3.2 前端结构

```text
frontend/
├── src/
│   ├── assets/
│   ├── components/
│   ├── layouts/
│   ├── router/
│   ├── service/
│   ├── store/
│   └── views/
├── packages/
└── package.json
```

## 4. 核心链路说明

### 4.1 原始 RAG 处理链路

1. 前端调用 `/api/v1/upload/chunk` 上传分片
2. 后端将分片写入 MinIO，并把分片状态记录到 Redis / MySQL
3. 前端调用 `/api/v1/upload/merge` 合并文件
4. 合并完成后向 Kafka 投递 `FileProcessingTask`
5. `FileProcessingConsumer` 消费任务
6. `ParseService` 使用 Tika 解析文档并做语义切片
7. `VectorizationService` 调用 Embedding 模型生成向量
8. `ElasticsearchService` 将向量与文本写入 ES
9. `HybridSearchService` 在查询时执行文本 + 向量混合检索
10. 聊天链路将检索结果拼入 prompt，由模型生成最终答案

### 4.2 新增 Agent 链路

1. 前端通过 `/agent/{token}` 建立 WebSocket 连接
2. `AgentWebSocketHandler` 提取 JWT 中的用户名作为 userId
3. `AgentChatService` 获取或创建 `conversationId`
4. Redis `ChatMemory` 读取最近 20 条消息
5. `lastChatResponse` 快照作为摘要上下文参与 prompt 构造
6. `ChatClient` 调用模型
7. 如果模型触发 `searchKnowledge` 工具：
   - 通过 `KnowledgeSearchTools` 复用 `HybridSearchService.searchWithPermission`
   - 默认取前 10 条结果
   - 将结果写入工具执行摘要与本轮上下文
8. 最多执行 3 轮工具循环
9. 生成最终答案后：
   - 更新 `ChatMemory`
   - 保存 `lastChatResponse`
   - 写入 `agent_conversation_audit`
10. 通过 WebSocket 返回兼容旧前端的消息结构：
   - `{"chunk":"..."}`
   - `{"type":"completion","status":"finished",...}`
   - `{"error":"..."}`
   - `{"type":"stop","message":"响应已停止",...}`

## 5. 关键配置文件

### 5.1 后端配置

- [application.yml](/Users/dcy/Documents/实习/PaiSmart-main/src/main/resources/application.yml)
- [application-dev.yml](/Users/dcy/Documents/实习/PaiSmart-main/src/main/resources/application-dev.yml)
- [application-docker.yml](/Users/dcy/Documents/实习/PaiSmart-main/src/main/resources/application-docker.yml)

### 5.2 Docker 编排

- [docker-compose.yaml](/Users/dcy/Documents/实习/PaiSmart-main/docs/docker-compose.yaml)

### 5.3 数据库脚本

- [ddl.sql](/Users/dcy/Documents/实习/PaiSmart-main/docs/databases/ddl.sql)

## 6. 启动前准备

建议优先使用 Docker 拉起基础中间件，再单独运行后端和前端。

### 6.1 必备软件

- Java 17
- Maven 3.8.6+
- Node.js 18.20.0+
- pnpm 8.7.0+
- Docker Desktop

### 6.2 必备中间件

- MySQL
- Redis
- Kafka
- Elasticsearch
- MinIO

### 6.3 外部 AI 配置

需要至少准备以下配置：

- `deepseek.api.url`
- `deepseek.api.key`
- `deepseek.api.model`
- `embedding.api.url`
- `embedding.api.key`
- `embedding.api.model`

如果只想本地先验证工程启动，可以先把 key 留空，但真正调用聊天和向量化时会失败。

## 7. 推荐启动方式：Docker + 本地后端 + 本地前端

### 7.1 启动基础服务

在项目根目录执行：

```bash
cd docs
docker compose up -d
```

默认会启动：

- MySQL: `3306`
- Redis: `6379`
- Kafka: `9092`
- Elasticsearch: `9200`
- MinIO API: `19000`
- MinIO Console: `19001`

### 7.2 检查容器状态

```bash
docker compose ps
```

### 7.3 初始化数据库

如果表没有自动创建，先执行：

```bash
mysql -uroot -p < docs/databases/ddl.sql
```

也可以依赖 JPA 的 `ddl-auto=update` 自动建表，但首次部署仍建议保留 SQL 脚本。

### 7.4 修改后端配置

如果你打算配合 `docker-compose` 使用，建议优先检查 [application-docker.yml](/Users/dcy/Documents/实习/PaiSmart-main/src/main/resources/application-docker.yml)：

- MySQL root 密码
- Redis 密码
- Elasticsearch 用户名与密码
- DeepSeek / Embedding API Key
- MinIO 访问地址

### 7.5 启动后端

回到项目根目录：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=docker
```

后端默认端口：

```text
http://localhost:8081
```

### 7.6 启动前端

新开一个终端：

```bash
cd frontend
pnpm install
pnpm dev
```

前端使用 `vite --mode test`，具体 API 地址请结合前端环境配置确认。

## 8. 纯本地启动方式

如果你不使用 Docker，需要自行保证本机或局域网中间件都已启动，并把连接信息与 [application.yml](/Users/dcy/Documents/实习/PaiSmart-main/src/main/resources/application.yml) 或 [application-dev.yml](/Users/dcy/Documents/实习/PaiSmart-main/src/main/resources/application-dev.yml) 对齐。

### 8.1 本地后端配置重点

`application.yml` 当前关注项：

- MySQL: `jdbc:mysql://localhost:3306/PaiSmart`
- Redis: `localhost:6379`
- Kafka: `127.0.0.1:9092`
- Elasticsearch: `localhost:9200`
- MinIO: `http://localhost:9000`
- 后端端口: `8081`

注意：

- `application.yml` 中 ES 使用的是 `https`
- `application-dev.yml` / `application-docker.yml` 中 ES 使用的是 `http`
- 这几个 profile 的密码和端口并不完全一样，启动前一定要确认选用的是哪套配置

### 8.2 本地启动后端

```bash
mvn spring-boot:run
```

或者：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 8.3 本地启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

## 9. 常用开发命令

### 9.1 后端

```bash
# 编译
mvn test-compile

# 运行测试
mvn test

# 打包
mvn clean package

# 指定本地可写仓库，适合受限环境
mvn -Dmaven.repo.local=/tmp/codex-m2 test
```

### 9.2 前端

```bash
cd frontend

# 安装依赖
pnpm install

# 开发模式
pnpm dev

# 类型检查
pnpm typecheck

# Lint
pnpm lint

# 生产构建
pnpm build
```

## 10. 新增 Agent 能力使用说明

### 10.1 WebSocket 路径

- 旧聊天：`/chat/{token}`
- 新 Agent：`/agent/{token}`

### 10.2 获取停止指令 token

- 旧聊天：`GET /api/v1/chat/websocket-token`
- 新 Agent：`GET /api/v1/agent/websocket-token`

### 10.3 Agent 当前行为

- 仅暴露一个工具：`searchKnowledge`
- 默认最多检索 10 条结果
- 检索结果会参与最终回答生成
- 最多执行 3 轮工具循环
- 结果写入 Redis 会话记忆和 `lastChatResponse`
- 最终审计落到 `agent_conversation_audit`

### 10.4 Agent 相关代码入口

- [AgentChatService.java](/Users/dcy/Documents/实习/PaiSmart-main/src/main/java/com/yizhaoqi/smartpai/agent/service/AgentChatService.java)
- [KnowledgeSearchTools.java](/Users/dcy/Documents/实习/PaiSmart-main/src/main/java/com/yizhaoqi/smartpai/agent/service/KnowledgeSearchTools.java)
- [AgentModelRouter.java](/Users/dcy/Documents/实习/PaiSmart-main/src/main/java/com/yizhaoqi/smartpai/agent/service/AgentModelRouter.java)
- [AgentPromptService.java](/Users/dcy/Documents/实习/PaiSmart-main/src/main/java/com/yizhaoqi/smartpai/agent/service/AgentPromptService.java)
- [AgentWebSocketHandler.java](/Users/dcy/Documents/实习/PaiSmart-main/src/main/java/com/yizhaoqi/smartpai/handler/AgentWebSocketHandler.java)

## 11. 启动后如何验证

### 11.1 检查后端是否启动

可以先检查端口：

```bash
lsof -nP -iTCP:8081 -sTCP:LISTEN
```

### 11.2 检查基础服务端口

```bash
lsof -nP -iTCP:3306 -iTCP:6379 -iTCP:9092 -iTCP:9200 -iTCP:19000 -iTCP:19001 -sTCP:LISTEN
```

### 11.3 检查 Elasticsearch

```bash
curl http://localhost:9200
```

### 11.4 检查 MinIO

```bash
curl http://localhost:19000/minio/health/live
```

### 11.5 检查 Redis

```bash
redis-cli ping
```

## 12. 已实现的重要测试

本次新增 Agent 相关已补充并验证：

- `KnowledgeSearchToolsTest`
- `AgentPromptServiceTest`
- `AgentConversationStateServiceTest`

执行方式：

```bash
mvn -q -Dmaven.repo.local=/tmp/codex-m2 \
  -Dtest=KnowledgeSearchToolsTest,AgentPromptServiceTest,AgentConversationStateServiceTest test
```

## 13. 常见问题

### 13.1 Maven 用的不是 Java 17

即使你系统装了 Java 17，`mvn -version` 也可能指向别的 JDK。项目建议统一使用 Java 17。

可以通过以下方式确认：

```bash
java -version
mvn -version
echo $JAVA_HOME
```

如果 Maven 没有走 Java 17，请把 `JAVA_HOME` 切到 JDK 17 再启动。

### 13.2 前端起不来

优先检查：

- Node 版本是否达到 `18.20.0+`
- `pnpm` 是否已安装
- `frontend` 下依赖是否已安装

### 13.3 后端启动成功但问答失败

优先检查：

- DeepSeek API Key 是否填写
- Embedding API Key 是否填写
- Elasticsearch 是否启动
- Kafka 是否启动
- MinIO 是否启动

### 13.4 Agent WebSocket 连得上但没有返回

优先检查：

- JWT 是否有效
- `searchKnowledge` 工具是否能查到结果
- 外部模型服务是否可访问
- Redis 中是否能正常写入会话记忆

## 14. 建议的本地开发顺序

1. 先启动 Docker 基础服务
2. 确认数据库、Redis、Kafka、ES、MinIO 端口可用
3. 填写 AI 配置
4. 启动后端
5. 启动前端
6. 先验证上传与检索
7. 再验证旧聊天 `/chat/{token}`
8. 最后验证新 Agent `/agent/{token}`

## 15. 许可证

本项目仓库内已有 [LICENSE](/Users/dcy/Documents/实习/PaiSmart-main/LICENSE)，如需对外分发或商用，请自行确认依赖与资源的许可证约束。
