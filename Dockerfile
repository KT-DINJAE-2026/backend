# 시연용 배포 이미지(docs/CLOUD_ARCHITECTURE.md). 서비스와 backend-init Job이 같은 이미지를 쓴다.
# 모델(models/)과 기반정보(masterdata/)는 git에 없으므로 두 디렉터리를 채운 로컬에서
# `gcloud builds submit`으로 빌드해야 한다(.gcloudignore 참고).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# 래퍼·빌드 스크립트를 먼저 복사해 Gradle 배포판·의존성 다운로드 레이어를 캐시한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
RUN ./gradlew --no-daemon --version
COPY src/ src/
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
COPY models/ /models/
COPY masterdata/ /masterdata/
ENV ML_MODEL_DIR=/models
EXPOSE 8080
# 컨테이너 메모리 1GiB 기준. JVM 기본값(25%)은 힙 256MiB뿐이라 상향한다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
