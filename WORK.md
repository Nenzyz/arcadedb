docker build -f docker/images/n8n-custom/Dockerfile --platform linux/amd64 --push -t docker.true-item.com/n8n .

mvn package -DskipTests -Pdocker,arm64 -rf :arcadedb-package

mvn package deploy -DskipTests -Pdocker -Ddocker-maven-plugin.platform=linux/arm64
mvn clean install deploy -Pdocker -Ddocker-maven-plugin.platform=linux/arm64


mvn package -DskipTests -Pdocker -Ddocker-maven-plugin.platform=linux/arm64


docker run -d -p 2480:2480 -p 2424:2424 -p 6379:6379 -p 5433:5432 -p 8182:8182 -p 27017:27017 \
    --name arcadedb \
    -v ./arcadedb:/home/arcadedb/databases \
    --env JAVA_OPTS="-Darcadedb.server.rootPassword=M9YA1qQxSyMLnKXsjjGdhMVrjz1k5ghQ -Darcadedb.redis.debug=true -Darcadedb.server.plugins=Redis:com.arcadedb.redis.RedisProtocolPlugin,Postgres:com.arcadedb.postgres.PostgresProtocolPlugin,MongoDB:com.arcadedb.mongo.MongoDBProtocolPlugin" \
    nenzyz/arcadedb:latest
