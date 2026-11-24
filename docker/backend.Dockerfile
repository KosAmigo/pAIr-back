# syntax=docker/dockerfile:1.7
# 백엔드 이미지. 빌드 컨텍스트는 pAIr-back/backend (docker-compose.yml 의 backend 서비스)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# Windows 에서 받은 줄바꿈(CRLF) 때문에 gradlew 가 리눅스에서 안 도는 것을 막는다
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon --console=plain \
    && cp "$(ls build/libs/*.jar | grep -v plain | head -n 1)" /app.jar

# 응시자 코드를 서버 JVM 에서 컴파일·실행하는 모드(LocalJvmRunner)와 편집기 진단이 javac 를 쓰므로 JDK 이미지를 그대로 쓴다
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
# 비밀번호·API 키는 이미지에 넣지 않는다. docker-compose.yml 이 application-secret.yml 을 /app 에 읽기 전용으로 붙인다
EXPOSE 8180 8181
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
