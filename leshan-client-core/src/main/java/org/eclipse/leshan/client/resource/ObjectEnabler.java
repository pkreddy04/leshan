/*******************************************************************************
 * Copyright (c) 2015 Sierra Wireless and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 *
 * Contributors:
 *     Sierra Wireless - initial API and implementation
 *     Achim Kraus (Bosch Software Innovations GmbH) - use ServerIdentity
 *     Achim Kraus (Bosch Software Innovations GmbH) - implement REPLACE/UPDATE
 *******************************************************************************/
package org.eclipse.leshan.client.resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.leshan.LwM2mId;
import org.eclipse.leshan.ResponseCode;
import org.eclipse.leshan.client.request.ServerIdentity;
import org.eclipse.leshan.client.servers.ServersInfoExtractor;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.request.BootstrapDeleteRequest;
import org.eclipse.leshan.core.request.BootstrapWriteRequest;
import org.eclipse.leshan.core.request.CreateRequest;
import org.eclipse.leshan.core.request.DeleteRequest;
import org.eclipse.leshan.core.request.ExecuteRequest;
import org.eclipse.leshan.core.request.ObserveRequest;
import org.eclipse.leshan.core.request.ReadRequest;
import org.eclipse.leshan.core.request.WriteRequest;
import org.eclipse.leshan.core.request.WriteRequest.Mode;
import org.eclipse.leshan.core.response.BootstrapDeleteResponse;
import org.eclipse.leshan.core.response.BootstrapWriteResponse;
import org.eclipse.leshan.core.response.CreateResponse;
import org.eclipse.leshan.core.response.DeleteResponse;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ObserveResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;

public class ObjectEnabler extends BaseObjectEnabler {

    private Map<Integer, LwM2mInstanceEnabler> instances;
    private LwM2mInstanceEnablerFactory instanceFactory;

