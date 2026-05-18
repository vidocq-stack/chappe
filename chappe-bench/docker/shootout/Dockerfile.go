# syntax=docker/dockerfile:1.7
ARG GO_TAG=1.24-alpine

FROM golang:${GO_TAG} AS build
WORKDIR /src
COPY chappe-bench/src/main/go/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /out/go-server server.go

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/go-server /go-server
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/go-server"]
