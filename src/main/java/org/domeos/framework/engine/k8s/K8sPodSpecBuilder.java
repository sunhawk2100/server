package org.domeos.framework.engine.k8s;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.Container;
import org.domeos.framework.api.consolemodel.deployment.ContainerDraft;
import org.domeos.framework.api.consolemodel.deployment.EnvDraft;
import org.domeos.framework.api.consolemodel.deployment.VolumeDraft;
import org.domeos.framework.api.consolemodel.deployment.VolumeMountDraft;
import org.domeos.framework.api.model.deployment.Deployment;
import org.domeos.framework.api.model.deployment.Version;
import org.domeos.framework.api.model.deployment.related.*;
import org.domeos.framework.api.model.deployment.related.LabelSelector;
import org.domeos.framework.engine.k8s.util.SecretUtils;
import org.domeos.global.GlobalConstant;
import org.domeos.util.StringUtils;

import java.util.*;

/**
 * Created by KaiRen on 2016/11/3.
 */
public class K8sPodSpecBuilder {

    private Version version;

    private Deployment deployment;

    private List<EnvDraft> extraEnvs;

    public K8sPodSpecBuilder(Version version, Deployment deployment, List<EnvDraft> extraEnvs) {
        this.version = version;
        this.deployment = deployment;
        this.extraEnvs = extraEnvs;
    }

    public PodSpec build() {
        return buildPodSpec();
    }

    private PodSpec buildPodSpec() {
        PodSpec podSpec = new PodSpec();
        if (!StringUtils.isBlank(version.getPodSpecStr())) {
            podSpec = version.toPodSpec();
            Map<String, String> nodeSelector;
            if (podSpec.getNodeSelector() == null) {
                nodeSelector = new HashMap<>();
            } else {
                nodeSelector = podSpec.getNodeSelector();
            }
            List<LabelSelector> selectors = version.getLabelSelectors();
            if (selectors != null) {
                for (LabelSelector selector : version.getLabelSelectors()) {
                    if (selector.getName() == null) {
                        continue;
                    }
                    if (selector.getContent() == null) {
                        selector.setContent("");
                    }
                    nodeSelector.put(selector.getName(), selector.getContent());
                }
            }
            podSpec.setNodeSelector(nodeSelector);
        } else {
            if (SecretUtils.haveDomeOSRegistry(version.getContainerDrafts())) {// domeos
                // registry
                List<LocalObjectReference> secretList = Arrays.asList(new LocalObjectReference(
                        GlobalConstant.SECRET_NAME_PREFIX + deployment.getNamespace()));
                podSpec.setImagePullSecrets(secretList);
            }
            podSpec.setHostNetwork(deployment.getNetworkMode() == NetworkMode.HOST);
            Map<String, String> nodeSelector = new HashMap<>();
            List<LabelSelector> selectors = version.getLabelSelectors();
            if (selectors != null) {
                for (LabelSelector selector : version.getLabelSelectors()) {
                    if (selector.getName() == null) {
                        continue;
                    }
                    if (selector.getContent() == null) {
                        selector.setContent("");
                    }
                    nodeSelector.put(selector.getName(), selector.getContent());
                }
            }
            podSpec.setNodeSelector(nodeSelector);
            List<Container> containers = buildContainer(deployment, version, extraEnvs);
            if (containers == null) {
                return null;
            }
            podSpec.setContainers(containers);
            // if configure to autoCollect or autoDelete log, need to set volumes
            // so that data can be shared accross different containers in a Pod
            if (version.getLogDraft() != null) {
                List<Volume> volumes;
                if (version.getLogDraft().getLogItemDrafts() != null) {
                    volumes = LogDraft.formatPodVolume(version.getLogDraft()); // version 0.3
                } else {
                    volumes = LogDraft.formatPodVolume(version.getContainerDrafts()); // version > 0.3
                }
                if (volumes != null && volumes.size() > 0) {
                    podSpec.setVolumes(volumes);
                }
            }

            // add volumes here
            if (version.getVolumeDrafts() != null && !version.getVolumeDrafts().isEmpty()) {
                List<Volume> volumes = podSpec.getVolumes();
                if (volumes == null) {
                    volumes = new ArrayList<>();
                }
                for (VolumeDraft volumeDraft : version.getVolumeDrafts()) {
                    HostPathVolumeSource hostPathVolumeSource = null;
                    EmptyDirVolumeSource emptyDirVolumeSource = null;
                    if (VolumeType.HOSTPATH.equals(volumeDraft.getVolumeType()) && !StringUtils.isBlank(volumeDraft.getHostPath())) {
                        hostPathVolumeSource = new HostPathVolumeSourceBuilder().withPath(volumeDraft.getHostPath()).build();
                    }
                    if (VolumeType.EMPTYDIR.equals(volumeDraft.getVolumeType())) {
                        emptyDirVolumeSource = new EmptyDirVolumeSourceBuilder().withMedium(volumeDraft.getEmptyDir()).build();
                    }
                    Volume volume = new VolumeBuilder()
                            .withName(volumeDraft.getName())
                            .withHostPath(hostPathVolumeSource)
                            .withEmptyDir(emptyDirVolumeSource)
                            .build();
                    volumes.add(volume);
                }
                podSpec.setVolumes(volumes);
            }
        }

        return podSpec;
    }

