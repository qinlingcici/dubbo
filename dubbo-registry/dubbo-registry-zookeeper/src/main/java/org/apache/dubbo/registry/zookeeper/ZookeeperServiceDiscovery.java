/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.registry.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.function.ThrowableConsumer;
import org.apache.dubbo.common.function.ThrowableFunction;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.registry.client.AbstractServiceDiscovery;
import org.apache.dubbo.registry.client.ServiceDiscovery;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.event.ServiceInstancesChangedEvent;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.model.ApplicationModel;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.KeeperException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.apache.dubbo.common.constants.LoggerCodeConstants.REGISTRY_ZOOKEEPER_EXCEPTION;
import static org.apache.dubbo.common.function.ThrowableFunction.execute;
import static org.apache.dubbo.metadata.RevisionResolver.EMPTY_REVISION;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.getExportedServicesRevision;
import static org.apache.dubbo.registry.zookeeper.util.CuratorFrameworkUtils.build;
import static org.apache.dubbo.registry.zookeeper.util.CuratorFrameworkUtils.buildCuratorFramework;
import static org.apache.dubbo.registry.zookeeper.util.CuratorFrameworkUtils.buildServiceDiscovery;
import static org.apache.dubbo.registry.zookeeper.util.CuratorFrameworkUtils.getRootPath;
import static org.apache.dubbo.rpc.RpcException.REGISTRY_EXCEPTION;

/**
 * Zookeeper {@link ServiceDiscovery} implementation based on
 * <a href="https://curator.apache.org/curator-x-discovery/index.html">Apache Curator X Discovery</a>
 * <p>
 * TODO, replace curator CuratorFramework with dubbo ZookeeperClient
 */
public class ZookeeperServiceDiscovery extends AbstractServiceDiscovery {

    private final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(getClass());

    public static final String DEFAULT_GROUP = "/services";

    private final CuratorFramework curatorFramework;

    private final String rootPath;

    private final org.apache.curator.x.discovery.ServiceDiscovery<ZookeeperInstance> serviceDiscovery;

    /**
     * The Key is watched Zookeeper path, the value is an instance of {@link CuratorWatcher}
     */
    private final Map<String, ZookeeperServiceDiscoveryChangeWatcher> watcherCaches = new ConcurrentHashMap<>();

    public ZookeeperServiceDiscovery(ApplicationModel applicationModel, URL registryURL) {
        super(applicationModel, registryURL);
        try {
            this.curatorFramework = buildCuratorFramework(registryURL, this);
            this.rootPath = getRootPath(registryURL);
            this.serviceDiscovery = buildServiceDiscovery(curatorFramework, rootPath);
            this.serviceDiscovery.start();
        } catch (Exception e) {
            throw new IllegalStateException("Create zookeeper service discovery failed.", e);
        }
    }

    @Override
    public void doDestroy() throws Exception {
        serviceDiscovery.close();
        curatorFramework.close();
        watcherCaches.clear();
    }

    @Override
    public void doRegister(ServiceInstance serviceInstance) {
        try {
            serviceDiscovery.registerService(build(serviceInstance));
        } catch (Exception e) {
            throw new RpcException(REGISTRY_EXCEPTION, "Failed register instance " + serviceInstance.toString(), e);
        }
    }

    @Override
    public void doUnregister(ServiceInstance serviceInstance) throws RuntimeException {
        if (serviceInstance != null) {
            doInServiceRegistry(serviceDiscovery -> serviceDiscovery.unregisterService(build(serviceInstance)));
        }
    }

    @Override
    protected void doUpdate(ServiceInstance serviceInstance) throws RuntimeException {
        if (!EMPTY_REVISION.equals(getExportedServicesRevision(serviceInstance))) {
            reportMetadata(serviceInstance.getServiceMetadata());
        }

        try {
            serviceDiscovery.updateService(build(serviceInstance));
        } catch (Exception e) {
            throw new RpcException(REGISTRY_EXCEPTION, "Failed register instance " + serviceInstance.toString(), e);
        }
    }

    @Override
    public Set<String> getServices() {
        return doInServiceDiscovery(s -> new LinkedHashSet<>(s.queryForNames()));
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName) throws NullPointerException {
        return doInServiceDiscovery(s -> build(registryURL, s.queryForInstances(serviceName)));
    }

