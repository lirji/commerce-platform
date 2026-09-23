package com.lrj.commerce.runtime.persistence;
import org.apache.ibatis.annotations.Mapper;
import com.lrj.commerce.runtime.api.Actor;

/** 本地凭据仅存令牌摘要，撤销与有效期每次请求从权威数据库判断。 */
@Mapper
public interface CredentialMapper {
    record Credential(String tenantId,String actorId,Actor.Role role,Actor.Channel channel) { }
    Credential findCredential(String tokenHash);
    /** 渠道只来自受控凭据，客户端不能通过报文覆盖。 */
    default Actor authenticate(String tokenHash){var c=findCredential(tokenHash);return c==null?null:new Actor(c.tenantId(),c.actorId(),c.role(),c.channel());}
    boolean activeOperator(@org.apache.ibatis.annotations.Param("tenant") String tenant,@org.apache.ibatis.annotations.Param("actor") String actor);
}
