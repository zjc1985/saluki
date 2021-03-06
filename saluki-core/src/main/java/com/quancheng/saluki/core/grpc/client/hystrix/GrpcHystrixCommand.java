/*
 * Copyright 1999-2012 DianRong.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.quancheng.saluki.core.grpc.client.hystrix;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.quancheng.saluki.core.common.Constants;
import com.quancheng.saluki.core.common.GrpcURL;
import com.quancheng.saluki.core.common.RpcContext;
import com.quancheng.saluki.core.grpc.client.GrpcRequest;
import com.quancheng.saluki.core.grpc.client.GrpcResponse;
import com.quancheng.saluki.core.grpc.client.failover.GrpcClientCall;
import com.quancheng.saluki.core.grpc.exception.RpcFrameworkException;
import com.quancheng.saluki.core.grpc.service.ClientServerMonitor;
import com.quancheng.saluki.core.grpc.service.MonitorService;
import com.quancheng.saluki.core.grpc.util.MethodDescriptorUtil;
import com.quancheng.saluki.serializer.exception.ProtobufException;

import io.grpc.MethodDescriptor;

/**
 * @author liushiming 2017年4月26日 下午6:16:32
 * @version $Id: GrpcHystrixObservableCommand.java, v 0.0.1 2017年4月26日 下午6:16:32 liushiming
 */
public abstract class GrpcHystrixCommand extends HystrixCommand<Object> {

  private static final Logger logger = LoggerFactory.getLogger(GrpcHystrixCommand.class);

  private static final int DEFAULT_THREADPOOL_CORE_SIZE = 5;

  private final String serviceName;

  private final String methodName;

  private final long start;

  private GrpcRequest request;

  private GrpcClientCall clientCall;

  private ClientServerMonitor clientServerMonitor;

  private AtomicInteger concurrent;

  public GrpcHystrixCommand(String serviceName, String methodName, Boolean isEnabledFallBack) {
    super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey(serviceName))//
        .andCommandKey(HystrixCommandKey.Factory.asKey(serviceName + ":" + methodName))//
        .andCommandPropertiesDefaults(
            HystrixCommandProperties.Setter().withCircuitBreakerRequestVolumeThreshold(20)// 10秒钟内至少19此请求失败，熔断器才发挥起作用
                .withCircuitBreakerSleepWindowInMilliseconds(30000)// 熔断器中断请求30秒后会进入半打开状态,放部分流量过去重试
                .withCircuitBreakerErrorThresholdPercentage(50)// 错误率达到50开启熔断保护
                .withExecutionTimeoutEnabled(false)// 禁用这里的超时
                .withFallbackEnabled(isEnabledFallBack))//
        .andThreadPoolPropertiesDefaults(
            HystrixThreadPoolProperties.Setter().withCoreSize(DEFAULT_THREADPOOL_CORE_SIZE))// 线程池为5
    );
    this.serviceName = serviceName;
    this.methodName = methodName;
    this.start = System.currentTimeMillis();
  }

  public void setRequest(GrpcRequest request) {
    this.request = request;
  }

  public void setClientCall(GrpcClientCall clientCall) {
    this.clientCall = clientCall;
  }

  public void setClientServerMonitor(ClientServerMonitor clientServerMonitor) {
    this.clientServerMonitor = clientServerMonitor;
  }

  public void setConcurrent(AtomicInteger concurrent) {
    this.concurrent = concurrent;
  }

  @Override
  protected Object run() throws Exception {
    MethodDescriptor<Message, Message> methodDesc = this.request.getMethodDescriptor();
    Integer timeOut = this.request.getMethodRequest().getCallTimeout();
    Message request = getRequestMessage();
    Message response = this.run0(request, methodDesc, timeOut, clientCall);
    Object obj = this.transformMessage(response);
    collect(serviceName, methodName, request, response, false);
    return obj;
  }

  @Override
  protected Object getFallback() {
    Class<?> responseType = this.request.getMethodRequest().getResponseType();
    Message response = MethodDescriptorUtil.buildDefaultInstance(responseType);
    Object obj = this.transformMessage(response);
    collect(serviceName, methodName, getRequestMessage(), response, true);
    return obj;
  }


  private Message getRequestMessage() {
    try {
      return this.request.getRequestArg();
    } catch (ProtobufException e) {
      RpcFrameworkException rpcFramwork = new RpcFrameworkException(e);
      throw rpcFramwork;
    }
  }

  private Object transformMessage(Message message) {
    Class<?> respPojoType = request.getMethodRequest().getResponseType();
    GrpcResponse response = new GrpcResponse.Default(message, respPojoType);
    try {
      return response.getResponseArg();
    } catch (ProtobufException e) {
      RpcFrameworkException rpcFramwork = new RpcFrameworkException(e);
      throw rpcFramwork;
    }
  }

  private void collect(String serviceName, String methodName, Message request, Message response,
      boolean error) {
    try {
      InetSocketAddress provider =
          (InetSocketAddress) clientCall.getAffinity().get(GrpcClientCall.GRPC_CURRENT_ADDR_KEY);
      if (request == null || response == null || provider == null) {
        return;
      }
      long elapsed = System.currentTimeMillis() - this.start; // 计算调用耗时
      int concurrent = this.concurrent.get(); // 当前并发数
      String service = serviceName; // 获取服务名称
      String method = methodName; // 获取方法名
      GrpcURL refUrl = this.request.getRefUrl();
      String host = refUrl.getHost();
      Integer port = refUrl.getPort();
      clientServerMonitor.collect(new GrpcURL(Constants.MONITOR_PROTOCOL, host, port, //
          service + "/" + method, //
          MonitorService.TIMESTAMP, String.valueOf(start), //
          MonitorService.APPLICATION, refUrl.getParameter(Constants.APPLICATION_NAME), //
          MonitorService.INTERFACE, service, //
          MonitorService.METHOD, method, //
          MonitorService.PROVIDER, provider.getHostName(), //
          error ? MonitorService.FAILURE : MonitorService.SUCCESS, "1", //
          MonitorService.ELAPSED, String.valueOf(elapsed), //
          MonitorService.CONCURRENT, String.valueOf(concurrent), //
          MonitorService.INPUT, String.valueOf(request.getSerializedSize()), //
          MonitorService.OUTPUT, String.valueOf(response.getSerializedSize())));
    } catch (Throwable t) {
      logger.warn("Failed to monitor count service " + serviceName + ", cause: " + t.getMessage());
    }
  }

  protected abstract Message run0(Message req, MethodDescriptor<Message, Message> methodDesc,
      Integer timeOut, GrpcClientCall clientCall);

  protected void cacheCurrentServer() {
    Object obj = clientCall.getAffinity().get(GrpcClientCall.GRPC_CURRENT_ADDR_KEY);
    if (obj != null) {
      InetSocketAddress currentServer = (InetSocketAddress) obj;
      RpcContext.getContext().setAttachment(Constants.REMOTE_ADDRESS, currentServer.getHostName());
    }
  }
}
