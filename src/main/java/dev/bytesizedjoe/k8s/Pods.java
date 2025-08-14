package dev.bytesizedjoe.k8s;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@NullMarked
@UtilityClass
public class Pods {
  public Optional<Pod> findFirstRunningPod(final KubernetesClient client) {
    final List<Pod> pods = client.pods().inAnyNamespace().list().getItems();
    return pods.stream()
        .filter(p -> Objects.equals("Running", Optional.ofNullable(p.getStatus()).map(s -> s.getPhase()).orElse(null)))
        .sorted(Comparator.comparing(p -> p.getMetadata().getNamespace() + "/" + p.getMetadata().getName()))
        .findFirst();
  }

  public Optional<String> firstRunningContainerName(final Pod p) {
    if (p.getStatus() == null || p.getStatus().getContainerStatuses() == null) return Optional.empty();
    for (final ContainerStatus cs : p.getStatus().getContainerStatuses()) {
      if (Boolean.TRUE.equals(cs.getReady())) {
        return Optional.ofNullable(cs.getName());
      }
    }
    return Optional.empty();
  }

  public static final class NoopPodWatcher implements Watcher<Pod> {
    @Override public void eventReceived(final Action action, final Pod resource) { }
    @Override public void onClose(final WatcherException cause) { }
  }
}
