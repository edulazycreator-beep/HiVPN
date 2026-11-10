# Builds both binaries; the runtime image has no shell and runs as non-root.
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY . .
RUN go mod tidy && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/hivpn-api ./cmd/api \
 && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/hivpn-nodeagent ./cmd/nodeagent

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/hivpn-api /usr/local/bin/hivpn-api
USER nonroot
EXPOSE 8080 8443
ENTRYPOINT ["/usr/local/bin/hivpn-api"]
CMD ["serve"]
