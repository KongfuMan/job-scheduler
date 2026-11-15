package com.webapps.jobs.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesClientConfiguration {
    @Bean(destroyMethod = "close")
    KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
