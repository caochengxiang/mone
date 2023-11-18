package com.xiaomi.data.push.uds.codes;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class RpcInvocation implements Serializable {

    private String serviceName;

    private String methodName;

    private String parameterTypesDesc;

    private Object[] args;

    private Map<String, Object> attachments;

}
