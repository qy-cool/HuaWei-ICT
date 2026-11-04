.PHONY: build build-linux run test test-race vet lint clean

build:
	go build -o bin/cloide-agent.exe ./cmd/cloide-agent

build-linux:
	GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o bin/cloide-agent-linux-amd64 ./cmd/cloide-agent

run:
	go run ./cmd/cloide-agent

test:
	go test ./...

test-race:
	go test -race ./...

vet:
	go vet ./...

lint: vet
	@echo "lint: use go vet for now"

clean:
	rm -rf bin/
