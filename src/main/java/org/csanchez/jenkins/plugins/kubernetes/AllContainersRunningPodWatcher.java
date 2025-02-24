package org.csanchez.jenkins.plugins.kubernetes;

import static java.util.stream.Collectors.joining;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.utils.Serialization;

/**
 * A pod watcher reporting when all containers are running
 */
public class AllContainersRunningPodWatcher implements Watcher<Pod> {
    private static final Logger LOGGER = Logger.getLogger(AllContainersRunningPodWatcher.class.getName());

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<Pod> reference = new AtomicReference<>();

    private Pod pod;

    private KubernetesClient client;

    @NonNull
    private final TaskListener runListener;

    public AllContainersRunningPodWatcher(KubernetesClient client, Pod pod, @CheckForNull TaskListener runListener) {
        this.client = client;
        this.pod = pod;
        this.runListener = runListener == null ? TaskListener.NULL : runListener;
        updateState(pod);
    }

    @Override
    public void eventReceived(Action action, Pod pod) {
        LOGGER.log(Level.FINEST, "[{0}] {1}", new Object[]{action, pod.getMetadata().getName()});
        switch (action) {
            case MODIFIED:
                updateState(pod);
                break;
            default:
        }
    }

    private void updateState(Pod pod) {
        if (areAllContainersRunning(pod)) {
            LOGGER.log(Level.FINE, "All containers are running for pod {0}", new Object[] {pod.getMetadata().getName()});
            reference.set(pod);
            latch.countDown();
        }
    }

    boolean areAllContainersRunning(Pod pod) {
        return pod.getSpec().getContainers().size() == pod.getStatus().getContainerStatuses().size() && PodUtils.getContainerStatus(pod).stream().allMatch(ContainerStatus::getReady);
    }

    @Override
    public void onClose(WatcherException cause) {

    }

    /**
     * Wait until all pod containers are running
     * 
     * @return the pod
     * @throws IllegalStateException
     *             if pod or containers are no longer running
     * @throws KubernetesClientTimeoutException
     *             if time ran out
     */
    public Pod await(long amount, TimeUnit timeUnit) {
        long started = System.currentTimeMillis();
        long alreadySpent = System.currentTimeMillis() - started;
        long remaining = timeUnit.toMillis(amount) - alreadySpent;
        if (remaining <= 0) {
            return periodicAwait(0, System.currentTimeMillis(), 0, 0);
        }
        try {
            // Retry with 10% of the remaining time, with a min of 1s and a max of 10s
            return periodicAwait(10, System.currentTimeMillis(), Math.min(10000L, Math.max(remaining / 10, 1000L)), remaining);
        } catch (KubernetesClientTimeoutException e) {
            // Wrap using the right timeout
            throw new KubernetesClientTimeoutException(pod, amount, timeUnit);
        }
    }

    private Pod awaitWatcher(long amount, TimeUnit timeUnit) {
        try {
            if (latch.await(amount, timeUnit)) {
                return reference.get();
            }
            throw new KubernetesClientTimeoutException(pod, amount, timeUnit);
        } catch (InterruptedException e) {
            throw new KubernetesClientTimeoutException(pod, amount, timeUnit);
        }
    }

    /**
     * Wait until all pod containers are running
     * 
     * @return the pod
     * @throws IllegalStateException
     *             if pod or containers are no longer running
     * @throws KubernetesClientTimeoutException
     *             if time ran out
     */
    private Pod periodicAwait(int i, long started, long interval, long amount) {
        Pod pod = client.pods().inNamespace(this.pod.getMetadata().getNamespace())
                .withName(this.pod.getMetadata().getName()).get();
        if (pod == null) {
            throw new IllegalStateException(String.format("Pod is no longer available: %s/%s",
                    this.pod.getMetadata().getNamespace(), this.pod.getMetadata().getName()));
        } else {
            LOGGER.finest(() -> "Updating pod for " + this.pod.getMetadata().getNamespace() + "/" + this.pod.getMetadata().getName() + " : " + Serialization.asYaml(pod));
            this.pod = pod;
        }
        List<ContainerStatus> terminatedContainers = PodUtils.getTerminatedContainers(pod);
        if (!terminatedContainers.isEmpty()) {
            IllegalStateException ise = new IllegalStateException(String.format("Pod has terminated containers: %s/%s (%s)",
                    this.pod.getMetadata().getNamespace(),
                    this.pod.getMetadata().getName(),
                    terminatedContainers.stream()
                            .map(ContainerStatus::getName)
                            .collect(joining(", ")
                            )));
            String logs = PodUtils.logLastLines(this.pod, client);
            if (logs != null) {
                ise.addSuppressed(new ContainerLogs(logs));
            }
            throw ise;
        }
        if (areAllContainersRunning(pod)) {
            return pod;
        }
        try {
            return awaitWatcher(interval, TimeUnit.MILLISECONDS);
        } catch (KubernetesClientTimeoutException e) {
            if (i <= 0) {
                throw e;
            }
        }

        long remaining = (started + amount) - System.currentTimeMillis();
        long next = Math.max(0, Math.min(remaining, interval));
        return periodicAwait(i - 1, started, next, amount);
    }

    public PodStatus getPodStatus() {
        return this.pod.getStatus();
    }
}