    private List<Container> buildContainer(Deployment deployment, Version version, List<EnvDraft> extraEnvs) {
        if (version == null || version.getContainerDrafts() == null || version.getContainerDrafts().size() == 0) {
            return null;
        }
        int size = version.getContainerDrafts().size();
        List<Container> containers = new ArrayList<>(size);

        List<EnvDraft> allExtraEnvs = new LinkedList<>();
        if (extraEnvs != null) {
            allExtraEnvs.addAll(extraEnvs);
        }
        allExtraEnvs.add(new EnvDraft("NETWORK_MODE", deployment.getNetworkMode().toString()));
        if (deployment.getExposePortNum() > 0) {
            allExtraEnvs.add(new EnvDraft("NEED_PORTS", String.valueOf(deployment.getExposePortNum())));
        }

        // idx used to distinguish container name
        int idx = 0;
        int logVolumeMountIdx = 1;  // logVolumeMountIdx to distinguish log volume seq
        for (ContainerDraft containerDraft : version.getContainerDrafts()) {
            Container container = new ContainerBuilder()
                    .withImage(containerDraft.formatImage() + ":" + containerDraft.getTag())
                    .withName(deployment.getName() + "-" + idx)
                    .withResources(formatResource(containerDraft)).build();
            // ** ** add env
            List<EnvDraft> containerEnvs = new LinkedList<>();
            if (allExtraEnvs.size() > 0) {
                containerEnvs.addAll(allExtraEnvs);
            }
            if (containerDraft.getEnvs() != null) {
                containerEnvs.addAll(containerDraft.getEnvs());
            }
            List<EnvVar> envVarList = formatEnv(containerEnvs);
            envVarList.addAll(DownwardAPIUtil.generateDownwardEnvs());

            container.setEnv(envVarList);

            // health checker
            HealthChecker deploymentHealthChecker = deployment.getHealthChecker();
            HealthChecker containerHealthChecker = containerDraft.getHealthChecker();
            Probe probe;
            if (containerHealthChecker != null) {
                probe = buildProbe(containerHealthChecker);
            } else {
                probe = buildProbe(deploymentHealthChecker);
            }
            if (probe != null) {
                container.setLivenessProbe(probe);
            }

            // set image pulling policy, default is always
            container.setImagePullPolicy(containerDraft.getImagePullPolicy().name());

            // if configure to autoCollect or autoDelete log, need to set volumeMount
            // to make compitable for the old version
            // move List<LogItemDraft> from logDraft to container
            if (version.getLogDraft() != null) {
                List<LogItemDraft> logItemDrafts = version.getLogDraft().getLogItemDrafts();  // version 0.3
                int increase = 0;
                if (logItemDrafts == null) {
                    logItemDrafts = containerDraft.getLogItemDrafts();  // version >= 0.4
                    increase = 1;
                }
                List<VolumeMount> volumeMounts = LogDraft.formatOriginalContainerVolumeMount(logItemDrafts, logVolumeMountIdx);
                if (volumeMounts != null && volumeMounts.size() > 0) {
                    container.setVolumeMounts(volumeMounts);
                    logVolumeMountIdx += (volumeMounts.size() * increase);
                }

            }
            // add volume mount here
            if (containerDraft.getVolumeMountDrafts() != null) {
                List<VolumeMount> volumeMounts = container.getVolumeMounts();
                if (volumeMounts == null) {
                    volumeMounts = new ArrayList<>();
                }
                for (VolumeMountDraft volumeMountDraft : containerDraft.getVolumeMountDrafts()) {
                    VolumeMount volumeMount = new VolumeMountBuilder()
                            .withName(volumeMountDraft.getName())
                            .withMountPath(volumeMountDraft.getMountPath())
                            .withReadOnly(volumeMountDraft.isReadOnly())
                            .withSubPath(volumeMountDraft.getSubPath())
                            .build();
                    volumeMounts.add(volumeMount);
                }
                container.setVolumeMounts(volumeMounts);
            }
            containers.add(container);
            idx++;
        }
        // if configured to autoCollect or autoDelete log, then need to add flume-image container
        if (version.getLogDraft() != null) {
            LogDraft logDraft = version.getLogDraft();
            List<EnvVar> envVarList = new LinkedList<>();
            envVarList.addAll(formatEnv(allExtraEnvs));
            envVarList.addAll(DownwardAPIUtil.generateDownwardEnvs());
            if (logDraft.getLogItemDrafts() != null) {
                envVarList.addAll(LogDraft.formatLogDraftEnv(logDraft));
            } else {
                envVarList.addAll(LogDraft.formatContainerLogEnv(logDraft.getKafkaBrokers(), version.getContainerDrafts()));
            }
            EnvVar[] envs = envVarList.toArray(new EnvVar[envVarList.size()]);
            List<VolumeMount> logVolumeMounts;
            if (logDraft.getLogItemDrafts() != null) {
                logVolumeMounts = LogDraft.formatFlumeContainerVolumeMount(logDraft);
            } else {
                logVolumeMounts = LogDraft.formatFlumeContainerVolumeMount(version.getContainerDrafts());
            }
            Container container = new ContainerBuilder()
                    .withImage(logDraft.getFlumeDraft().formatImage() + ":" + logDraft.getFlumeDraft().getTag())
                    .withName(deployment.getName() + "-" + idx)
                    .withEnv(envs)
                    .withVolumeMounts(logVolumeMounts)
                    .withResources(formatResource(logDraft.getFlumeDraft()))
                    .build();
            containers.add(container);
        }

        return containers;
    }