    public ObjectEnabler(int id, ObjectModel objectModel, Map<Integer, LwM2mInstanceEnabler> instances,
            LwM2mInstanceEnablerFactory instanceFactory) {
        super(id, objectModel);
        this.instances = new HashMap<>(instances);
        this.instanceFactory = instanceFactory;
        for (Entry<Integer, LwM2mInstanceEnabler> entry : this.instances.entrySet()) {
            addInstance(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public synchronized List<Integer> getAvailableInstanceIds() {
        List<Integer> ids = new ArrayList<>(instances.keySet());
        Collections.sort(ids);
        return ids;
    }

    @Override
    public synchronized List<Integer> getAvailableResourceIds(int instanceId) {
        LwM2mInstanceEnabler instanceEnabler = instances.get(instanceId);
        if (instanceEnabler != null) {
            return instanceEnabler.getAvailableResourceIds(getObjectModel());
        } else {
            return Collections.emptyList();
        }
    }

    public synchronized void addInstance(int instanceId, LwM2mInstanceEnabler newInstance) {
        instances.put(instanceId, newInstance);
        listenInstance(newInstance, instanceId);
    }

    public synchronized LwM2mInstanceEnabler getInstance(int instanceId) {
        return instances.get(instanceId);
    }

    public synchronized LwM2mInstanceEnabler removeInstance(int instanceId) {
        return instances.remove(instanceId);
    }

    @Override
    protected CreateResponse doCreate(CreateRequest request) {
        Integer instanceId = request.getInstanceId();
        if (instanceId == null) {
            // the client is in charge to generate the id of the new instance
            if (instances.isEmpty()) {
                instanceId = 0;
            } else {
                instanceId = Collections.max(instances.keySet()) + 1;
            }
        }

        LwM2mInstanceEnabler newInstance = instanceFactory.create(getObjectModel());

        for (LwM2mResource resource : request.getResources()) {
            newInstance.write(resource.getId(), resource);
        }
        instances.put(instanceId, newInstance);
        listenInstance(newInstance, instanceId);

        return CreateResponse.success(new LwM2mPath(request.getPath().getObjectId(), instanceId).toString());
    }

    @Override
    protected ReadResponse doRead(ServerIdentity identity, ReadRequest request) {
        LwM2mPath path = request.getPath();

        // Manage Object case
        if (path.isObject()) {
            List<LwM2mObjectInstance> lwM2mObjectInstances = new ArrayList<>();
            for (Entry<Integer, LwM2mInstanceEnabler> entry : instances.entrySet()) {
                lwM2mObjectInstances.add(getLwM2mObjectInstance(entry.getKey(), entry.getValue(), identity, false));
            }
            return ReadResponse.success(new LwM2mObject(getId(), lwM2mObjectInstances));
        }

        // Manage Instance case
        LwM2mInstanceEnabler instance = instances.get(path.getObjectInstanceId());
        if (instance == null)
            return ReadResponse.notFound();

        if (path.getResourceId() == null) {
            return ReadResponse.success(getLwM2mObjectInstance(path.getObjectInstanceId(), instance, identity, false));
        }

        // Manage Resource case
        return instance.read(path.getResourceId());
    }

    @Override
    protected ObserveResponse doObserve(final ServerIdentity identity, final ObserveRequest request) {
        final LwM2mPath path = request.getPath();

        // Manage Object case
        if (path.isObject()) {
            List<LwM2mObjectInstance> lwM2mObjectInstances = new ArrayList<>();
            for (Entry<Integer, LwM2mInstanceEnabler> entry : instances.entrySet()) {
                lwM2mObjectInstances.add(getLwM2mObjectInstance(entry.getKey(), entry.getValue(), identity, true));
            }
            return ObserveResponse.success(new LwM2mObject(getId(), lwM2mObjectInstances));
        }

        // Manage Instance case
        final LwM2mInstanceEnabler instance = instances.get(path.getObjectInstanceId());
        if (instance == null)
            return ObserveResponse.notFound();

        if (path.getResourceId() == null) {
            return ObserveResponse
                    .success(getLwM2mObjectInstance(path.getObjectInstanceId(), instance, identity, true));
        }

        // Manage Resource case
        return instance.observe(path.getResourceId());
    }

    LwM2mObjectInstance getLwM2mObjectInstance(int instanceid, LwM2mInstanceEnabler instance, ServerIdentity identity,
            boolean observe) {
        List<LwM2mResource> resources = new ArrayList<>();
        for (ResourceModel resourceModel : getObjectModel().resources.values()) {
            // check, if internal request (SYSTEM) or readable
            if (identity.isSystem() || resourceModel.operations.isReadable()) {
                if (observe) {
                    ObserveResponse response = instance.observe(resourceModel.id);
                    if (response.getCode() == ResponseCode.CONTENT && response.getContent() instanceof LwM2mResource)
                        resources.add((LwM2mResource) response.getContent());
                } else {
                    ReadResponse response = instance.read(resourceModel.id);
                    if (response.getCode() == ResponseCode.CONTENT && response.getContent() instanceof LwM2mResource)
                        resources.add((LwM2mResource) response.getContent());
                }
            }
        }
        return new LwM2mObjectInstance(instanceid, resources);
    }

    @Override
    protected WriteResponse doWrite(ServerIdentity identity, WriteRequest request) {
        LwM2mPath path = request.getPath();

        // Manage Instance case
        LwM2mInstanceEnabler instance = instances.get(path.getObjectInstanceId());
        if (instance == null)
            return WriteResponse.notFound();

        if (path.isObjectInstance()) {
            // instance write
            Map<Integer, LwM2mResource> writeResources = ((LwM2mObjectInstance) request.getNode()).getResources();
            if (request.isReplaceRequest()) {
                // REPLACE
                writeResources = new HashMap<>(writeResources); // make them modifiable
                for (ResourceModel resourceModel : getObjectModel().resources.values()) {
                    if (!identity.isLwm2mServer() || resourceModel.operations.isWritable()) {
                        LwM2mResource writeResource = writeResources.remove(resourceModel.id);
                        if (null != writeResource) {
                            instance.write(resourceModel.id, writeResource);
                        } else {
                            instance.reset(resourceModel.id);
                        }
                    }
                }
            }
            // UPDATE and resources currently not in the model
            for (LwM2mResource resource : writeResources.values()) {
                instance.write(resource.getId(), resource);
            }
            return WriteResponse.success();
        }

        // Manage Resource case
        return instance.write(path.getResourceId(), (LwM2mResource) request.getNode());
    }

    @Override
    protected BootstrapWriteResponse doWrite(ServerIdentity identity, BootstrapWriteRequest request) {
        LwM2mPath path = request.getPath();

        // Manage Object case
        if (path.isObject()) {
            for (LwM2mObjectInstance instanceNode : ((LwM2mObject) request.getNode()).getInstances().values()) {
                LwM2mInstanceEnabler instanceEnabler = instances.get(instanceNode.getId());
                if (instanceEnabler == null) {
                    doCreate(new CreateRequest(path.getObjectId(), instanceNode));
                } else {
                    doWrite(identity, new WriteRequest(Mode.REPLACE, path.getObjectId(), path.getObjectInstanceId(),
                            instanceNode.getResources().values()));
                }
            }
            return BootstrapWriteResponse.success();
        }

        // Manage Instance case
        if (path.isObjectInstance()) {
            LwM2mObjectInstance instanceNode = (LwM2mObjectInstance) request.getNode();
            LwM2mInstanceEnabler instanceEnabler = instances.get(path.getObjectInstanceId());
            if (instanceEnabler == null) {
                doCreate(new CreateRequest(path.getObjectId(), instanceNode));
            } else {
                doWrite(identity, new WriteRequest(Mode.REPLACE, request.getContentFormat(), path.getObjectId(),
                        path.getObjectInstanceId(), instanceNode.getResources().values()));
            }
            return BootstrapWriteResponse.success();
        }

        // Manage resource case
        LwM2mResource resource = (LwM2mResource) request.getNode();
        LwM2mInstanceEnabler instanceEnabler = instances.get(path.getObjectInstanceId());
        if (instanceEnabler == null) {
            doCreate(new CreateRequest(path.getObjectId(),
                    new LwM2mObjectInstance(path.getObjectInstanceId(), resource)));
        } else {
            instanceEnabler.write(path.getResourceId(), resource);
        }
        return BootstrapWriteResponse.success();
    }

    @Override
    protected ExecuteResponse doExecute(ExecuteRequest request) {
        LwM2mPath path = request.getPath();
        LwM2mInstanceEnabler instance = instances.get(path.getObjectInstanceId());
        if (instance == null) {
            return ExecuteResponse.notFound();
        }
        return instance.execute(path.getResourceId(), request.getParameters());
    }

    @Override
    protected DeleteResponse doDelete(DeleteRequest request) {
        if (null != instances.remove(request.getPath().getObjectInstanceId())) {
            return DeleteResponse.success();
        }
        return DeleteResponse.notFound();
    }

    @Override
    public BootstrapDeleteResponse doDelete(BootstrapDeleteRequest request) {
        if (request.getPath().isRoot() || request.getPath().isObject()) {
            if (id == LwM2mId.SECURITY) {
                // For security object, we clean everything except bootstrap Server account.
                Entry<Integer, LwM2mInstanceEnabler> bootstrapServerAccount = null;
                for (Entry<Integer, LwM2mInstanceEnabler> instance : instances.entrySet()) {
                    if (ServersInfoExtractor.isBootstrapServer(instance.getValue())) {
                        bootstrapServerAccount = instance;
                    }
                }
                instances.clear();
                if (bootstrapServerAccount != null) {
                    instances.put(bootstrapServerAccount.getKey(), bootstrapServerAccount.getValue());
                }
                return BootstrapDeleteResponse.success();
            } else {
                instances.clear();
                return BootstrapDeleteResponse.success();
            }
        } else if (request.getPath().isObjectInstance()) {
            if (id == LwM2mId.SECURITY) {
                // For security object, deleting bootstrap Server account is not allowed
                LwM2mInstanceEnabler instance = instances.get(request.getPath().getObjectInstanceId());
                if (ServersInfoExtractor.isBootstrapServer(instance)) {
                    return BootstrapDeleteResponse.badRequest("bootstrap server can not be deleted");
                }
            }
            if (null != instances.remove(request.getPath().getObjectInstanceId())) {
                return BootstrapDeleteResponse.success();
            } else {
                return BootstrapDeleteResponse.badRequest(String.format("Instance %s not found", request.getPath()));
            }
        }
        return BootstrapDeleteResponse.badRequest(String.format("unexcepted path %s", request.getPath()));
    }

    private void listenInstance(LwM2mInstanceEnabler instance, final int instanceId) {
        instance.addResourceChangedListener(new ResourceChangedListener() {

            @Override
            public void resourcesChanged(int... resourceIds) {
                NotifySender sender = getNotifySender();
                if (null != sender) {
                    // check, if sender is available
                    sender.sendNotify(getId() + "");
                    sender.sendNotify(getId() + "/" + instanceId);
                    for (int resourceId : resourceIds) {
                        sender.sendNotify(getId() + "/" + instanceId + "/" + resourceId);
                    }
                }
            }
        });
    }

}
