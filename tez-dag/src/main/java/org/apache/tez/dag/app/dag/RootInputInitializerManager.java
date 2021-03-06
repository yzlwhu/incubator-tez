/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.dag.app.dag;

import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.tez.common.ReflectionUtils;
import org.apache.tez.dag.api.InputDescriptor;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.app.AppContext;
import org.apache.tez.dag.app.dag.event.VertexEventRootInputFailed;
import org.apache.tez.dag.app.dag.event.VertexEventRootInputInitialized;
import org.apache.tez.dag.app.dag.impl.RootInputLeafOutputDescriptor;
import org.apache.tez.dag.app.dag.impl.TezRootInputInitializerContextImpl;
import org.apache.tez.dag.records.TezVertexID;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.api.TezRootInputInitializer;
import org.apache.tez.runtime.api.TezRootInputInitializerContext;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.tez.runtime.api.events.RootInputInitializerEvent;

public class RootInputInitializerManager {

  private static final Log LOG = LogFactory.getLog(RootInputInitializerManager.class);

  private final ExecutorService rawExecutor;
  private final ListeningExecutorService executor;
  @SuppressWarnings("rawtypes")
  private final EventHandler eventHandler;
  private volatile boolean isStopped = false;
  private final UserGroupInformation dagUgi;

  private final Vertex vertex;
  private final AppContext appContext;

  private final Map<String, InitializerWrapper> initializerMap = new HashMap<String, InitializerWrapper>();

