package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ConnectorCredentialRequest;
import com.paiagent.dto.ConnectorCredentialResponse;
import com.paiagent.entity.ConnectorCredential;
import com.paiagent.mapper.ConnectorCredentialMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConnectorCredentialService {

    private final ConnectorCredentialMapper credentialMapper;
    private final CredentialCryptoService cryptoService;

    public ConnectorCredentialService(ConnectorCredentialMapper credentialMapper,
                                      CredentialCryptoService cryptoService) {
        this.credentialMapper = credentialMapper;
        this.cryptoService = cryptoService;
    }

    public ConnectorCredentialResponse create(ConnectorCredentialRequest request) {
        ConnectorCredential entity = new ConnectorCredential();
        applyRequest(entity, request);
        credentialMapper.insert(entity);
        return toResponse(entity);
    }

    public ConnectorCredentialResponse update(Long id, ConnectorCredentialRequest request) {
        ConnectorCredential entity = requireCredential(id);
        applyRequest(entity, request);
        credentialMapper.updateById(entity);
        return toResponse(entity);
    }

    public List<ConnectorCredentialResponse> list() {
        return credentialMapper.selectList(null).stream()
                .map(this::toResponse)
                .toList();
    }

    public void delete(Long id) {
        requireCredential(id);
        credentialMapper.deleteById(id);
    }

    public Map<String, String> getSecretData(Long id) {
        ConnectorCredential entity = requireCredential(id);
        Map<String, String> parsed = JSON.parseObject(
                cryptoService.decrypt(entity.getEncryptedPayload()),
                Map.class
        );
        return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
    }

    private void applyRequest(ConnectorCredential entity, ConnectorCredentialRequest request) {
        entity.setName(request.getName().trim());
        entity.setConnectorType(request.getConnectorType().trim().toLowerCase());
        entity.setDescription(request.getDescription());
        entity.setKeyVersion("v1");
        entity.setEncryptedPayload(cryptoService.encrypt(JSON.toJSONString(request.getSecretData())));
    }

    private ConnectorCredential requireCredential(Long id) {
        ConnectorCredential entity = credentialMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("凭证不存在");
        }
        return entity;
    }

    private ConnectorCredentialResponse toResponse(ConnectorCredential entity) {
        ConnectorCredentialResponse response = new ConnectorCredentialResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setConnectorType(entity.getConnectorType());
        response.setDescription(entity.getDescription());
        response.setKeyVersion(entity.getKeyVersion());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setSecretFields(getSecretData(entity.getId()).keySet().stream().sorted().toList());
        return response;
    }
}
