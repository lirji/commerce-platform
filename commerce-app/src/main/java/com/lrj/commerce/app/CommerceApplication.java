package com.lrj.commerce.app;
import com.lrj.commerce.marketing.api.DecisionPort;
import com.lrj.commerce.marketing.application.MarketingDecisionService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;
import org.apache.ibatis.annotations.Mapper;
import java.time.Clock;

/** 单一应用装配各领域，通过端口连接纯规则内核。 */
@SpringBootApplication(scanBasePackages="com.lrj.commerce")
@MapperScan(basePackages="com.lrj.commerce",annotationClass=Mapper.class)
public class CommerceApplication {
    public static void main(String[] args) {SpringApplication.run(CommerceApplication.class,args);}
    @Bean Clock clock() {return Clock.systemUTC();}
    @Bean DecisionPort decisions() {return new MarketingDecisionService();}
}
