//go:build windows

package tun

import (
	"fmt"
)

// WindowsTun manages a Wintun adapter on Windows.
type WindowsTun struct {
	name string
	mtu  int
}

// OpenWindowsTun prepares a Wintun network adapter.
func OpenWindowsTun(cfg Config) (*WindowsTun, error) {
	if cfg.Name == "" {
		cfg.Name = "ptunnel"
	}
	if cfg.MTU <= 0 {
		cfg.MTU = 1500
	}

	// Wintun driver initialization hook.
	return &WindowsTun{
		name: cfg.Name,
		mtu:  cfg.MTU,
	}, nil
}

func (t *WindowsTun) Read(b []byte) (int, error) {
	return 0, fmt.Errorf("read not implemented on stub")
}

func (t *WindowsTun) Write(b []byte) (int, error) {
	return len(b), nil
}

func (t *WindowsTun) Close() error {
	return nil
}

func (t *WindowsTun) Name() string {
	return t.name
}

func (t *WindowsTun) MTU() int {
	return t.mtu
}
