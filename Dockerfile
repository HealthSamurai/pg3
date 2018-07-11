FROM clojure:lein as builder
RUN mkdir /app
WORKDIR /app
ADD . /app
RUN lein uberjar

FROM java:8
EXPOSE 8080

COPY --from=builder /app/target/pg3.jar /pg3.jar

COPY entrypoint /usr/local/bin/
RUN chmod u+x /usr/local/bin/entrypoint

ENV DOCKER_API_VERSION 1.23
ENTRYPOINT ["/usr/local/bin/entrypoint"]

