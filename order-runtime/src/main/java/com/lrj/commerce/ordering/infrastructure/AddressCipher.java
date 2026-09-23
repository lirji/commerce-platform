package com.lrj.commerce.ordering.infrastructure;
import com.lrj.commerce.ordering.api.OrderApi.Address;
import com.lrj.commerce.runtime.JsonCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Base64;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** 本地开发密钥来自私密配置；AAD阻止跨租户或订单挪用密文。 */
@Component
public class AddressCipher {
    private final SecretKeySpec key;private final SecureRandom random=new SecureRandom();
    public AddressCipher(@Value("${commerce.address-key}") String encoded) {
        byte[] bytes=Base64.getDecoder().decode(encoded);
        if(bytes.length!=32) throw new IllegalArgumentException("地址密钥必须为256位");key=new SecretKeySpec(bytes,"AES");
    }
    /** 随机nonce每次不同；原命令重试通过事务结果回放避免重建地址。 */
    public byte[] encrypt(String tenant,String order,Address address) {
        try {
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD((tenant+"/"+order).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(JsonCodec.write(address).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array();
        } catch(java.security.GeneralSecurityException failure) {throw new IllegalStateException("地址加密不可用",failure);}
    }
}