  @SuppressWarnings("rawtypes")
  public RootInputInitializerManager(Vertex vertex, AppContext appContext,
                                     UserGroupInformation dagUgi) {
    this.appContext = appContext;
    this.vertex = vertex;
    this.eventHandler = appContext.getEventHandler();
    this.rawExecutor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
        .setDaemon(true).setNameFormat("InputInitializer [" + this.vertex.getName() + "] #%d").build());
    this.executor = MoreExecutors.listeningDecorator(rawExecutor);
    this.dagUgi = dagUgi;
  }
  
  public void runInputInitializers(List<RootInputLeafOutputDescriptor<InputDescriptor>> inputs) {

    for (RootInputLeafOutputDescriptor<InputDescriptor> input : inputs) {
      TezRootInputInitializer initializer = createInitializer(input);
      InitializerWrapper initializerWrapper = new InitializerWrapper(input, initializer, vertex, appContext);
      initializerMap.put(input.getEntityName(), initializerWrapper);
      ListenableFuture<List<Event>> future = executor
          .submit(new InputInitializerCallable(initializerWrapper, dagUgi));
      Futures.addCallback(future, createInputInitializerCallback(initializerWrapper));
    }
  }


  @VisibleForTesting
  protected TezRootInputInitializer createInitializer(RootInputLeafOutputDescriptor<InputDescriptor> input) {
    String className = input.getInitializerClassName();
    @SuppressWarnings("unchecked")
    Class<? extends TezRootInputInitializer> clazz =
        (Class<? extends TezRootInputInitializer>) ReflectionUtils
            .getClazz(className);
    TezRootInputInitializer initializer = null;
    try {
      initializer = clazz.newInstance();
    } catch (InstantiationException e) {
      throw new TezUncheckedException("Failed to create input initializerWrapper", e);
    } catch (IllegalAccessException e) {
      throw new TezUncheckedException("Failed to create input initializerWrapper", e);
    }
    return initializer;
  }

  public void handleInitializerEvent(RootInputInitializerEvent event) {
    Preconditions.checkState(vertex.getName().equals(event.getTargetVertexName()),
        "Received event for incorrect vertex");
    Preconditions.checkNotNull(event.getTargetInputName(), "target input name must be set");
    InitializerWrapper initializer = initializerMap.get(event.getTargetInputName());
    Preconditions.checkState(initializer != null,
        "Received event for unknown input : " + event.getTargetInputName());
    // This is a restriction based on current flow - i.e. events generated only by initialize().
    // TODO Rework the flow as per the first comment on TEZ-1076
    if (isStopped) {
      LOG.warn("InitializerManager already stopped for " + vertex.getLogIdentifier() +
          " Dropping event. [" + event + "]");
      return;
    }
    if (initializer.isComplete()) {
      LOG.warn(
          "Event targeted at vertex " + vertex.getLogIdentifier() + ", initializerWrapper for Input: " +
              initializer.getInput().getEntityName() +
              " will be dropped, since Input has already been initialized. [" + event + "]");
    }
    try {
      initializer.getInitializer().handleInputInitializerEvent(Lists.newArrayList(event));
    } catch (Exception e) {
      throw new TezUncheckedException(
          "Initializer for input: " + event.getTargetInputName() + " failed to process event", e);
    }
  }

  @VisibleForTesting
  protected InputInitializerCallback createInputInitializerCallback(InitializerWrapper initializer) {
    return new InputInitializerCallback(initializer, eventHandler, vertex.getVertexId());
  }
  
  public void shutdown() {
    if (executor != null && !isStopped) {
      // Don't really care about what is running if an error occurs. If no error
      // occurs, all execution is complete.
      executor.shutdownNow();
      isStopped = true;
    }
  }

  private static class InputInitializerCallable implements
      Callable<List<Event>> {

    private final InitializerWrapper initializerWrapper;
    private final UserGroupInformation ugi;

    public InputInitializerCallable(InitializerWrapper initializer, UserGroupInformation ugi) {
      this.initializerWrapper = initializer;
      this.ugi = ugi;
    }

    @Override
    public List<Event> call() throws Exception {
      List<Event> events = ugi.doAs(new PrivilegedExceptionAction<List<Event>>() {
        @Override
        public List<Event> run() throws Exception {
          LOG.info(
              "Starting InputInitializer for Input: " + initializerWrapper.getInput().getEntityName() +
                  " on vertex " + initializerWrapper.getVertexLogIdentifier());
          return initializerWrapper.getInitializer().initialize(initializerWrapper.context);
        }
      });
      return events;
    }
  }

  @SuppressWarnings("rawtypes")
  @VisibleForTesting
  private static class InputInitializerCallback implements
      FutureCallback<List<Event>> {

    private final InitializerWrapper initializer;
    private final EventHandler eventHandler;
    private final TezVertexID vertexID;

    public InputInitializerCallback(InitializerWrapper initializer,
        EventHandler eventHandler, TezVertexID vertexID) {
      this.initializer = initializer;
      this.eventHandler = eventHandler;
      this.vertexID = vertexID;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onSuccess(List<Event> result) {
      initializer.setComplete();
      LOG.info(
          "Succeeded InputInitializer for Input: " + initializer.getInput().getEntityName() +
              " on vertex " + initializer.getVertexLogIdentifier());
      eventHandler.handle(new VertexEventRootInputInitialized(vertexID,
          initializer.getInput().getEntityName(), result));
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onFailure(Throwable t) {
      initializer.setComplete();
      LOG.info(
          "Failed InputInitializer for Input: " + initializer.getInput().getEntityName() +
              " on vertex " + initializer.getVertexLogIdentifier());
      eventHandler
          .handle(new VertexEventRootInputFailed(vertexID, initializer.getInput().getEntityName(), t));
    }
  }

  private static class InitializerWrapper {


    private final RootInputLeafOutputDescriptor<InputDescriptor> input;
    private final TezRootInputInitializer initializer;
    private final TezRootInputInitializerContext context;
    private final AtomicBoolean isComplete = new AtomicBoolean(false);
    private final String vertexLogIdentifier;

    InitializerWrapper(RootInputLeafOutputDescriptor<InputDescriptor> input,
                       TezRootInputInitializer initializer, Vertex vertex,
                       AppContext appContext) {
      this.input = input;
      this.initializer = initializer;
      this.context = new TezRootInputInitializerContextImpl(input, vertex, appContext);
      this.vertexLogIdentifier = vertex.getLogIdentifier();
    }

    public RootInputLeafOutputDescriptor<InputDescriptor> getInput() {
      return input;
    }

    public TezRootInputInitializer getInitializer() {
      return initializer;
    }

    public TezRootInputInitializerContext getContext() {
      return context;
    }

    public String getVertexLogIdentifier() {
      return vertexLogIdentifier;
    }

    public boolean isComplete() {
      return isComplete.get();
    }

    public void setComplete() {
      this.isComplete.set(true);
    }
  }


}
