package com.paiagent.engine.executor.impl;

import com.paiagent.service.ConnectorCredentialService;
import org.springframework.stereotype.Component;

/** 为工厂注册 kubernetes_restart，执行逻辑由父类统一实施。 */
@Component
public class KubernetesRestartNodeExecutor extends KubernetesActionNodeExecutor {

    public KubernetesRestartNodeExecutor(OutboundHttpPolicy httpPolicy,
                                         ConnectorCredentialService credentialService) {
        super(httpPolicy, credentialService);
    }

    @Override
    public String getSupportedNodeType() {
        return "kubernetes_restart";
    }
}