    private Probe buildProbe(HealthChecker healthChecker) {
        if (healthChecker == null || healthChecker.getType().equals(HealthCheckerType.NONE)) {
            return null;
        }
        Probe probe = new Probe();
        probe.setTimeoutSeconds(healthChecker.getTimeout());
        probe.setInitialDelaySeconds(healthChecker.getDelay());
        switch (healthChecker.getType()) {
            case HTTP:
                HTTPGetAction httpGetAction = new HTTPGetAction();
                httpGetAction.setPath(healthChecker.getUrl());
                httpGetAction.setPort(new IntOrString(healthChecker.getPort()));
                probe.setHttpGet(httpGetAction);
                break;
            case TCP:
                TCPSocketAction tcpSocketAction = new TCPSocketAction();
                tcpSocketAction.setPort(new IntOrString(healthChecker.getPort()));
                probe.setTcpSocket(tcpSocketAction);
                break;
            default:
                return null;
        }
        return probe;
    }

    private static List<EnvVar> formatEnv(List<EnvDraft> envDrafts) {
        if (envDrafts == null || envDrafts.size() == 0) {
            return null;
        }
        List<EnvVar> envs = new LinkedList<>();
        for (EnvDraft envDraft : envDrafts) {
            EnvVar tmpEnv = new EnvVarBuilder().withName(envDraft.getKey()).withValue(envDraft.getValue()).build();
            envs.add(tmpEnv);
        }
        return envs;
    }

    private List<EnvDraft> buildHostNetworkEnvs(Deployment deployment, Version version, List<String> nodeIpList) {
        int size = version.getHostList().size();
        List<EnvDraft> hostNetworkEnvs = new LinkedList<>();
        boolean isHostMode = deployment.getNetworkMode() == NetworkMode.HOST;
        hostNetworkEnvs.add(new EnvDraft("BASIC_SERVICE_NAME", formatEnvInPod(buildStatefulServiceName(deployment))));
        for (int i = 0; i != size; i++) {
            String svcName = formatEnvInPod(buildStatefulServiceName(deployment, i));
            if (isHostMode) {
                hostNetworkEnvs.add(new EnvDraft(svcName + "_SERVICE_HOST", nodeIpList.get(i)));
            }
            // for (LoadBalanceDraft loadBalanceDraft : deployment.) {
            // if (isHostMode) {
            // hostNetworkEnvs.add(new EnvDraft(svcName + "_SERVICE_PORT_"
            // + formatEnvInPod(loadBalanceDraft.getName()),
            // String.valueOf(loadBalanceDraft.getTargetPort())));
            // }
            // hostNetworkEnvs.add(new EnvDraft(svcName +
            // "_SERVICE_TARGET_PORT_"
            // + formatEnvInPod(loadBalanceDraft.getName()),
            // String.valueOf(loadBalanceDraft.getTargetPort())));
            // }
        }
        return hostNetworkEnvs;
    }

    private ResourceRequirements formatResource(ContainerDraft containerDraft) {
        ResourceRequirements result = new ResourceRequirements();
        Map<String, Quantity> resource = new HashMap<>();
        if (containerDraft.getCpu() > 0) {
            resource.put("cpu", new Quantity(String.valueOf(containerDraft.getCpu())));
        }
        if (containerDraft.getMem() > 0) {
            resource.put("memory", new Quantity(String.valueOf(containerDraft.getMem()) + "Mi"));
        }
        result.setLimits(resource);
        return result;
    }

    private String formatEnvInPod(String originEnvName) {
        String result = originEnvName.toUpperCase();
        return result.replaceAll("[.-]", "_");
    }

    private String buildStatefulServiceName(Deployment deployment, int index) {
        return buildStatefulServiceName(deployment) + "-" + index;
    }

    private String buildStatefulServiceName(Deployment deployment) {
        if (deployment.getName().length() > 12) {
            return deployment.getName().substring(0, 12) + "-" + deployment.getId();
        }
        return deployment.getName();
    }

}


