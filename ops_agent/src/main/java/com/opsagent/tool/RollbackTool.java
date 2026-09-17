package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 版本回滚。
 *
 * fabric8 没有封装 kubectl rollout undo，但 rollout undo 的本质就是
 * 把 pod template 的镜像改回上一个版本的镜像，所以这里以 image tag 回滚实现：
 * 从同 namespace 下、属于该 deployment 的 ReplicaSet 里，
 * 按 deployment.kubernetes.io/revision 排序，取次新版本对应的镜像。
 *
 * Python 侧读 data.fromImage / data.toImage。
 */
@Component
public class RollbackTool extends KubernetesToolBase implements Tool {

    private static final String REVISION_ANNOTATION = "deployment.kubernetes.io/revision";
    private static final String CHANGE_CAUSE_ANNOTATION = "kubernetes.io/change-cause";

    @Override
    public String getName() {
        return "rollback";
    }

    @Override
    public String getDescription() {
        return "将 Deployment 回滚到上一个镜像版本（或指定的 Tag）。";
    }

    @Override
    public boolean isDangerous() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        String name = p.str("deployment", "service");
        if (name == null) {
            return Tool.failure("Missing 'deployment' parameter（也接受 'service'）", start);
        }
        String version = p.strOr("previous", "version", "target_version", "targetVersion");

        try {
            Deployment deployment = client.apps().deployments()
                    .inNamespace(namespace).withName(name).get();
            if (deployment == null) {
                return Tool.failure("Deployment not found: " + name + " (namespace=" + namespace + ")", start);
            }

            String currentImage = firstImage(deployment);
            if (currentImage == null) {
                return Tool.failure("Deployment " + name + " 没有可用的 container image，无法回滚", start);
            }

            String targetImage;
            String revisionLabel;
            if ("previous".equalsIgnoreCase(version)) {
                TreeMap<Integer, String> byRevision = collectRevisions(name, deployment);
                if (byRevision.size() < 2) {
                    return Tool.failure(
                            "找不到上一个版本：namespace " + namespace + " 下 " + name
                                    + " 只有 " + byRevision.size() + " 个历史 ReplicaSet。"
                                    + "可能从未发布过新版本，或 ReplicaSet 已被清理。",
                            start);
                }
                var previousEntry = byRevision.lowerEntry(byRevision.lastKey());
                targetImage = previousEntry.getValue();
                revisionLabel = String.valueOf(previousEntry.getKey());
            } else {
                // 非 previous：既接受完整 image，也接受只给一个 tag
                targetImage = version.contains("/") ? version : replaceTag(currentImage, version);
                revisionLabel = version;
            }

            if (targetImage.equals(currentImage)) {
                return Tool.failure("目标镜像与当前镜像相同（" + currentImage + "），无需回滚", start);
            }

            List<Container> containers = deployment.getSpec().getTemplate().getSpec().getContainers();
            containers.get(0).setImage(targetImage);

            // 写一条 change-cause，让这次回滚在 kubectl rollout history 里可追溯
            Map<String, String> annotations = deployment.getMetadata().getAnnotations();
            if (annotations == null) {
                annotations = new LinkedHashMap<>();
                deployment.getMetadata().setAnnotations(annotations);
            }
            String contextId = p.strOr("unknown", "contextId", "taskId");
            annotations.put(CHANGE_CAUSE_ANNOTATION,
                    "rollback by AIOps agent (contextId=" + contextId + "): " + currentImage + " -> " + targetImage);

            client.apps().deployments().inNamespace(namespace).resource(deployment).update();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deployment", name);
            data.put("namespace", namespace);
            data.put("fromImage", currentImage);
            data.put("toImage", targetImage);
            data.put("revision", revisionLabel);
            return Tool.success("Rolled back " + name + " from " + currentImage + " to " + targetImage, data, start);
        } catch (Exception e) {
            return Tool.failure("Rollback failed: " + e.getMessage(), start);
        }
    }

    /** 收集该 deployment 的所有历史版本：revision -> image。 */
    private TreeMap<Integer, String> collectRevisions(String name, Deployment deployment) {
        TreeMap<Integer, String> byRevision = new TreeMap<>();
        List<ReplicaSet> replicaSets = client.apps().replicaSets()
                .inNamespace(namespace)
                .withLabelSelector(deployment.getSpec().getSelector())
                .list()
                .getItems();

        for (ReplicaSet rs : replicaSets) {
            if (rs.getMetadata() == null || rs.getMetadata().getAnnotations() == null) {
                continue;
            }
            String revision = rs.getMetadata().getAnnotations().get(REVISION_ANNOTATION);
            if (revision == null) {
                continue;
            }
            String image = replicaSetImage(rs);
            if (image == null) {
                continue;
            }
            try {
                byRevision.put(Integer.parseInt(revision), image);
            } catch (NumberFormatException ignored) {
                // revision 标注被人手工改坏过，跳过这一条
            }
        }
        return byRevision;
    }

    private String firstImage(Deployment deployment) {
        if (deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
                || deployment.getSpec().getTemplate().getSpec() == null) {
            return null;
        }
        List<Container> containers = deployment.getSpec().getTemplate().getSpec().getContainers();
        if (containers == null || containers.isEmpty()) {
            return null;
        }
        return containers.get(0).getImage();
    }

    private String replicaSetImage(ReplicaSet rs) {
        if (rs.getSpec() == null || rs.getSpec().getTemplate() == null
                || rs.getSpec().getTemplate().getSpec() == null) {
            return null;
        }
        List<Container> containers = rs.getSpec().getTemplate().getSpec().getContainers();
        if (containers == null || containers.isEmpty()) {
            return null;
        }
        return containers.get(0).getImage();
    }

    /** 把 image 的 tag 换成新值。注意 registry 可能带端口号（host:5000/img），不能直接截第一个冒号。 */
    private String replaceTag(String image, String tag) {
        int lastSlash = image.lastIndexOf('/');
        int lastColon = image.lastIndexOf(':');
        if (lastColon > lastSlash) {
            return image.substring(0, lastColon) + ":" + tag;
        }
        return image + ":" + tag;
    }
}
