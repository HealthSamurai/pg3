FROM clojure:lein as builder
RUN mkdir /app
WORKDIR /app
ADD . /app
RUN lein uberjar

FROM java:8
EXPOSE 8080

COPY --from=builder /app/target/pg3.jar /pg3.jar

CMD java -cp /pg3.jar clojure.main -m pg3.core
