package com.lrj.commerce.runtime;
import com.lrj.commerce.runtime.api.OperatorDirectory;
import com.lrj.commerce.runtime.persistence.CredentialMapper;
import org.springframework.stereotype.Service;

/** 与现有本地认证保持同一权威来源；外部IdP尚未接入。 */
@Service
public class LocalOperatorDirectory implements OperatorDirectory {
 private final CredentialMapper credentials;
 public LocalOperatorDirectory(CredentialMapper credentials){this.credentials=credentials;}
 /** 授权不创建身份，也不接受其他租户的运营账号。 */
 public boolean active(String tenantId,String actorId){return credentials.activeOperator(tenantId,actorId);}
}
