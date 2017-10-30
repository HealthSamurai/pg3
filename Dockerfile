FROM java:8
EXPOSE 8080

ADD target/pg3.jar /pg3.jar

CMD java -cp /pg3.jar clojure.main -m pg3.core
