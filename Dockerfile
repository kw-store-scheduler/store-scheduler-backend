# 백엔드(Java)와 최적화 엔진(Python OR-Tools)을 한 이미지로 실행한다
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY Store_Scheduler_Backend/ .
RUN ./gradlew bootJar --no-daemon -x test

FROM python:3.12-slim
RUN pip install --no-cache-dir ortools==9.15.6755
COPY --from=eclipse-temurin:17-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    PYTHON_COMMAND=python3
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
# DB_URL이 없으면 Render가 주는 DB_HOST/DB_PORT/DB_NAME으로 JDBC URL을 만든다
# 서버 기본 시간대가 UTC라 한국 시간 오전에 '오늘'이 하루 밀리지 않도록 서울로 고정한다
CMD ["sh", "-c", "export DB_URL=${DB_URL:-jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME}; exec java -Duser.timezone=Asia/Seoul $JAVA_OPTS -jar app.jar"]
