import request from '@/utils/request'

// 集群状态：namespace 下所有 Deployment 的副本/就绪/镜像，Dashboard 集群卡片用
export const getClusterStatus = () => {
  return request.get('/cluster/status')
}
