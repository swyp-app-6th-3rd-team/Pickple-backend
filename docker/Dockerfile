# 빌드 스테이지
FROM amazoncorretto:25 AS builder
WORKDIR /workspace

# amazoncorretto 이미지는 Amazon Linux 최소 구성이라 findutils(xargs) 가 없다.
# Gradle wrapper 스크립트가 xargs 를 쓰므로 없으면 "xargs is not available" 로 실패한다.
RUN yum install -y findutils && yum clean all

# 의존성 레이어를 소스와 분리해 캐시 적중률을 높인다.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# 실행 스테이지
FROM amazoncorretto:25-alpine
WORKDIR /app

# 헬스체크용 wget 과 타임존 데이터
RUN apk add --no-cache wget tzdata && \
    ln -sf /usr/share/zoneinfo/Asia/Seoul /etc/localtime

# root 로 실행하지 않는다.
RUN addgroup -S app && adduser -S app -G app

# 로그 디렉터리를 root 권한일 때 미리 만들고 소유권을 넘긴다.
#
# 이 순서가 중요하다. 도커는 빈 named volume 을 마운트할 때 컨테이너 이미지의
# 해당 경로 소유권·권한을 볼륨에 복사한다. 디렉터리가 없으면 root:root 로 만들어지고,
# non-root 로 실행되는 앱이 로그 파일을 쓰지 못한다.
#   → java.io.FileNotFoundException: /app/logs/app.log (Permission denied)
#
# ⚠️ 운영은 named volume 이 아니라 /data/logs bind mount 다(ADR-0025). bind mount 는
#    위의 소유권 복사가 **일어나지 않으므로**, 호스트에서 미리 chown 100:101 해야 한다
#    (terraform/templates/user-data.sh.tftpl 참조). 이 RUN 은 로컬 실행용이다.
#
# USER 를 바꾸기 전에 만들어야 chown 이 먹는다.
# jfr/ 은 JFR 상시 녹화의 링버퍼가 쌓이는 곳이다(아래 ENTRYPOINT 참조).
RUN mkdir -p /app/logs/jfr && \
    chown -R app:app /app/logs

USER app

COPY --from=builder --chown=app:app /workspace/build/libs/*.jar app.jar

# 컨테이너 안의 로그 위치. docker-compose 가 이 경로를 볼륨에 연결한다.
ENV LOG_DIR=/app/logs

EXPOSE 8080

# 컨테이너 메모리 한도를 JVM 이 인식하게 한다.
#
# JFR 상시 녹화를 켠다. JDK 내장이라 별도 에이전트가 필요 없다.
#   disk=true + maxage=6h  → 디스크에 6시간 롤링 링버퍼를 유지한다.
#     "어젯밤 3시에 멈췄는데 그때 스레드가 뭘 했나" 를 사후에 답할 수 있다.
#     사람이 그 시점에 붙어 있을 필요가 없다.
#   settings=profile       → JDK 가 "통상 2% 내외 오버헤드" 로 명시한 프로파일.
#     더 가볍게 하려면 settings=default("상시 사용 안전, 1% 미만").
#   dumponexit=true        → 컨테이너가 죽을 때 마지막 상태를 남긴다.
#
# 담기는 것: jdk.ThreadDump(jstack 형식), jdk.JavaMonitorEnter(락 대기 + previousOwner),
#           jdk.ThreadPark, jdk.SocketRead 등이 기본 활성이다.
#
# 주의: JFR 보존은 chunk 단위라 maxage=6h 가 정확히 6시간을 보장하지는 않는다.
# 덤프: docker exec <c> jcmd 1 JFR.dump name=app filename=/app/logs/jfr/dump.jfr
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseContainerSupport", \
  "-XX:StartFlightRecording=name=app,disk=true,maxage=6h,maxsize=512m,settings=profile,dumponexit=true,filename=/app/logs/jfr/onexit.jfr", \
  "-XX:FlightRecorderOptions=repository=/app/logs/jfr", \
  "-Duser.timezone=Asia/Seoul", \
  "-jar", "app.jar"]
