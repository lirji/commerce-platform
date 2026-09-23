# 构建入口为scripts/build.sh：只封装已经通过同一套验证的不可变jar。
FROM eclipse-temurin:21.0.12_8-jre-ubi9-minimal@sha256:fa6a3cd1e88402446002f86e0d06f5202d7d6ec02d525888f9c0ea2eeb5b07ad
WORKDIR /app
COPY --chown=10001:10001 commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar /app/commerce.jar
USER 10001:10001
EXPOSE 8600
ENTRYPOINT ["java","-jar","/app/commerce.jar"]
