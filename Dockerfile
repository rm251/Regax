FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

COPY . /app

RUN javac CryptoWebApp.java

EXPOSE 8080

CMD ["java", "CryptoWebApp"]
