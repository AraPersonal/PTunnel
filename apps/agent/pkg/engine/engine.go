package engine

import (
	"context"
)

// Engine defines the common lifecycle of a proxy protocol backend (e.g., Xray or Hysteria2).
type Engine interface {
	// Name returns the identifier of the engine (e.g. "xray", "hysteria2").
	Name() string

	// Start initializes and runs the engine using the given raw configuration.
	Start(ctx context.Context, configJSON string, tunFd int) error

	// Stop cleanly tears down the running engine instance.
	Stop() error

	// IsRunning checks whether the engine process or loop is currently active.
	IsRunning() bool

	// LocalProxyURL returns the local loopback proxy address (e.g., "http://127.0.0.1:38104" or socks5).
	LocalProxyURL() string
}
