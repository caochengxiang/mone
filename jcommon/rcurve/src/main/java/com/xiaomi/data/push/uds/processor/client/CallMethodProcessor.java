/*
 *  Copyright 2020 Xiaomi
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.xiaomi.data.push.uds.processor.client;

import com.alibaba.com.caucho.hessian.io.Hessian2Input;
import com.google.gson.Gson;
import com.xiaomi.data.push.common.CovertUtils;
import com.xiaomi.data.push.common.Send;
import com.xiaomi.data.push.uds.classloader.ClassLoaderExecute;
import com.xiaomi.data.push.uds.codes.CodesFactory;
import com.xiaomi.data.push.uds.codes.HessianCodes;
import com.xiaomi.data.push.uds.codes.ICodes;
import com.xiaomi.data.push.uds.codes.RpcInvocation;
import com.xiaomi.data.push.uds.context.CallContext;
import com.xiaomi.data.push.uds.context.ContextHolder;
import com.xiaomi.data.push.uds.po.UdsCommand;
import com.xiaomi.data.push.uds.processor.UdsProcessor;
import com.xiaomi.youpin.docean.common.DefaultInvokeMethodCallback;
import com.xiaomi.youpin.docean.common.InvokeMethodCallback;
import com.xiaomi.youpin.docean.common.MethodReq;
import com.xiaomi.youpin.docean.common.ReflectUtils;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Function;


/**
 * @author goodjava@qq.com
 * <p>
 * 反射调用方法(从mesh agent 拿到的参数)
 */
@Slf4j
public class CallMethodProcessor implements UdsProcessor<UdsCommand, UdsCommand> {

    private final Function<UdsCommand, Object> beanFactory;

    @Setter
    private Function<String, ClassLoader> classLoaderFunction;

    /**
     * 执行函数的钩子类
     */
    @Setter
    private InvokeMethodCallback invokeMethodCallback = new DefaultInvokeMethodCallback();

    /**
     * 异常转换function
     */
    @Setter
    private BiFunction<Throwable, UdsCommand, Throwable> throwableFunction = (throwable, res) -> throwable;

    private Gson gson = new Gson();


    public CallMethodProcessor(Function<UdsCommand, Object> beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public UdsCommand processRequest(UdsCommand req) {
        log.debug("process request:{}", req.getId());
        UdsCommand response = UdsCommand.createResponse(req);
        new ClassLoaderExecute(this.throwableFunction).execute(() -> {
            CallContext ctx = new CallContext();
            ctx.setAttrs(req.getAttachments());
            ContextHolder.getContext().set(ctx);
            Object obj = beanFactory.apply(req);
            String[] types = req.getParamTypes() == null ? new String[]{} : req.getParamTypes();
            String[] paramArray = req.getParams() == null ? new String[]{} : req.getParams();
            log.debug("invoke method :{} {} {} {} {}", req.getId(), req.getServiceName(), req.getMethodName(), Arrays.toString(types), Arrays.toString(paramArray));

            MethodReq mr = new MethodReq();
            mr.setMethodName(req.getMethodName());
            mr.setParamTypes(types);
            mr.setParams(paramArray);
            mr.setByteParams(req.getByteParams());
            mr.setAttachments(req.getAttachments());
            beforeCallMethod(req, mr);
            if (!"com.xiaomi.sautumn.serverless.api.dubbo.Dubbo".equals(req.getServiceName()) && !req.getAttachments().isEmpty() && "dubbo".equals(req.getAttachments().get("alias"))) {
                //进入mesh dubbo provider业务代码前使用
                return ReflectUtils.invokeMethod(mr, obj, (paramTypes, params) -> {
                    byte[] bytes = req.getData();
                    Object[] objs = new Object[mr.getParamTypes().length];
                    ByteArrayInputStream is = new ByteArrayInputStream(bytes);
                    Hessian2Input hi = new Hessian2Input(is);
                    try {
                        //反序列化dubbo body
                        String version = hi.readString();
                        String serviceName = hi.readString();
                        String serviceVersion = hi.readString();
                        String method = hi.readString();
                        String desc = hi.readString();
                        for (int i=0; i<mr.getParamTypes().length; i++) {
                            objs[i] = hi.readObject();
                        }
                        //todo dubbo的attachments处理
//                      Object attachments = hi.readObject(Map.class);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        try {
                            hi.close();
                            is.close();
                        } catch (Exception ex) {
                            log.error(ex.getMessage());
                        }
                    }
                    return objs;
                }, invokeMethodCallback);
            } else {
                return ReflectUtils.invokeMethod(mr, obj, (paramTypes, params) -> CovertUtils.convert(req.getSerializeType(), paramTypes, params), invokeMethodCallback);
            }
        }, this.classLoaderFunction, response, req, (res) -> afterCallMethod(res));
        if (response.getObj() instanceof CompletableFuture) {
            ((CompletableFuture)response.getObj()).handle((obj, t) -> {
                if (t != null) {
                    if (t instanceof CompletionException) {

                    } else {

                    }
                } else {

                }
                response.setObj(obj);
                Send.sendResponse(req.getChannel(), response);
                return response;
            });
        } else {
            if ("com.xiaomi.sautumn.serverless.api.dubbo.Dubbo".equals(req.getServiceName())){
                Send.sendResponsetmp(req.getChannel(), response);
            } else if (!"com.xiaomi.sautumn.serverless.api.dubbo.Dubbo".equals(req.getServiceName()) && !req.getAttachments().isEmpty() && "dubbo".equals(req.getAttachments().get("alias"))){
                // 执行完成mesh dubbo provider业务代码后使用
                Object res = response.getObj();
                ICodes codes = CodesFactory.getCodes((byte) 1);
                byte[] bytes = codes.encode(res, res);
                response.setData(bytes);
                Send.sendResponsetmp(req.getChannel(), response);
            } else {
                Send.sendResponse(req.getChannel(), response);
            }
        }
        return null;
    }

    @Override
    public String cmd() {
        return "call";
    }

    @Override
    public int poolSize() {
        String threads = System.getenv("uds.client.dubbo.threads");
        if (threads != null) {
            return Integer.valueOf(threads);
        } else {
            return 300;
        }
    }

    public void beforeCallMethod(UdsCommand req, MethodReq mr) {

    }

    public void afterCallMethod(Object res) {
    }
}
