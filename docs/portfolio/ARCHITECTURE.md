# 球球 技术架构与接口边界

源码核对基线：`825f047f81b54195e246ea4b14dfb2b5ff180d8d`。

```mermaid
flowchart TD
  A["接收需求与历史"] --> B{"需要重新找商品"}
  B -->|是| C["检索商品与资料"]
  B -->|否| D["保留历史候选"]
  C --> E{"条件完整"}
  D --> E
  E -->|不足| F["澄清选项"]
  F --> A
  E -->|够用| G["解释和商品卡"]
```

- 客户端：`client/android/` 下 Kotlin Android 工程；会话交互、商品详情与 `CartStore.kt`。
- 服务：`server/api/routes/chat.py` 提供聊天入口，`server/agent/orchestrator.py` 处理路由、澄清和 SSE，`server/rag/retriever.py` 处理召回与排序。
- 数据：商品 ID 关联商品主数据与 FAQ/评价。前置硬约束与结构化会话一致性属于后续验证项，本地购物车不等于支付。

| 交接 | 产品契约 |
|---|---|
| Android → API | 会话、当次输入及可用上下文 |
| 召回 → 生成 | 候选商品 ID、结构字段、可用证据 |
| SSE → Android | 文本及结构化卡片/澄清事件；事件标签看代码 |
| 详情 → 本地购物车 | 选定的商品 ID、规格与数量，价格来自商品数据 |
