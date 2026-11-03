from pydantic import BaseModel
from typing import Optional, Dict, Any

class Alert(BaseModel):
    id: str
    severity: str
    title: str
    description: str
    serviceName: str
    host: Optional[str] = None
    detail: Optional[str] = None
    # 只读诊断标记：预测性预警等告警不许走高危 executor/审批，只输出诊断报告。
    readonly: Optional[bool] = False

class AgentMessage(BaseModel):
    type: str
    taskId: str
    content: str
    stepName: Optional[str] = None

class ToolExecutionRequest(BaseModel):
    toolName: str
    parameters: Dict[str, Any]
    contextId: str

class ToolExecutionResult(BaseModel):
    success: bool
    message: str
    data: Optional[Dict[str, Any]] = None