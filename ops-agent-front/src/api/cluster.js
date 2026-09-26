import request from '@/utils/request'

// 集群状态：namespace 下所有 Deployment 的副本/就绪/镜像，Dashboard 集群卡片用
export const getClusterStatus = () => {
  return request.get('/cluster/status')
}

// Prometheus 指标大盘数据源：各 Deployment 副本数、容器 CPU/内存
export const getClusterMetrics = () => {
  return request.get('/cluster/metrics')
}

// 基线偏离：各 Deployment 当前指标 vs 学习到的基线区间（均值 ± Nσ）
export const getClusterBaseline = () => {
  return request.get('/cluster/baseline')
}

// 服务拓扑关系图：Service → Pod（含就绪状态与断流标记）
// deployment 可选，传入时只看与该服务相关的部分，避免关系图糊成一团
export const getClusterTopology = (deployment) => {
  return request.get('/cluster/topology', { params: deployment ? { deployment } : {} })
}

// 故障影响面：受影响的流量入口、是否断流、异常实例清单
export const getClusterImpact = (deployment) => {
  return request.get('/cluster/impact', { params: { deployment } })
}