    @Override
    public void addServiceInstancesChangedListener(ServiceInstancesChangedListener listener)
        throws NullPointerException, IllegalArgumentException {
        // check if listener has already been added through another interface/service
        if (!instanceListeners.add(listener)) {
            return;
        }
        listener.getServiceNames().forEach(serviceName -> registerServiceWatcher(serviceName, listener));
    }

    @Override
    public void removeServiceInstancesChangedListener(ServiceInstancesChangedListener listener) throws IllegalArgumentException {
        listener.getServiceNames().forEach(serviceName -> {
            String servicePath = buildServicePath(serviceName);
            ZookeeperServiceDiscoveryChangeWatcher watcher = watcherCaches.get(servicePath);
            if (watcher != null) {
                watcher.getListeners().remove(listener);
                if (watcher.getListeners().isEmpty()) {
                    watcher.stopWatching();
                    watcherCaches.remove(servicePath);
                }
            }
        });
    }


    private void doInServiceRegistry(ThrowableConsumer<org.apache.curator.x.discovery.ServiceDiscovery> consumer) {
        ThrowableConsumer.execute(serviceDiscovery, s -> consumer.accept(s));
    }

    private <R> R doInServiceDiscovery(ThrowableFunction<org.apache.curator.x.discovery.ServiceDiscovery, R> function) {
        return execute(serviceDiscovery, function);
    }

    protected void registerServiceWatcher(String serviceName, ServiceInstancesChangedListener listener) {
        String path = buildServicePath(serviceName);
        try {
            curatorFramework.create().creatingParentsIfNeeded().forPath(path);
        } catch (KeeperException.NodeExistsException e) {
            // ignored
            if (logger.isDebugEnabled()) {
                logger.debug(e);
            }
        } catch (Exception e) {
            throw new IllegalStateException("registerServiceWatcher create path=" + path + " fail.", e);
        }

        CountDownLatch latch = new CountDownLatch(1);
        ZookeeperServiceDiscoveryChangeWatcher watcher = watcherCaches.computeIfAbsent(path, key -> {
            ZookeeperServiceDiscoveryChangeWatcher tmpWatcher = new ZookeeperServiceDiscoveryChangeWatcher(this, serviceName, path, latch);
            try {
                curatorFramework.getChildren().usingWatcher(tmpWatcher).forPath(path);
            } catch (KeeperException.NoNodeException e) {
                // ignored
                if (logger.isErrorEnabled()) {
                    logger.error(REGISTRY_ZOOKEEPER_EXCEPTION, "", "", e.getMessage());
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            return tmpWatcher;
        });
        watcher.addListener(listener);
        listener.onEvent(new ServiceInstancesChangedEvent(serviceName, this.getInstances(serviceName)));
        latch.countDown();
    }

    /**
     * 1. re-register, taken care by curator ServiceDiscovery
     * 2. re-subscribe, register curator watcher and notify the latest provider list
     */
    public void recover() {
        watcherCaches.forEach((path, watcher) -> {
            CountDownLatch latch = new CountDownLatch(1);
            Set<ServiceInstancesChangedListener> listeners = watcher.getListeners();
            try {
                watcher.setLatch(latch);
                curatorFramework.getChildren().usingWatcher(watcher).forPath(path);
            } catch (Exception e) {
                logger.error(REGISTRY_ZOOKEEPER_EXCEPTION, "", "", "Trying to recover from new zkClient session failed, path is " + path + ", error msg: " + e.getMessage());
            }

            List<ServiceInstance> instances = this.getInstances(watcher.getServiceName());
            for (ServiceInstancesChangedListener listener : listeners) {
                listener.onEvent(new ServiceInstancesChangedEvent(watcher.getServiceName(), instances));
            }
            latch.countDown();
        });
    }

    public void reRegisterWatcher(ZookeeperServiceDiscoveryChangeWatcher watcher) throws Exception {
        curatorFramework.getChildren().usingWatcher(watcher).forPath(watcher.getPath());
    }

    private String buildServicePath(String serviceName) {
        return rootPath + "/" + serviceName;
    }
}
