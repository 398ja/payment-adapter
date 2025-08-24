# Docker Usage

The REST service includes a Dockerfile at `cashu-gateway-rest/Dockerfile`. Build the image:

```bash
docker build -t cashu-gateway-rest cashu-gateway-rest
```

`docker-compose.yml` can build and run all images:

```bash
docker-compose build
docker-compose up
```

## Publishing with Maven

The project is configured with the [Jib](https://github.com/GoogleContainerTools/jib) Maven plugin. Executing:

```bash
./mvnw deploy
```

builds the REST module and pushes the resulting image to `docker.398ja.xyz/cashu-gateway-rest`.
