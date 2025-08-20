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
