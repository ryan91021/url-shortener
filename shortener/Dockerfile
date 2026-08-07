# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先只 COPY pom.xml，讓「下載依賴」這層能被 Docker layer cache 重用
# （之後只改 src、沒動 pom.xml 時，重 build 不必重新下載依賴）
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 再 COPY 原始碼、打包成 fat jar；-DskipTests：image build 不依賴外部 Redis/AWS
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- runtime stage ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 只把「可執行 fat jar」從 build stage 搬過來（不帶 maven、不帶原始碼 → image 小很多）
COPY --from=build /app/target/shortener-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]