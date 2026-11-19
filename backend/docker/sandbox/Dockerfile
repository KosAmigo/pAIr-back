# 응시자 코드 실행용 샌드박스 이미지. 빌드: sh docker/sandbox/build.sh
FROM eclipse-temurin:21-jdk
ADD https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.12.2/junit-platform-console-standalone-1.12.2.jar /opt/junit.jar
COPY run.sh /opt/run.sh
RUN chmod 644 /opt/junit.jar && chmod 755 /opt/run.sh && useradd -u 10001 -m runner
USER runner
WORKDIR /tmp
