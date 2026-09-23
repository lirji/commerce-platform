package com.lrj.commerce.runtime.persistence;
import org.apache.ibatis.annotations.Mapper;
import com.lrj.commerce.runtime.api.Actor;

/** 本地凭据仅存令牌摘要，撤销与有效期每次请求从权威数据库判断。 */
@Mapper
public interface CredentialMapper {
    Actor authenticate(String tokenHash);
}
